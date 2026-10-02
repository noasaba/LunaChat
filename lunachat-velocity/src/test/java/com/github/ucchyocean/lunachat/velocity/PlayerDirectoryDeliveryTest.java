package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.*;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDirectoryDeliveryTest {
    @Test void disconnectImmediatelyRemovesPlayerOnEverySynchronizedWire8Edge() throws Exception {
        try (Harness h = new Harness(8)) {
            h.synchronize("a");
            h.synchronize("b");
            h.sent.clear();
            UUID id = UUID.randomUUID();
            h.authority.removePlayerFromDirectory(id, "Alice");
            assertEquals(2, h.sent.size());
            for (var frame : h.sent) {
                assertEquals(FrameType.PRESENCE, frame.type());
                var event = new PresenceCodec().decode(frame.payload());
                assertEquals(PresenceCodec.Kind.QUIT, event.kind());
                assertEquals(id, event.player());
                assertEquals("Alice", event.name());
            }
            h.authority.tick();
            assertEquals(2, h.sent.size(), "Directory deltas must not replay as old chat notifications");
        }
    }

    @Test void connectionAndVisibilityChangeRefreshDirectoryWithoutJoinChat() throws Exception {
        try (Harness h = new Harness(8)) {
            h.synchronize("a");
            h.sent.clear();
            UUID id = UUID.randomUUID();
            Player player = stub(Player.class, (method, args) -> switch (method) {
                case "getUniqueId" -> id;
                case "getUsername" -> "Alice";
                default -> null;
            });
            h.authority.refreshPlayerDirectory(player, "b");
            var event = new PresenceCodec().decode(h.sent.getFirst().payload());
            assertEquals(PresenceCodec.Kind.SNAPSHOT, event.kind());
            assertEquals(PresenceCodec.Visibility.PUBLIC, event.visibility());
            assertEquals("b", event.to());
            h.authority.setPresenceVisibilityProvider(p -> PresenceCodec.Visibility.HIDDEN);
            h.authority.refreshPlayerDirectory(player, "b");
            assertEquals(PresenceCodec.Visibility.HIDDEN,
                    new PresenceCodec().decode(h.sent.getLast().payload()).visibility());
        }
    }

    @Test void oldWirePeersDoNotReceiveDirectoryDeltasTheyCouldRenderAsChat() throws Exception {
        try (Harness h = new Harness(7)) {
            h.synchronize("a");
            h.sent.clear();
            h.authority.removePlayerFromDirectory(UUID.randomUUID(), "Alice");
            assertTrue(h.sent.isEmpty());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final int wire;
        private final MinecraftChannelIdentifier channel;
        private final SecureFrameCodec codec;
        private final Map<String, UUID> sessions = new HashMap<>();
        private final List<SecureFrame> sent = new ArrayList<>();
        private final java.nio.file.Path directory;
        private final VelocityNetworkAuthority authority;

        Harness(int wire) throws Exception {
            this.wire = wire;
            channel = MinecraftChannelIdentifier.create("lunachat", "network_v" + wire);
            codec = new SecureFrameCodec(wire, new byte[32], new ReplayWindow(128), Clock.systemUTC());
            List<RegisteredServer> servers = new ArrayList<>();
            for (String name : List.of("a", "b", "unsynchronized")) {
                servers.add(stub(RegisteredServer.class, (method, args) -> switch (method) {
                    case "getServerInfo" -> info(name);
                    case "sendPluginMessage" -> { sent.add(codec.decode((byte[]) args[1])); yield true; }
                    default -> null;
                }));
            }
            ProxyServer proxy = stub(ProxyServer.class, (method, args) -> switch (method) {
                case "getAllServers" -> servers;
                case "getAllPlayers" -> List.of();
                case "getServer" -> servers.stream().filter(s -> s.getServerInfo().getName().equals(args[0])).findFirst();
                default -> null;
            });
            directory = Files.createTempDirectory("lunachat-directory-test");
            authority = new VelocityNetworkAuthority(proxy, LoggerFactory.getLogger(getClass()),
                    channel, new AuthorityChannelStore(directory), new byte[32], 32, 128);
        }

        void synchronize(String node) {
            sessions.put(node, UUID.randomUUID());
            send(node, FrameType.HELLO, 1, new byte[0]);
            byte[] state = sent.stream().filter(f -> f.type() == FrameType.STATE).reduce((a, b) -> b).orElseThrow().payload();
            send(node, FrameType.STATE, 2, state);
        }

        void send(String node, FrameType type, long sequence, byte[] payload) {
            ServerConnection source = stub(ServerConnection.class,
                    (method, args) -> method.equals("getServerInfo") ? info(node) : null);
            ChannelMessageSink target = stub(ChannelMessageSink.class, (method, args) -> true);
            Instant now = Instant.now();
            var frame = new SecureFrame(wire, sessions.get(node), 1, sequence, UUID.randomUUID(),
                    null, type, now, now.plusSeconds(30), payload);
            authority.handle(new PluginMessageEvent(source, target, channel, codec.encode(frame)));
        }

        @Override public void close() throws Exception {
            authority.close();
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static ServerInfo info(String name) {
        return new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    }
    private interface Handler { Object call(String method, Object[] args) throws Exception; }
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> handler.call(method.getName(), args == null ? new Object[0] : args));
    }
}
