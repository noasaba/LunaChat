package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.core.InMemoryChannelDirectory;
import com.github.ucchyocean.lunachat.core.IntegrationRuntime;
import com.github.ucchyocean.lunachat.core.network.AcceptedMessageCodec;
import com.github.ucchyocean.lunachat.core.network.ChannelStateCodec;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec;
import com.github.ucchyocean.lunachat.core.network.FrameAuthenticationException;
import com.github.ucchyocean.lunachat.core.network.FrameType;
import com.github.ucchyocean.lunachat.core.network.ReliableOutbox;
import com.github.ucchyocean.lunachat.core.network.ReplayWindow;
import com.github.ucchyocean.lunachat.core.network.ReplayFrameException;
import com.github.ucchyocean.lunachat.core.network.SecureFrame;
import com.github.ucchyocean.lunachat.core.network.SecureFrameCodec;
import com.github.ucchyocean.lunachat.api.RuntimeRole;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity network authority and authenticated routing state machine. */
final class VelocityNetworkAuthority implements AutoCloseable {
    private record Session(UUID id, long epoch, long revision) {}
    private record PendingExternal(AcceptedMessage message, CompletableFuture<AcceptedMessage> completion) {}
    private enum LogicalAdmission { NEW, PENDING, DUPLICATE, FULL }
    private final ProxyServer proxy;
    private final Logger logger;
    private final ChannelIdentifier channel;
    private final AuthorityChannelStore store;
    private final AuthorityMembershipStore memberships;
    private final InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
    private final SecureFrameCodec secure;
    private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
    private final AuthoritySnapshotCodec channelStates = new AuthoritySnapshotCodec();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ReliableOutbox> outboxes = new ConcurrentHashMap<>();
    /**
     * External publishes remain pending until a Paper edge acknowledges that
     * LunaChat accepted and rendered the canonical message.  Putting a frame
     * in an outbox is transport admission only; it is not API admission.
     */
    private final Map<UUID, PendingExternal> pendingExternal = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> inboundReceipts = new LinkedHashMap<>();
    private final Map<UUID, Instant> inboundPending = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int pendingCapacity;
    private final int receiptCapacity;
    private final IntegrationRuntime runtime;

