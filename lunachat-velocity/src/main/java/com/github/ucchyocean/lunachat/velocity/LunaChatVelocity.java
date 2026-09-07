package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.LunaChatApiProvider;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.core.network.SharedPassphrase;
import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.time.Instant;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Plugin(id = "lunachat", name = "LunaChat", version = "4.0.21-SNAPSHOT",
        dependencies = {@Dependency(id = "svsync", optional = true)},
        description = "LunaChat network authority for Velocity 4.1")
public final class LunaChatVelocity implements LunaChatApiProvider {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("lunachat", "network_v8");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile VelocityNetworkAuthority authority;
    private volatile ScheduledTask networkTask;
    private volatile PresenceVisibilityProvider presenceVisibilityProvider;
    private final PresenceHistory presenceHistory = new PresenceHistory();
    private volatile SVSyncVisibilityIntegration svsyncVisibility;
    private record QueuedVisibility(SVSyncVisibilityIntegration.Change change, long connectionGeneration) {}
    private final ArrayDeque<QueuedVisibility> visibilityChanges = new ArrayDeque<>();
    private final Object visibilityApplyLock = new Object();
    private final Map<UUID, Long> connectionGenerations = new HashMap<>();
    private final VisibilityChangeValidator visibilityChangeValidator = new VisibilityChangeValidator();
    private boolean visibilityDrainScheduled;

