package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BoundedMessageGateway implements MessageGateway, AutoCloseable {
    private record Receipt(UUID messageId, Instant expiresAt) {}
    private record PendingPublish(UUID messageId, CompletableFuture<ExternalPublishResult> result) {}
    private final ChannelDirectory channels;
    private final ExternalDeliverySink sink;
    private final Clock clock;
    private final String sourceServerId;
    private final int maxReceipts;
    private final ThreadPoolExecutor admission;
    private final ThreadPoolExecutor observers;
    private final CopyOnWriteArraySet<AcceptedMessageListener> listeners = new CopyOnWriteArraySet<>();
    private final Map<ExternalMessageIdentity, Receipt> externalReceipts = new LinkedHashMap<>();
    private final Map<ExternalMessageIdentity, PendingPublish> pendingPublishes = new LinkedHashMap<>();
    private final Map<UUID, Instant> observedLogicalIds = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final class PublishTask implements Runnable {
        private final ExternalMessageRequest request;
        private final CompletableFuture<ExternalPublishResult> result;
        private PublishTask(ExternalMessageRequest request, CompletableFuture<ExternalPublishResult> result) {
            this.request = request;
            this.result = result;
        }
        @Override public void run() { publishOnAdmissionThread(request, result); }
        private void rejectClosed() { result.complete(unavailable("GATEWAY_CLOSED")); }
    }

    public BoundedMessageGateway(ChannelDirectory channels, ExternalDeliverySink sink, Clock clock,
            String sourceServerId, int maxPending, int maxReceipts) {
        this.channels = Objects.requireNonNull(channels);
        this.sink = Objects.requireNonNull(sink);
        this.clock = Objects.requireNonNull(clock);
        this.sourceServerId = Objects.requireNonNull(sourceServerId);
        if (maxPending < 1 || maxReceipts < 1) throw new IllegalArgumentException("bounds must be positive");
        this.maxReceipts = maxReceipts;
        this.admission = executor("lunachat-admission", 1, maxPending);
        this.observers = executor("lunachat-observer", 2, maxPending);
    }

    private static ThreadPoolExecutor executor(String name, int threads, int capacity) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public Subscription observeAcceptedMessages(AcceptedMessageListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) throw new IllegalStateException("gateway is closed");
        listeners.add(listener);
        AtomicBoolean subscribed = new AtomicBoolean(true);
        return () -> { if (subscribed.compareAndSet(true, false)) listeners.remove(listener); };
    }

    @Override public CompletionStage<ExternalPublishResult> publishExternal(ExternalMessageRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) return CompletableFuture.completedFuture(unavailable("GATEWAY_CLOSED"));
        CompletableFuture<ExternalPublishResult> result = new CompletableFuture<>();
        try {
            admission.execute(new PublishTask(request, result));
        } catch (RejectedExecutionException full) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.OVER_CAPACITY, true, "ADMISSION_FULL"));
        }
        return result;
    }

    private synchronized void publishOnAdmissionThread(ExternalMessageRequest request, CompletableFuture<ExternalPublishResult> result) {
        Instant now = clock.instant();
        purge(now);
        Receipt existing = externalReceipts.get(request.identity());
        if (existing != null) {
            result.complete(new ExternalPublishResult(PublishStatus.DUPLICATE, existing.messageId(), false, "DUPLICATE_IDENTITY"));
            return;
        }
        PendingPublish pending = pendingPublishes.get(request.identity());
        if (pending != null) {
            pending.result().whenComplete((first, error) -> {
                if (error != null) result.completeExceptionally(error);
                else if (first.status() == PublishStatus.ACCEPTED || first.status() == PublishStatus.DUPLICATE) {
                    result.complete(new ExternalPublishResult(PublishStatus.DUPLICATE, pending.messageId(), false,
                            "DUPLICATE_IDENTITY"));
                } else result.complete(first);
            });
            return;
        }
        if (request.createdAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.INVALID, false, "CREATED_AT_IN_FUTURE"));
            return;
        }
        Instant expiresAt = request.createdAt().plus(request.requestedLifetime());
        if (!expiresAt.isAfter(now)) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.EXPIRED, false, "MESSAGE_EXPIRED"));
            return;
        }
        ChannelDescriptor channel = channels.find(request.channelId()).orElse(null);
        if (channel == null) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.CHANNEL_NOT_FOUND, false, "CHANNEL_NOT_FOUND"));
            return;
        }
        if (!channel.acceptsExternalMessages()) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.FORBIDDEN, false, "EXTERNAL_DISABLED"));
            return;
        }
        if (externalReceipts.size() + pendingPublishes.size() >= maxReceipts) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.OVER_CAPACITY, true, "DEDUP_FULL"));
            return;
        }
        UUID logicalId = UUID.randomUUID();
        AcceptedMessage accepted = new AcceptedMessage(logicalId, channel.id(), channel.name(),
                new MessageOrigin(OriginKind.EXTERNAL, request.identity().namespace(), request.identity().value()),
                request.author(), sourceServerId, request.content(), request.createdAt(), expiresAt);
        pendingPublishes.put(request.identity(), new PendingPublish(logicalId, result));
        sink.commit(accepted).whenComplete((finalMessage, error) -> {
            completeCommit(request.identity(), accepted, finalMessage, error, result);
        });
    }

    private synchronized void completeCommit(ExternalMessageIdentity identity, AcceptedMessage accepted,
            AcceptedMessage finalMessage, Throwable error, CompletableFuture<ExternalPublishResult> result) {
        pendingPublishes.remove(identity);
        if (closed.get()) {
            result.complete(unavailable("GATEWAY_CLOSED"));
            return;
        }
        if (error != null || finalMessage == null) {
            result.complete(unavailable(error == null ? "DELIVERY_UNAVAILABLE" : "DELIVERY_FAILED"));
            return;
        }
        if (!accepted.messageId().equals(finalMessage.messageId())
                || !accepted.channelId().equals(finalMessage.channelId())
                || !accepted.origin().equals(finalMessage.origin())) {
            result.complete(ExternalPublishResult.rejected(PublishStatus.INVALID, false, "SINK_CHANGED_IDENTITY"));
            return;
        }
        externalReceipts.put(identity, new Receipt(accepted.messageId(), accepted.expiresAt().plus(Duration.ofMinutes(5))));
        acceptOnAdmissionThread(finalMessage);
        result.complete(new ExternalPublishResult(PublishStatus.ACCEPTED, accepted.messageId(), false, "ACCEPTED"));
    }

    /** Called at the final LunaChat acceptance boundary for Minecraft/system messages. */
    public boolean accept(AcceptedMessage message) {
        if (closed.get()) return false;
        try { admission.execute(() -> acceptOnAdmissionThread(message)); }
        catch (RejectedExecutionException full) { return false; }
        return true;
    }

    private synchronized boolean acceptOnAdmissionThread(AcceptedMessage message) {
        Instant now = clock.instant();
        purge(now);
        if (observedLogicalIds.containsKey(message.messageId())) return false;
        if (observedLogicalIds.size() >= maxReceipts) return false;
        observedLogicalIds.put(message.messageId(), message.expiresAt().plus(Duration.ofMinutes(5)));
        for (AcceptedMessageListener listener : listeners) {
            try { observers.execute(() -> { try { listener.onAccepted(message); } catch (RuntimeException ignored) {} }); }
            catch (RejectedExecutionException ignored) { /* bounded loss is isolated from chat delivery */ }
        }
        return true;
    }

    private void purge(Instant now) {
        externalReceipts.values().removeIf(receipt -> !receipt.expiresAt().isAfter(now));
        observedLogicalIds.values().removeIf(expiry -> !expiry.isAfter(now));
    }

    private static ExternalPublishResult unavailable(String code) {
        return ExternalPublishResult.rejected(PublishStatus.UNAVAILABLE, true, code);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        listeners.clear();
        synchronized (this) {
            pendingPublishes.values().forEach(pending -> pending.result().complete(unavailable("GATEWAY_CLOSED")));
            pendingPublishes.clear();
        }
        for (Runnable queued : admission.shutdownNow()) {
            if (queued instanceof BoundedMessageGateway.PublishTask publish) publish.rejectClosed();
        }
        observers.shutdownNow();
    }
}