    VelocityNetworkAuthority(ProxyServer proxy, Logger logger, ChannelIdentifier channel,
            AuthorityChannelStore store, byte[] secret, int pendingCapacity, int receiptCapacity) throws IOException {
        this.proxy = proxy;
        this.logger = logger;
        this.channel = channel;
        this.store = store;
        this.memberships = new AuthorityMembershipStore(store.directory(), store.snapshot(), store.settings());
        this.pendingCapacity = pendingCapacity;
        this.receiptCapacity = receiptCapacity;
        directory.replace(store.snapshot());
        secure = new SecureFrameCodec(4, secret, new ReplayWindow(receiptCapacity), Clock.systemUTC());
        proxy.getAllServers().forEach(server -> outboxes.put(server.getServerInfo().getName(),
                new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1))));
        runtime = IntegrationRuntime.authority(RuntimeRole.NETWORK_AUTHORITY, directory, this::commitExternal,
                Clock.systemUTC(), "velocity", pendingCapacity, receiptCapacity);
    }

    IntegrationRuntime runtime() { return runtime; }
    synchronized java.util.List<com.github.ucchyocean.lunachat.api.ChannelDescriptor> channels() { return store.snapshot(); }
    synchronized AuthoritySnapshotCodec.Settings snapshotSettings() { return store.settings(); }
    synchronized void createChannel(String name, boolean external) throws IOException { store.create(name, external); refreshAuthority(); }
    synchronized void deleteChannel(String name) throws IOException { store.delete(name); refreshAuthority(); }
    synchronized void setAlias(String name, String alias) throws IOException { store.alias(name, alias); refreshAuthority(); }
    synchronized void setSettings(String defaultChannel, java.util.Set<String> force) throws IOException { store.settings(defaultChannel, force); refreshAuthority(); }
    private void refreshAuthority() throws IOException {
        memberships.refreshCatalog(store.snapshot(), store.settings());
        directory.replace(store.snapshot());
        for (String node : sessions.keySet()) sendState(node);
    }

    synchronized void handle(PluginMessageEvent event) {
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        String sourceNode = source.getServerInfo().getName();
        try {
            SecureFrame frame = secure.decode(event.getData());
            if (frame.type() == FrameType.HELLO) {
                Session existing = sessions.get(sourceNode);
                // A periodic heartbeat is not a new handshake. Replaying the
                // catalog here resets live Paper membership and opens a window
                // where an in-flight MESSAGE appears to be unauthenticated.
                if (existing != null && existing.id().equals(frame.sessionId())
                        && existing.epoch() == frame.epoch() && isCatalogSynchronized(sourceNode)) return;
                sessions.put(sourceNode, new Session(frame.sessionId(), frame.epoch(), -1));
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.READY, null,
                        sourceNode.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(10));
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.STATE, null,
                        channelStates.encode(memberships.snapshot()), Instant.now().plusSeconds(30));
                return;
            }
            requireSession(sourceNode, frame);
            if (frame.type() == FrameType.STATE) {
                var acknowledged = channelStates.decode(frame.payload());
                if (acknowledged.revision() < memberships.snapshot().revision()) {
                    sendState(sourceNode);
                    return;
                }
                if (!memberships.snapshot().equals(acknowledged)) {
                    throw new FrameAuthenticationException("channel catalog acknowledgement mismatch");
                }
                Session session = sessions.get(sourceNode);
                sessions.put(sourceNode, new Session(session.id(), session.epoch(), acknowledged.revision()));
            } else if (frame.type() == FrameType.MEMBER_CHANGE && frame.logicalMessageId() != null) {
                // Only authenticated server connections reach this path. Paper
                // invokes it after its existing command permissions/events.
                var change = channelStates.decodeChange(frame.payload());
                String result;
                try { result = memberships.change(change); }
                catch (IOException unavailable) {
                    logger.error("LunaChat membership update could not be persisted; existing state retained", unavailable);
                    result = "UNAVAILABLE";
                }
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.MEMBER_RESULT,
                        frame.logicalMessageId(), result.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(30));
                for (String node : sessions.keySet()) sendState(node);
            } else if (frame.type() == FrameType.MESSAGE && frame.logicalMessageId() != null) {
                if (!isCatalogSynchronized(sourceNode)) {
                    // requireSession already authenticated this sender. The
                    // outbox retries without an ACK after catalog sync finishes.
                    logger.debug("Deferred LunaChat message from {} pending catalog synchronization", sourceNode);
                    return;
                }
                AcceptedMessage accepted = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(accepted.messageId()) || !sourceNode.equals(accepted.sourceServerId())) {
                    throw new FrameAuthenticationException("message identity mismatch");
                }
                LogicalAdmission admission = reserveLogical(accepted.messageId(), accepted.expiresAt());
                if (admission == LogicalAdmission.DUPLICATE) {
                    sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.ACK, accepted.messageId(),
                            new byte[0], Instant.now().plusSeconds(30));
                } else if (admission == LogicalAdmission.NEW) {
                    runtime.authorityGateway().acceptAsync(accepted).whenComplete((admitted, failure) -> {
                        if (failure == null && Boolean.TRUE.equals(admitted)
                                && completeLogical(accepted.messageId(), accepted.expiresAt())) {
                            enqueueForOtherBackends(sourceNode, accepted);
                            sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.ACK, accepted.messageId(),
                                    new byte[0], Instant.now().plusSeconds(30));
                        } else {
                            releaseLogical(accepted.messageId());
                        }
                    });
                }
            } else if (frame.type() == FrameType.ACK && frame.logicalMessageId() != null) {
                ReliableOutbox outbox = outboxes.get(sourceNode);
                byte[] proposedPayload = outbox == null ? null
                        : outbox.payload(frame.logicalMessageId(), Instant.now()).orElse(null);
                if (frame.payload().length == 0) {
                    if (proposedPayload == null) return;
                    AcceptedMessage proposed = messages.decode(proposedPayload);
                    if (proposed.origin().kind() == com.github.ucchyocean.lunachat.api.OriginKind.EXTERNAL
                            || pendingExternal.containsKey(frame.logicalMessageId())) {
                        throw new FrameAuthenticationException("external acknowledgement payload is required");
                    }
                    outbox.acknowledge(frame.logicalMessageId());
                    return;
                }
                AcceptedMessage acknowledged = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(acknowledged.messageId())) {
                    throw new FrameAuthenticationException("acknowledgement identity mismatch");
                }
                if (proposedPayload != null) validateStableIdentity(messages.decode(proposedPayload), acknowledged);
                PendingExternal pending = validateExternalAcknowledgement(acknowledged);
                if (outbox != null) outbox.acknowledge(frame.logicalMessageId());
                completeExternalAcknowledgement(pending, acknowledged);
            }
        } catch (ReplayFrameException replay) {
            logger.debug("Discarded replayed LunaChat frame from {}", sourceNode);
        } catch (Exception rejected) {
            sessions.remove(sourceNode);
            logger.warn("Rejected LunaChat frame from {}: {}", sourceNode, rejected.getMessage());
        }
    }

    private synchronized CompletableFuture<AcceptedMessage> commitExternal(AcceptedMessage message) {
        AcceptedMessage canonical = messages.canonicalize(message);
        CompletableFuture<AcceptedMessage> completion = new CompletableFuture<>();
        PendingExternal pending = new PendingExternal(canonical, completion);
        pendingExternal.put(canonical.messageId(), pending);
        int committed = 0;
        Instant now = Instant.now();
        for (Map.Entry<String, ReliableOutbox> entry : outboxes.entrySet()) {
            if (isCatalogSynchronized(entry.getKey())
                    && entry.getValue().offer(canonical.messageId(), messages.encode(canonical), canonical.expiresAt(), now)) committed++;
        }
        if (committed == 0) {
            pendingExternal.remove(canonical.messageId(), pending);
            completion.complete(null);
        } else {
            if (committed < outboxes.size()) {
                logger.warn("External message {} admitted with partial backend coverage ({}/{})",
                        canonical.messageId(), committed, outboxes.size());
            }
        }
        return completion;
    }

    private PendingExternal validateExternalAcknowledgement(AcceptedMessage acknowledged)
            throws FrameAuthenticationException {
        PendingExternal pending = pendingExternal.get(acknowledged.messageId());
        if (pending != null) validateStableIdentity(pending.message(), acknowledged);
        return pending;
    }

    /** Completes API admission with the content finalized by the accepting Paper. */
    private void completeExternalAcknowledgement(PendingExternal pending, AcceptedMessage acknowledged) {
        if (pending == null) return;
        if (pendingExternal.remove(acknowledged.messageId(), pending)) {
            pending.completion().complete(acknowledged);
        }
    }

    private static void validateStableIdentity(AcceptedMessage proposed, AcceptedMessage acknowledged)
            throws FrameAuthenticationException {
        if (!proposed.messageId().equals(acknowledged.messageId())
                || !proposed.channelId().equals(acknowledged.channelId())
                || !proposed.origin().equals(acknowledged.origin())
                || !proposed.author().equals(acknowledged.author())
                || !proposed.sourceServerId().equals(acknowledged.sourceServerId())
                || !proposed.createdAt().equals(acknowledged.createdAt())
                || !proposed.expiresAt().equals(acknowledged.expiresAt())) {
            throw new FrameAuthenticationException("acknowledgement changed message identity");
        }
    }

    private void enqueueForOtherBackends(String sourceNode, AcceptedMessage message) {
        AcceptedMessage canonical = messages.canonicalize(message);
        byte[] payload = messages.encode(canonical);
        Instant now = Instant.now();
        outboxes.forEach((node, outbox) -> {
            if (!node.equals(sourceNode) && isCatalogSynchronized(node)
                    && !outbox.offer(canonical.messageId(), payload, canonical.expiresAt(), now)) {
                logger.warn("Network outbox for {} rejected logical message {}", node, canonical.messageId());
            }
        });
    }

    void tick() {
        Instant now = Instant.now();
        java.util.Set<String> activeNodes = proxy.getAllServers().stream()
                .map(server -> server.getServerInfo().getName()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        activeNodes.forEach(node -> outboxes.computeIfAbsent(node,
                ignored -> new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1))));
        outboxes.keySet().stream().filter(node -> !activeNodes.contains(node)).toList().forEach(node -> {
            outboxes.remove(node);
            sessions.remove(node);
        });
        directory.replace(store.snapshot());
        synchronized (this) {
            pendingExternal.forEach((messageId, pending) -> {
                boolean queued = outboxes.values().stream().anyMatch(outbox -> outbox.contains(messageId, now));
                if ((!pending.message().expiresAt().isAfter(now) || !queued)
                        && pendingExternal.remove(messageId, pending)) {
                    pending.completion().complete(null);
                }
            });
        }
        synchronized (this) {
            inboundReceipts.values().removeIf(expiry -> !expiry.isAfter(now));
            inboundPending.values().removeIf(expiry -> !expiry.isAfter(now));
        }
        outboxes.forEach((node, outbox) -> {
            Session session = sessions.get(node);
            if (session == null) return;
            if (!isCatalogSynchronized(node)) { sendState(node); return; }
            for (ReliableOutbox.Attempt attempt : outbox.pollDue(now, 32)) {
                sendAttempt(node, session, attempt);
            }
        });
    }

    private synchronized LogicalAdmission reserveLogical(UUID logicalId, Instant expiry) {
        Instant now = Instant.now();
        inboundReceipts.values().removeIf(value -> !value.isAfter(now));
        inboundPending.values().removeIf(value -> !value.isAfter(now));
        if (inboundReceipts.containsKey(logicalId)) return LogicalAdmission.DUPLICATE;
        if (inboundPending.containsKey(logicalId)) return LogicalAdmission.PENDING;
        if (inboundReceipts.size() + inboundPending.size() >= receiptCapacity) return LogicalAdmission.FULL;
        inboundPending.put(logicalId, expiry);
        return LogicalAdmission.NEW;
    }

    private synchronized boolean completeLogical(UUID logicalId, Instant expiry) {
        if (inboundPending.remove(logicalId) == null || !expiry.isAfter(Instant.now())) return false;
        inboundReceipts.put(logicalId, expiry.plus(Duration.ofMinutes(5)));
        return true;
    }

    private synchronized void releaseLogical(UUID logicalId) {
        inboundPending.remove(logicalId);
    }

    private void requireSession(String node, SecureFrame frame) throws FrameAuthenticationException {
        Session expected = sessions.get(node);
        if (expected == null || !expected.id().equals(frame.sessionId()) || expected.epoch() != frame.epoch()) {
            throw new FrameAuthenticationException("unknown or stale session");
        }
    }

    private boolean isCatalogSynchronized(String node) {
        Session session = sessions.get(node);
        return session != null && session.revision() == memberships.snapshot().revision();
    }

    private void sendState(String node) {
        Session session = sessions.get(node);
        if (session != null) sendNow(node, session.id(), session.epoch(), FrameType.STATE, null,
                channelStates.encode(memberships.snapshot()), Instant.now().plusSeconds(30));
    }

    private void sendAttempt(String node, Session session, ReliableOutbox.Attempt attempt) {
        SecureFrame frame = new SecureFrame(4, session.id(), session.epoch(), attempt.sequence(), attempt.frameId(),
                attempt.logicalMessageId(), FrameType.MESSAGE, Instant.now(), attempt.expiresAt(), attempt.payload());
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    private void sendNow(String node, UUID sessionId, long epoch, FrameType type, UUID logicalId,
            byte[] payload, Instant expiresAt) {
        SecureFrame frame = new SecureFrame(4, sessionId, epoch, sequence.incrementAndGet(), UUID.randomUUID(),
                logicalId, type, Instant.now(), expiresAt, payload);
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    @Override public void close() {
        sessions.clear();
        pendingExternal.values().forEach(pending -> pending.completion().complete(null));
        pendingExternal.clear();
        runtime.close();
    }
}
