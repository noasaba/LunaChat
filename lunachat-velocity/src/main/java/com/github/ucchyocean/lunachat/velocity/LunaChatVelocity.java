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
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "lunachat", name = "LunaChat", version = "4.0.21-SNAPSHOT",
        description = "LunaChat network authority for Velocity 4.1")
public final class LunaChatVelocity implements LunaChatApiProvider {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("lunachat", "network_v7");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile VelocityNetworkAuthority authority;
    private volatile ScheduledTask networkTask;
    private volatile PresenceVisibilityProvider presenceVisibilityProvider;
    private final Map<UUID, String> lastPresenceServer = new HashMap<>();

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
            authority.setPresenceVisibilityProvider(presenceVisibilityProvider);
            proxy.getCommandManager().register("lunachat", new VelocityAuthorityCommand(authority), "lcauthority");
            networkTask = proxy.getScheduler().buildTask(this, authority::tick).repeat(Duration.ofSeconds(1)).schedule();
            logger.info("LunaChat network authority ready (API {}, wire 7; v6 peers are rejected)", authority.runtime().apiVersion());
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
        var previous = event.getPreviousServer();
        PresenceCodec.Event presence = new PresenceCodec.Event(UUID.randomUUID(), event.getPlayer().getUniqueId(),
                event.getPlayer().getUsername(), previous.isEmpty() ? PresenceCodec.Kind.JOIN : PresenceCodec.Kind.MOVE,
                previous.map(s -> s.getServerInfo().getName()).orElse(null), event.getServer().getServerInfo().getName(), PresenceCodec.Visibility.UNKNOWN);
        publishPresenceWhenVisible(current, event.getPlayer(), presence, 0);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        VelocityNetworkAuthority current = authority; if (current == null) return;
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
        synchronized (lastPresenceServer) {
            if (!player.isActive() && event.kind() != PresenceCodec.Kind.QUIT) return;
            if (event.kind() == PresenceCodec.Kind.QUIT) {
                lastPresenceServer.remove(event.player());
            } else {
                String actual = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(event.to());
                String previous = lastPresenceServer.get(event.player());
                if (actual == null || actual.equals(previous)) return;
                event = new PresenceCodec.Event(event.eventId(), event.player(), event.name(),
                        previous == null ? PresenceCodec.Kind.JOIN : PresenceCodec.Kind.MOVE,
                        previous, actual, visibility);
                lastPresenceServer.put(event.player(), actual);
            }
        }
        current.publishPresence(event);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        VelocityNetworkAuthority current = authority;
        authority = null;
        ScheduledTask task = networkTask;
        networkTask = null;
        if (task != null) task.cancel();
        if (current != null) current.close();
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
