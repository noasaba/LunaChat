package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedMessageGatewayTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final ChannelDescriptor CHANNEL = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);

    @Test void observerFailureDoesNotFailPublishAndDuplicateNotifiesOnce() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory(); directory.put(CHANNEL);
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory,
                message -> CompletableFuture.completedFuture(message), Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 16, 16);
        CountDownLatch observed = new CountDownLatch(1); AtomicInteger count = new AtomicInteger();
        gateway.observeAcceptedMessages(message -> { throw new IllegalStateException("observer failure"); });
        gateway.observeAcceptedMessages(message -> { count.incrementAndGet(); observed.countDown(); });
        ExternalMessageRequest request = request("42", NOW);
        ExternalPublishResult first = gateway.publishExternal(request).toCompletableFuture().get(2, TimeUnit.SECONDS);
        ExternalPublishResult duplicate = gateway.publishExternal(request).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(observed.await(2, TimeUnit.SECONDS));
        assertEquals(PublishStatus.ACCEPTED, first.status());
        assertEquals(PublishStatus.DUPLICATE, duplicate.status());
        assertEquals(first.messageId(), duplicate.messageId());
        assertEquals(1, count.get());
        gateway.close();
    }

    @Test void expiredAndForbiddenRequestsAreExplicit() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
        ChannelDescriptor forbidden = new ChannelDescriptor(CHANNEL.id(), "global", Set.of(), false); directory.put(forbidden);
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory,
                message -> CompletableFuture.completedFuture(message), Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 4, 4);
        assertEquals(PublishStatus.EXPIRED, gateway.publishExternal(request("old", NOW.minusSeconds(120)))
                .toCompletableFuture().get().status());
        assertEquals(PublishStatus.FORBIDDEN, gateway.publishExternal(request("new", NOW))
                .toCompletableFuture().get().status());
        gateway.close();
    }

    @Test void slowObserverIsBoundedAndSubscriptionCanBeRemoved() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory(); directory.put(CHANNEL);
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory,
                message -> CompletableFuture.completedFuture(message), Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 2, 16);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger removedCount = new AtomicInteger();
        Subscription slow = gateway.observeAcceptedMessages(message -> {
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        Subscription removed = gateway.observeAcceptedMessages(message -> removedCount.incrementAndGet());
        removed.close();
        ExternalPublishResult result = gateway.publishExternal(request("bounded", NOW)).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(PublishStatus.ACCEPTED, result.status());
        assertEquals(0, removedCount.get());
        slow.close();
        release.countDown();
        gateway.close();
    }

    @Test void concurrentDuplicateSharesInflightIdentityAndCommitsOnce() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory(); directory.put(CHANNEL);
        CompletableFuture<AcceptedMessage> sinkCompletion = new CompletableFuture<>();
        AtomicInteger commits = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<AcceptedMessage> proposed = new java.util.concurrent.atomic.AtomicReference<>();
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory, message -> {
            proposed.set(message);
            commits.incrementAndGet();
            return sinkCompletion;
        }, Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 16, 16);
        ExternalMessageRequest request = request("concurrent", NOW);
        CompletableFuture<ExternalPublishResult> first = gateway.publishExternal(request).toCompletableFuture();
        CompletableFuture<ExternalPublishResult> duplicate = gateway.publishExternal(request).toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (commits.get() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        sinkCompletion.complete(proposed.get());
        ExternalPublishResult firstResult = first.get(2, TimeUnit.SECONDS);
        ExternalPublishResult duplicateResult = duplicate.get(2, TimeUnit.SECONDS);
        assertEquals(1, commits.get());
        assertEquals(PublishStatus.ACCEPTED, firstResult.status());
        assertEquals(PublishStatus.DUPLICATE, duplicateResult.status());
        assertEquals(firstResult.messageId(), duplicateResult.messageId());
        gateway.close();
    }

    @Test void minecraftAcceptanceIsIndependentOfExternalChannelPolicy() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
        ChannelDescriptor channel = new ChannelDescriptor(CHANNEL.id(), "global", Set.of(), false);
        directory.put(channel);
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory,
                message -> CompletableFuture.completedFuture(message), Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 4, 4);
        CountDownLatch observed = new CountDownLatch(1);
        gateway.observeAcceptedMessages(message -> observed.countDown());
        UUID logicalId = UUID.randomUUID();
        AcceptedMessage message = new AcceptedMessage(logicalId, channel.id(), channel.name(),
                new MessageOrigin(OriginKind.MINECRAFT, "lunachat.minecraft", "minecraft-1"),
                new MessageAuthor.Player(UUID.randomUUID(), "player", "Player"), "paper-1", "hello",
                NOW, NOW.plusSeconds(60));
        assertTrue(gateway.accept(message));
        assertTrue(observed.await(2, TimeUnit.SECONDS));
        gateway.close();
    }

    @Test void synchronousSinkFailureCompletesAndReleasesPendingIdentity() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory(); directory.put(CHANNEL);
        AtomicInteger commits = new AtomicInteger();
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory, message -> {
            commits.incrementAndGet();
            throw new IllegalStateException("synchronous failure");
        }, Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 4, 4);

        ExternalMessageRequest request = request("sync-failure", NOW);
        assertEquals(PublishStatus.UNAVAILABLE,
                gateway.publishExternal(request).toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(PublishStatus.UNAVAILABLE,
                gateway.publishExternal(request).toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(2, commits.get(), "a failed identity must not remain stuck in pending state");
        gateway.close();
    }

    @Test void sinkCannotChangeStableMessageIdentity() throws Exception {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory(); directory.put(CHANNEL);
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory, message ->
                CompletableFuture.completedFuture(new AcceptedMessage(message.messageId(), message.channelId(),
                        message.channelName(), message.origin(),
                        new MessageAuthor.External("lunabridge.discord", "changed", "Changed"),
                        "other-server", message.content(), message.createdAt().plusSeconds(1),
                        message.expiresAt().plusSeconds(1))),
                Clock.fixed(NOW, ZoneOffset.UTC), "paper-1", 4, 4);

        ExternalPublishResult result = gateway.publishExternal(request("changed-identity", NOW))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(PublishStatus.INVALID, result.status());
        assertEquals("SINK_CHANGED_IDENTITY", result.diagnosticCode());
        gateway.close();
    }

    private static ExternalMessageRequest request(String id, Instant created) {
        return new ExternalMessageRequest(CHANNEL.id(), new ExternalMessageIdentity("lunabridge.discord", id),
                new MessageAuthor.External("lunabridge.discord", "user", "User"), "hello", created, Duration.ofMinutes(1));
    }
}