    @Inject
    public LunaChatVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            Properties config = loadConfig();
            byte[] secret = resolveSecret(config);
            int pending = VelocityNetworkConfig.bounded(config, "maxPending", 256);
            int receipts = VelocityNetworkConfig.bounded(config, "receiptCapacity", 4096);
            proxy.getChannelRegistrar().register(CHANNEL);
            AuthorityChannelStore store = new AuthorityChannelStore(dataDirectory);
            authority = new VelocityNetworkAuthority(proxy, logger, CHANNEL, store, secret, pending, receipts);
            svsyncVisibility = SVSyncVisibilityIntegration.connect(proxy, logger, this::queueVisibilityChange).orElse(null);
            if (svsyncVisibility != null) presenceVisibilityProvider = player -> svsyncVisibility.visibility(player.getUniqueId());
            authority.setPresenceVisibilityProvider(presenceVisibilityProvider);
            proxy.getCommandManager().register("lunachat", new VelocityAuthorityCommand(authority), "lcauthority");
            networkTask = proxy.getScheduler().buildTask(this, this::tick).repeat(Duration.ofSeconds(1)).schedule();
            logger.info("LunaChat network authority ready (API {}, wire 8; older peers are rejected)", authority.runtime().apiVersion());
        } catch (Exception failure) {
            logger.error("LunaChat authority failed closed during initialization", failure);
            authority = null;
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        VelocityNetworkAuthority current = authority;
        if (current == null) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        current.handle(event);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        VelocityNetworkAuthority current = authority; if (current == null) return;
        drainVisibilityChanges();
        var previous = event.getPreviousServer();
        if (previous.isEmpty()) synchronized (visibilityChanges) {
            connectionGenerations.merge(event.getPlayer().getUniqueId(), 1L, Long::sum);
        }
        PresenceCodec.Event presence = new PresenceCodec.Event(UUID.randomUUID(), event.getPlayer().getUniqueId(),
                event.getPlayer().getUsername(), previous.isEmpty() ? PresenceCodec.Kind.JOIN : PresenceCodec.Kind.MOVE,
                previous.map(s -> s.getServerInfo().getName()).orElse(null), event.getServer().getServerInfo().getName(), PresenceCodec.Visibility.UNKNOWN);
        publishPresenceWhenVisible(current, event.getPlayer(), presence, 0);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        VelocityNetworkAuthority current = authority; if (current == null) return;
        drainVisibilityChanges();
        synchronized (visibilityChanges) {
            connectionGenerations.merge(event.getPlayer().getUniqueId(), 1L, Long::sum);
        }
        PresenceCodec.Event presence = new PresenceCodec.Event(UUID.randomUUID(), event.getPlayer().getUniqueId(),
                event.getPlayer().getUsername(), PresenceCodec.Kind.QUIT, null, null, PresenceCodec.Visibility.UNKNOWN);
        publishPresenceWhenVisible(current, event.getPlayer(), presence, 0);
    }

    /** Called by an SVSync-compatible bridge; null restores the no-provider PUBLIC default. */
    public void setPresenceVisibilityProvider(PresenceVisibilityProvider provider) {
        presenceVisibilityProvider = provider;
        VelocityNetworkAuthority current = authority;
        if (current != null) current.setPresenceVisibilityProvider(provider);
    }

    private void publishPresenceWhenVisible(VelocityNetworkAuthority current, com.velocitypowered.api.proxy.Player player,
            PresenceCodec.Event event, int attempt) {
        PresenceVisibilityPolicy.Decision decision = PresenceVisibilityPolicy.resolve(presenceVisibilityProvider, player, attempt);
        PresenceCodec.Visibility visibility = decision.visibility();
        if (decision.retry()) {
            final int next = attempt + 1;
            final PresenceCodec.Event pendingEvent = event;
            proxy.getScheduler().buildTask(this, () -> publishPresenceWhenVisible(current, player, pendingEvent, next))
                    .delay(1, TimeUnit.SECONDS).schedule();
            return;
        }
        if (visibility == PresenceCodec.Visibility.UNKNOWN) {
            logger.warn("Presence visibility remained UNKNOWN after bounded retry: player={}, kind={}, from={}, to={}",
                    player.getUniqueId(), event.kind(), event.from(), event.to());
        }
        if (!player.isActive() && event.kind() != PresenceCodec.Kind.QUIT) return;
        if (event.kind() == PresenceCodec.Kind.QUIT) {
            PresenceCodec.Event quit = presenceHistory.disconnected(event.player(), event.name(), Instant.now());
            if (quit != null) current.publishPresence(quit);
            return;
        }
        String actual = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        if (actual == null) return;
        var events = event.kind() == PresenceCodec.Kind.SNAPSHOT
                ? presenceHistory.visibilityChanged(event.player(), event.name(), actual, visibility, Instant.now())
                : presenceHistory.connected(event.player(), event.name(), actual, visibility, Instant.now());
        events.forEach(current::publishPresence);
    }

    private void tick() {
        VelocityNetworkAuthority current = authority;
        if (current != null) current.tick();
        if (current != null && svsyncVisibility == null) proxy.getAllPlayers().forEach(player -> player.getCurrentServer().ifPresent(server -> {
            PresenceCodec.Visibility visibility = PresenceVisibilityPolicy
                    .resolve(presenceVisibilityProvider, player, 3).visibility();
            presenceHistory.visibilityChanged(player.getUniqueId(), player.getUsername(),
                    server.getServerInfo().getName(), visibility, Instant.now()).forEach(current::publishPresence);
        }));
        Set<UUID> active = proxy.getAllPlayers().stream().filter(com.velocitypowered.api.proxy.Player::isActive)
                .map(com.velocitypowered.api.proxy.Player::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        presenceHistory.discardStale(Instant.now(), active);
    }

    private void queueVisibilityChange(SVSyncVisibilityIntegration.Change change) {
        synchronized (visibilityChanges) {
            if (!visibilityChangeValidator.reserve(change.sequence())) {
                logger.warn("Discarded out-of-order SVSync visibility event: player={}, sequence={}",
                        change.player(), change.sequence());
                return;
            }
            long generation = connectionGenerations.getOrDefault(change.player(), 0L);
            visibilityChanges.addLast(new QueuedVisibility(change, generation));
            if (visibilityDrainScheduled) return;
            visibilityDrainScheduled = true;
        }
        proxy.getScheduler().buildTask(this, this::drainVisibilityChanges).schedule();
    }

    private void drainVisibilityChanges() {
        synchronized (visibilityApplyLock) {
            while (true) {
                QueuedVisibility queued;
                synchronized (visibilityChanges) {
                    queued = visibilityChanges.pollFirst();
                    if (queued == null) {
                        visibilityDrainScheduled = false;
                        return;
                    }
                }
                applyVisibilityChange(queued);
            }
        }
    }

    private void applyVisibilityChange(QueuedVisibility queued) {
        SVSyncVisibilityIntegration.Change change = queued.change();
        VelocityNetworkAuthority current = authority;
        if (current == null) return;
        var player = proxy.getPlayer(change.player()).orElse(null);
        if (player == null) return;
        long generation;
        synchronized (visibilityChanges) {
            generation = connectionGenerations.getOrDefault(change.player(), 0L);
        }
        String currentServer = player.getCurrentServer().map(server -> server.getServerInfo().getName()).orElse(null);
        if (!visibilityChangeValidator.validAtApply(change, queued.connectionGeneration(), generation,
                player.isActive(), currentServer)) {
            logger.debug("Discarded stale SVSync visibility event: player={}, sequence={}, eventServer={}, currentServer={}",
                    change.player(), change.sequence(), change.server(), currentServer);
            return;
        }
        presenceHistory.visibilityChanged(change.player(), player.getUsername(), currentServer, change.current(),
                change.explicitReappear(), Instant.now()).forEach(current::publishPresence);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        VelocityNetworkAuthority current = authority;
        authority = null;
        ScheduledTask task = networkTask;
        networkTask = null;
        if (task != null) task.cancel();
        if (current != null) current.close();
        SVSyncVisibilityIntegration visibility = svsyncVisibility;
        svsyncVisibility = null;
        if (visibility != null) visibility.close();
        synchronized (visibilityChanges) {
            visibilityChanges.clear();
            connectionGenerations.clear();
            visibilityDrainScheduled = false;
        }
        proxy.getCommandManager().unregister("lunachat");
        proxy.getChannelRegistrar().unregister(CHANNEL);
    }

    @Override public Optional<LunaChatIntegrationApi> current() {
        VelocityNetworkAuthority current = authority;
        return current == null ? Optional.empty() : Optional.of(current.runtime());
    }

    private Properties loadConfig() throws IOException {
        boolean firstStart = !Files.exists(dataDirectory.resolve("network.properties"));
        Properties config = new VelocityNetworkConfig(dataDirectory).load();
        if (firstStart) logger.warn("Set sharePass in network.properties and use the same integration.sharePass on every Paper server.");
        return config;
    }

    private static byte[] resolveSecret(Properties config) {
        String sharePass = config.getProperty("sharePass", "").trim();
        if (!sharePass.isBlank()) return SharedPassphrase.derive(sharePass);
        byte[] legacy = Base64.getDecoder().decode(config.getProperty("sharedSecret", ""));
        if (legacy.length < 32) {
            throw new IllegalArgumentException("sharePass is required; legacy sharedSecret must decode to at least 32 bytes");
        }
        return legacy;
    }

}
