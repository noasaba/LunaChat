package com.github.ucchyocean.lunachat.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class SVSyncVisibilityIntegrationTest {
    enum Visibility { UNKNOWN, PUBLIC, HIDDEN }
    record Change(UUID playerId, Visibility previousVisibility, Visibility visibility,
            boolean connected, String serverName, long sequence) {}
    interface Listener { void onVisibilityChange(Change change); }
    static final class Api {
        Listener listener;
        boolean hasState = true;
        public boolean hasState(UUID player) { return hasState; }
        public Visibility getVisibility(UUID player) { return Visibility.HIDDEN; }
        public AutoCloseable addVisibilityListener(Listener listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }
    }

    @Test
    void discoversCurrentStateReceivesExplicitTransitionAndUnsubscribes() {
        Api api = new Api();
        List<SVSyncVisibilityIntegration.Change> received = new ArrayList<>();
        UUID player = UUID.randomUUID();
        PresenceHistory history = new PresenceHistory();
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        history.connected(player, "Alice", "lobby",
                com.github.ucchyocean.lunachat.core.network.PresenceCodec.Visibility.HIDDEN, now);
        history.connected(player, "Alice", "main",
                com.github.ucchyocean.lunachat.core.network.PresenceCodec.Visibility.HIDDEN, now.plusMillis(10));
        List<com.github.ucchyocean.lunachat.core.network.PresenceCodec.Event> revealed = new ArrayList<>();
        var integration = SVSyncVisibilityIntegration.connect(proxy(api), logger(), change -> {
            received.add(change);
            revealed.addAll(history.visibilityChanged(change.player(), "Alice", change.server(), change.current(),
                    change.explicitReappear(), now.plusMillis(20)));
        }).orElseThrow();

        assertEquals(com.github.ucchyocean.lunachat.core.network.PresenceCodec.Visibility.HIDDEN,
                integration.visibility(player));
        api.hasState = false;
        assertEquals(com.github.ucchyocean.lunachat.core.network.PresenceCodec.Visibility.PUBLIC,
                integration.visibility(player));
        api.hasState = true;
        api.listener.onVisibilityChange(new Change(player, Visibility.HIDDEN, Visibility.PUBLIC,
                true, "main", 42));

        assertEquals(1, received.size());
        assertTrue(received.get(0).explicitReappear());
        assertEquals("main", received.get(0).server());
        assertEquals(List.of(com.github.ucchyocean.lunachat.core.network.PresenceCodec.Kind.LOGIN,
                        com.github.ucchyocean.lunachat.core.network.PresenceCodec.Kind.MOVE),
                revealed.stream().map(com.github.ucchyocean.lunachat.core.network.PresenceCodec.Event::kind).toList());
        integration.close();
        assertTrue(api.listener == null);
    }

    @Test
    void oldApiWithoutListenerFallsBackSafely() {
        Object oldApi = new Object() { public Visibility getVisibility(UUID player) { return Visibility.PUBLIC; } };
        assertTrue(SVSyncVisibilityIntegration.connect(proxy(oldApi), logger(), ignored -> {}).isEmpty());
    }

    @Test
    void missingPluginFallsBackSafely() {
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class}, (proxy, method, args) -> method.getName().equals("getPlugin")
                        ? Optional.empty() : defaultValue(method.getReturnType()));
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (ignored, method, args) -> method.getName().equals("getPluginManager")
                        ? manager : defaultValue(method.getReturnType()));
        assertTrue(SVSyncVisibilityIntegration.connect(proxy, logger(), ignored -> {}).isEmpty());
    }

    private static ProxyServer proxy(Object instance) {
        PluginContainer container = (PluginContainer) Proxy.newProxyInstance(PluginContainer.class.getClassLoader(),
                new Class<?>[]{PluginContainer.class}, (proxy, method, args) -> method.getName().equals("getInstance")
                        ? Optional.of(instance) : defaultValue(method.getReturnType()));
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class}, (proxy, method, args) -> method.getName().equals("getPlugin")
                        ? Optional.of(container) : defaultValue(method.getReturnType()));
        return (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (proxy, method, args) -> method.getName().equals("getPluginManager")
                        ? manager : defaultValue(method.getReturnType()));
    }

    private static Logger logger() {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }
}
