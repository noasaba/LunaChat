package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;

/** Linkage-safe optional adapter for SVSync 0.1 visibility API. */
final class SVSyncVisibilityIntegration implements AutoCloseable {
    record Change(UUID player, PresenceCodec.Visibility previous, PresenceCodec.Visibility current,
            boolean connected, String server, long sequence, boolean explicitReappear) {}

    private final Object api;
    private final Method hasState;
    private final Method getVisibility;
    private final Object subscription;
    private final Method close;
    private final ConcurrentHashMap<UUID, PresenceCodec.Visibility> confirmed;
    private final Logger logger;

    private SVSyncVisibilityIntegration(Object api, Method hasState, Method getVisibility,
            Object subscription, Method close,
            ConcurrentHashMap<UUID, PresenceCodec.Visibility> confirmed, Logger logger) {
        this.api = api;
        this.hasState = hasState;
        this.getVisibility = getVisibility;
        this.subscription = subscription;
        this.close = close;
        this.confirmed = confirmed;
        this.logger = logger;
    }

    static Optional<SVSyncVisibilityIntegration> connect(ProxyServer proxy, Logger logger,
            Consumer<Change> listener) {
        try {
            var container = proxy.getPluginManager().getPlugin("svsync");
            if (container.isEmpty() || container.get().getInstance().isEmpty()) return Optional.empty();
            Object api = container.get().getInstance().get();
            Method hasState = api.getClass().getMethod("hasState", UUID.class);
            Method getVisibility = api.getClass().getMethod("getVisibility", UUID.class);
            Method addListener = java.util.Arrays.stream(api.getClass().getMethods())
                    .filter(method -> method.getName().equals("addVisibilityListener")
                            && method.getParameterCount() == 1).findFirst().orElse(null);
            if (addListener == null) {
                logger.info("SVSync visibility listener API is unavailable; LunaChat presence defaults to PUBLIC");
                return Optional.empty();
            }
            Class<?> listenerType = addListener.getParameterTypes()[0];
            ConcurrentHashMap<UUID, PresenceCodec.Visibility> confirmed = new ConcurrentHashMap<>();
            Object proxyListener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                    (ignored, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(ignored);
                            case "equals" -> ignored == args[0];
                            case "toString" -> "LunaChatSVSyncVisibilityListener";
                            default -> null;
                        };
                        if (method.getName().equals("onVisibilityChange") && args != null && args.length == 1) {
                            try {
                                listener.accept(decode(args[0]));
                            } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
                                logger.warn("Discarded incompatible SVSync visibility transition: {}",
                                        incompatible.toString());
                            }
                        }
                        return null;
                    });
            Object subscription = addListener.invoke(api, proxyListener);
            Method close = AutoCloseable.class.getMethod("close");
            logger.info("SVSync visibility state and transition integration enabled");
            return Optional.of(new SVSyncVisibilityIntegration(
                    api, hasState, getVisibility, subscription, close, confirmed, logger));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            logger.warn("SVSync API is absent or incompatible; LunaChat presence defaults to PUBLIC: {}",
                    incompatible.toString());
            return Optional.empty();
        }
    }

    PresenceCodec.Visibility visibility(UUID player) {
        try {
            // SVSync deliberately uses UNKNOWN for a missing cache entry. That
            // means "not observed", not vanished; only an explicit HIDDEN
            // state may make an otherwise online Velocity player undiscoverable.
            if (!Boolean.TRUE.equals(hasState.invoke(api, player))) {
                confirmed.remove(player);
                return PresenceCodec.Visibility.PUBLIC;
            }
            PresenceCodec.Visibility current = parse(getVisibility.invoke(api, player));
            if (current != PresenceCodec.Visibility.UNKNOWN) confirmed.put(player, current);
            if (current == PresenceCodec.Visibility.UNKNOWN) {
                logger.warn("SVSync returned UNKNOWN for an online cached player; treating it as PUBLIC: player={}",
                        player);
            }
            return normalizeCurrent(current);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            PresenceCodec.Visibility fallback = confirmed.getOrDefault(player, PresenceCodec.Visibility.UNKNOWN);
            logger.warn("SVSync visibility query failed; using last explicit state {}: player={}, failure={}",
                    fallback, player, failure.getClass().getSimpleName());
            return fallback;
        }
    }

    private static PresenceCodec.Visibility normalizeCurrent(PresenceCodec.Visibility visibility) {
        return visibility == PresenceCodec.Visibility.HIDDEN
                ? PresenceCodec.Visibility.HIDDEN : PresenceCodec.Visibility.PUBLIC;
    }

    void rememberConfirmed(Change change) {
        if (change.current() != PresenceCodec.Visibility.UNKNOWN) {
            confirmed.put(change.player(), change.current());
        }
    }

    private static Change decode(Object change) throws ReflectiveOperationException {
        Class<?> type = change.getClass();
        UUID player = (UUID) type.getMethod("playerId").invoke(change);
        PresenceCodec.Visibility previous = parse(type.getMethod("previousVisibility").invoke(change));
        PresenceCodec.Visibility current = parse(type.getMethod("visibility").invoke(change));
        boolean connected = (boolean) type.getMethod("connected").invoke(change);
        String server = (String) type.getMethod("serverName").invoke(change);
        long sequence = ((Number) type.getMethod("sequence").invoke(change)).longValue();
        boolean explicit = previous == PresenceCodec.Visibility.HIDDEN
                && current == PresenceCodec.Visibility.PUBLIC;
        return new Change(player, previous, current, connected, server, sequence, explicit);
    }

    private static PresenceCodec.Visibility parse(Object value) {
        if (value == null) return PresenceCodec.Visibility.UNKNOWN;
        try { return PresenceCodec.Visibility.valueOf(value.toString()); }
        catch (IllegalArgumentException invalid) { return PresenceCodec.Visibility.UNKNOWN; }
    }

    @Override public void close() {
        try { close.invoke(subscription); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {}
    }
}
