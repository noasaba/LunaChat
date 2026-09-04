package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lc3.*;
import com.github.ucchyocean.lc3.bukkit.BukkitEventSender;
import com.github.ucchyocean.lc3.channel.ChannelManager;
import com.github.ucchyocean.lunachat.api.*;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.md_5.bungee.api.chat.BaseComponent;
import static org.junit.Assert.*;

/** Executes the public publish API through real BukkitChannel and both event adapters. */
public class AuthorityDeliveryTest {
    @Test public void externalDeliveryFollowsLocalPlayerAcrossReplicasWithoutBroadcast() throws Exception {
        Map<Field, Object> restore = new LinkedHashMap<>();
        List<PaperIntegrationService> services = new ArrayList<>();
        try {
            for (String field : List.of("instance", "mode", "esender")) remember(restore, LunaChat.class, field);
            remember(restore, Bukkit.class, "server");
            remember(restore, PaperIntegrationService.class, "current");
            var plugin = new LunaChatStandalone(Files.createTempDirectory("authority-delivery").toFile());
            plugin.onEnable();
            var role = LunaChatConfig.class.getDeclaredField("integrationRole"); role.setAccessible(true);
            role.set(plugin.getLunaChatConfig(), "network_edge");
            set(LunaChat.class, "mode", LunaChatMode.BUKKIT);
            set(LunaChat.class, "esender", new BukkitEventSender());
            UUID playerId = UUID.randomUUID();
            List<String> received = new ArrayList<>();
            List<String> events = new ArrayList<>();
            Map<UUID, Player> local = new HashMap<>();
            Player.Spigot spigot = new Player.Spigot() {
                @Override public void sendMessage(BaseComponent... message) { received.add(BaseComponent.toLegacyText(message)); }
            };
            Player player = proxy(Player.class, (method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName", "getDisplayName" -> "noa_berry";
                case "isOnline" -> true;
                case "spigot" -> spigot;
                case "sendMessage" -> { received.add(String.valueOf(args[0])); yield null; }
                default -> defaultValue(method.getReturnType());
            });
            PluginManager plugins = proxy(PluginManager.class, (method, args) -> {
                if (method.getName().equals("callEvent")) events.add(args[0].getClass().getSimpleName());
                return defaultValue(method.getReturnType());
            });
            set(Bukkit.class, "server", proxy(Server.class, (method, args) -> switch (method.getName()) {
                case "getPlayer", "getOfflinePlayer" -> local.get(args[0]);
                case "getOnlinePlayers" -> local.values();
                case "getPluginManager" -> plugins;
                case "getLogger" -> Logger.getLogger("authority-delivery");
                case "isPrimaryThread" -> true;
                default -> defaultValue(method.getReturnType());
            }));
            var descriptor = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
            var key = new Key(descriptor.id(), playerId);
            var snapshot = new Snapshot(1, List.of(descriptor), List.of(new Member(key, true, 1)), Set.of(descriptor.id()));
            ChannelManager first = new ChannelManager(), second = new ChannelManager();
            first.applyAuthoritySnapshot(snapshot); second.applyAuthoritySnapshot(snapshot);
            var a = new PaperIntegrationService(first, Runnable::run, Logger.getLogger("delivery-a"));
            var b = new PaperIntegrationService(second, Runnable::run, Logger.getLogger("delivery-b"));
            services.add(a); services.add(b);
            local.put(playerId, player); set(PaperIntegrationService.class, "current", a);
            assertEquals(PublishStatus.ACCEPTED, publish(a, descriptor, "first"));
            assertEquals(1, received.size());
            assertTrue(events.contains("LunaChatBukkitChannelMessageEvent"));
            assertTrue(events.contains("LunaChatChannelMessageEvent"));
            assertFalse(first.getChannel("global").isBroadcastChannel());
            assertEquals(PublishStatus.DUPLICATE, publish(a, descriptor, "first"));
            assertEquals(1, received.size());
            first.applyAuthoritySnapshot(snapshot);
            local.clear(); // The player moved away; this backend now has no local recipient.
            publish(a, descriptor, "empty-source");
            assertEquals(1, received.size());
            local.put(playerId, player); set(PaperIntegrationService.class, "current", b);
            publish(b, descriptor, "moved-target");
            assertEquals(2, received.size());
            second.applyAuthoritySnapshot(new Snapshot(2, List.of(descriptor), List.of(new Member(key, false, 2)), Set.of(descriptor.id())));
            publish(b, descriptor, "after-leave");
            assertEquals("left members must not receive external delivery", 2, received.size());
        } finally {
            services.forEach(PaperIntegrationService::stop);
            for (var entry : restore.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }

    private static PublishStatus publish(PaperIntegrationService service, ChannelDescriptor channel, String id) throws Exception {
        return service.api().messages().publishExternal(new ExternalMessageRequest(channel.id(),
                new ExternalMessageIdentity("test:discord", id), new MessageAuthor.External("test:discord", "user", "Discord User"),
                "hello", Instant.now(), Duration.ofMinutes(1))).toCompletableFuture().get(2, TimeUnit.SECONDS).status();
    }
    private static void remember(Map<Field, Object> state, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); state.put(field, field.get(null));
    }
    private static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private interface Handler { Object call(Method method, Object[] args) throws Exception; }
    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> handler.call(method, args == null ? new Object[0] : args)));
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return null;
    }
}
