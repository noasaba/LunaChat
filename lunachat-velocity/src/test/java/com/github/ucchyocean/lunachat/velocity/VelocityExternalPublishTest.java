package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.ExternalMessageIdentity;
import com.github.ucchyocean.lunachat.api.ExternalMessageRequest;
import com.github.ucchyocean.lunachat.api.MessageAuthor;
import com.github.ucchyocean.lunachat.api.MessageOrigin;
import com.github.ucchyocean.lunachat.api.OriginKind;
import com.github.ucchyocean.lunachat.api.ExternalPublishResult;
import com.github.ucchyocean.lunachat.api.PublishStatus;
import com.github.ucchyocean.lunachat.core.network.AcceptedMessageCodec;
import com.github.ucchyocean.lunachat.core.network.FrameType;
import com.github.ucchyocean.lunachat.core.network.ReplayWindow;
import com.github.ucchyocean.lunachat.core.network.SecureFrame;
import com.github.ucchyocean.lunachat.core.network.SecureFrameCodec;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityExternalPublishTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final ChannelIdentifier CHANNEL = proxy(ChannelIdentifier.class,
            (method, args) -> method.getName().equals("getId") ? "lunachat:network_v2" : defaultValue(method.getReturnType()));

    @Test void authorityAssignsIdentityFromActualVelocityConnection() throws Exception {
        try (Harness harness = new Harness(1)) {
            harness.helloClaiming("backend-a", "spoofed-name");

            SecureFrame ready = harness.sent.stream()
                    .filter(frame -> frame.type() == FrameType.READY).findFirst().orElseThrow();
            assertEquals("backend-a", new String(ready.payload(), StandardCharsets.UTF_8));
        }
    }

    @Test void externalPublishCompletesAfterCanonicalPaperAcksAndSuppressesRetry() throws Exception {
        try (Harness harness = new Harness(2)) {
            harness.hello("backend-a");
            harness.hello("backend-b");
            harness.sent.clear();

            Instant created = Instant.now();
            AtomicReference<AcceptedMessage> observed = new AtomicReference<>();
            CountDownLatch observation = new CountDownLatch(1);
            harness.authority.runtime().messages().observeAcceptedMessages(message -> {
                observed.set(message);
                observation.countDown();
            });
            ExternalMessageRequest request = new ExternalMessageRequest(harness.channel.id(),
                    new ExternalMessageIdentity("lunabridge:discord", "discord-42"),
                    new MessageAuthor.External("lunabridge:discord", "user-7", "Noa"),
                    "aaaa", created, Duration.ofMinutes(5));
            CompletableFuture<ExternalPublishResult> result = harness.authority.runtime().messages()
                    .publishExternal(request).toCompletableFuture();
            assertFalse(result.isDone(), "outbox admission must not complete publish");

            harness.awaitDeliveries(2);
            List<SecureFrame> deliveries = harness.sent.stream()
                    .filter(frame -> frame.type() == FrameType.MESSAGE).toList();
            assertEquals(2, deliveries.size());
            AcceptedMessage first = harness.messages.decode(deliveries.get(0).payload());
            AcceptedMessage second = harness.messages.decode(deliveries.get(1).payload());
            assertEquals(first, second, "all backend deliveries use one canonical identity");
            assertEquals(Instant.ofEpochMilli(created.toEpochMilli()), first.createdAt());
            assertEquals(Instant.ofEpochMilli(request.createdAt().plus(request.requestedLifetime()).toEpochMilli()),
                    first.expiresAt());
            assertEquals(request.identity().namespace(), first.origin().namespace());
            assertEquals(request.identity().value(), first.origin().sourceMessageId());
            assertEquals(request.author(), first.author());
            assertEquals("velocity", first.sourceServerId());

            AcceptedMessage renderedByPaper = new AcceptedMessage(first.messageId(), first.channelId(),
                    first.channelName(), first.origin(), first.author(), first.sourceServerId(),
                    "aaaa-rendered-by-paper", first.createdAt(), first.expiresAt());
            harness.ack("backend-a", renderedByPaper);
            assertTrue(result.isDone(), "one Paper acceptance ACK completes external publish");
            assertEquals(PublishStatus.ACCEPTED, result.get().status(), result.get().toString());
            assertEquals(first.messageId(), result.get().messageId());
            assertTrue(observation.await(2, TimeUnit.SECONDS));
            assertEquals("aaaa-rendered-by-paper", observed.get().content(),
                    "authority observers receive Paper-finalized content");

            harness.ack("backend-b", second);
            harness.sent.clear();
            harness.authority.tick();
            assertTrue(harness.sent.stream().noneMatch(frame -> frame.type() == FrameType.MESSAGE),
                    "ACKed logical messages are not retried");
        }
    }

    @Test void receiptCapacityDoesNotAcknowledgeOrLoseNewMessages() throws Exception {
        try (Harness harness = new Harness(1, 4)) {
            harness.hello("backend-a");
            harness.sent.clear();
            harness.fillInboundReceipts(4);
            AcceptedMessage second = harness.minecraftMessage("backend-a", "second");
            harness.message("backend-a", second);
            Thread.sleep(100L);
            assertTrue(harness.sent.stream().noneMatch(frame -> frame.type() == FrameType.ACK),
                    "a full authority must leave the source message retryable");
        }
    }

    @Test void invalidAcknowledgementDoesNotRemoveRetryState() throws Exception {
        try (Harness harness = new Harness(1)) {
            harness.hello("backend-a");
            harness.sent.clear();
            ExternalMessageRequest request = new ExternalMessageRequest(harness.channel.id(),
                    new ExternalMessageIdentity("lunabridge:discord", "invalid-ack"),
                    new MessageAuthor.External("lunabridge:discord", "user", "User"),
                    "hello", Instant.now(), Duration.ofMinutes(5));
            CompletableFuture<ExternalPublishResult> result = harness.authority.runtime().messages()
                    .publishExternal(request).toCompletableFuture();
            harness.awaitDeliveries(1);
            AcceptedMessage proposed = harness.messages.decode(harness.sent.stream()
                    .filter(frame -> frame.type() == FrameType.MESSAGE).findFirst().orElseThrow().payload());
            AcceptedMessage changed = new AcceptedMessage(proposed.messageId(), proposed.channelId(),
                    proposed.channelName(), proposed.origin(),
                    new MessageAuthor.External("lunabridge:discord", "changed", "Changed"),
                    proposed.sourceServerId(), proposed.content(), proposed.createdAt(), proposed.expiresAt());

            harness.ack("backend-a", changed);
            assertFalse(result.isDone());
            assertEquals(1, harness.outboxSize("backend-a"),
                    "identity validation must happen before acknowledgement removal");
        }
    }

    @Test void removedBackendDoesNotKeepExternalPublishPending() throws Exception {
        try (Harness harness = new Harness(1)) {
            harness.hello("backend-a");
            ExternalMessageRequest request = new ExternalMessageRequest(harness.channel.id(),
                    new ExternalMessageIdentity("lunabridge:discord", "removed-backend"),
                    new MessageAuthor.External("lunabridge:discord", "user", "User"),
                    "hello", Instant.now(), Duration.ofMinutes(5));
            CompletableFuture<ExternalPublishResult> result = harness.authority.runtime().messages()
                    .publishExternal(request).toCompletableFuture();
            harness.awaitDeliveries(1);

            harness.servers.clear();
            harness.authority.tick();
            assertEquals(PublishStatus.UNAVAILABLE, result.get(2, TimeUnit.SECONDS).status());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ChannelDescriptor channel = new ChannelDescriptor(ChannelId.random(), "global", java.util.Set.of(), true);
        private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
        private final SecureFrameCodec inboundCodec = new SecureFrameCodec(2, SECRET,
                new ReplayWindow(128), Clock.systemUTC());
        private final SecureFrameCodec outboundCodec = new SecureFrameCodec(2, SECRET,
                new ReplayWindow(128), Clock.systemUTC());
        private final List<SecureFrame> sent = new CopyOnWriteArrayList<>();
        private final ChannelMessageSink eventTarget = proxy(ChannelMessageSink.class,
                (method, args) -> method.getName().startsWith("sendPluginMessage") ? true : defaultValue(method.getReturnType()));
        private final ProxyServer proxy;
        private final VelocityNetworkAuthority authority;
        private final Path directory;
        private final List<RegisteredServer> servers;
        private final long epoch = 7L;

        private Harness(int backendCount) throws Exception { this(backendCount, 128); }

        private Harness(int backendCount, int receiptCapacity) throws Exception {
            directory = Files.createTempDirectory("lunachat-velocity-test");
            AuthorityChannelStore store = new AuthorityChannelStore(directory);
            store.applyProposal("backend-a", List.of(channel));
            servers = new ArrayList<>();
            for (int i = 0; i < backendCount; i++) servers.add(server("backend-" + (char) ('a' + i)));
            proxy = proxy(ProxyServer.class, (method, args) -> switch (method.getName()) {
                case "getAllServers" -> servers;
                case "getServer" -> Optional.of(servers.stream()
                        .filter(server -> server.getServerInfo().getName().equals(args[0])).findFirst().orElse(null));
                default -> defaultValue(method.getReturnType());
            });
            authority = new VelocityNetworkAuthority(proxy, LoggerFactory.getLogger("VelocityExternalPublishTest"),
                    CHANNEL, store, SECRET, 32, receiptCapacity);
        }

        private RegisteredServer server(String name) {
            ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
            return proxy(RegisteredServer.class, (method, args) -> {
                if (method.getName().equals("getServerInfo")) return info;
                if (method.getName().equals("sendPluginMessage")) {
                    sent.add(outboundCodec.decode((byte[]) args[1]));
                    return true;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private void hello(String backend) {
            helloClaiming(backend, "");
        }

        private void helloClaiming(String backend, String claimedIdentity) {
            SecureFrame hello = new SecureFrame(2, session(backend), epoch, 1, UUID.randomUUID(), null,
                    FrameType.HELLO, Instant.now(), Instant.now().plusSeconds(30),
                    claimedIdentity.getBytes(StandardCharsets.UTF_8));
            authority.handle(event(backend, hello));
        }

        private void ack(String backend, AcceptedMessage message) {
            SecureFrame ack = new SecureFrame(2, session(backend), epoch, 2, UUID.randomUUID(), message.messageId(),
                    FrameType.ACK, Instant.now(), Instant.now().plusSeconds(30), messages.encode(message));
            authority.handle(event(backend, ack));
        }

        private void message(String backend, AcceptedMessage message) {
            SecureFrame frame = new SecureFrame(2, session(backend), epoch, 2, UUID.randomUUID(), message.messageId(),
                    FrameType.MESSAGE, Instant.now(), Instant.now().plusSeconds(30), messages.encode(message));
            authority.handle(event(backend, frame));
        }

        private AcceptedMessage minecraftMessage(String backend, String content) {
            Instant now = Instant.now();
            UUID id = UUID.randomUUID();
            return new AcceptedMessage(id, channel.id(), channel.name(),
                    new MessageOrigin(OriginKind.MINECRAFT, "lunachat.minecraft", id.toString()),
                    new MessageAuthor.Player(UUID.randomUUID(), "player", "Player"), backend, content,
                    now, now.plusSeconds(60));
        }

        private void awaitDeliveries(int expected) throws InterruptedException {
            for (int attempt = 0; attempt < 100; attempt++) {
                authority.tick();
                long deliveries = sent.stream().filter(frame -> frame.type() == FrameType.MESSAGE).count();
                if (deliveries >= expected) return;
                Thread.sleep(5L);
            }
        }

        @SuppressWarnings("unchecked")
        private int outboxSize(String backend) throws Exception {
            java.lang.reflect.Field field = VelocityNetworkAuthority.class.getDeclaredField("outboxes");
            field.setAccessible(true);
            Map<String, com.github.ucchyocean.lunachat.core.network.ReliableOutbox> outboxes =
                    (Map<String, com.github.ucchyocean.lunachat.core.network.ReliableOutbox>) field.get(authority);
            return outboxes.get(backend).size();
        }

        @SuppressWarnings("unchecked")
        private void fillInboundReceipts(int count) throws Exception {
            java.lang.reflect.Field field = VelocityNetworkAuthority.class.getDeclaredField("inboundReceipts");
            field.setAccessible(true);
            Map<UUID, Instant> receipts = (Map<UUID, Instant>) field.get(authority);
            synchronized (authority) {
                for (int index = 0; index < count; index++) {
                    receipts.put(UUID.randomUUID(), Instant.now().plusSeconds(60));
                }
            }
        }

        private PluginMessageEvent event(String backend, SecureFrame frame) {
            ServerConnection source = proxy(ServerConnection.class, (method, args) -> {
                if (method.getName().equals("getServerInfo")) {
                    return new ServerInfo(backend, new InetSocketAddress("127.0.0.1", 25565));
                }
                return defaultValue(method.getReturnType());
            });
            return new PluginMessageEvent(source, eventTarget, CHANNEL, inboundCodec.encode(frame));
        }

        private UUID session(String backend) {
            return UUID.nameUUIDFromBytes(("session:" + backend).getBytes(StandardCharsets.UTF_8));
        }

        @Override public void close() throws Exception {
            authority.close();
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.call(method, args == null ? new Object[0] : args));
    }

    private interface MethodHandler { Object call(java.lang.reflect.Method method, Object[] args) throws Exception; }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
