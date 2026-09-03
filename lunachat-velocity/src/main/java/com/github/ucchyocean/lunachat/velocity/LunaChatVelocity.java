package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.LunaChatApiProvider;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.core.network.SharedPassphrase;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

@Plugin(id = "lunachat", name = "LunaChat", version = "4.0.7-SNAPSHOT",
        description = "LunaChat network authority for Velocity 4.1")
public final class LunaChatVelocity implements LunaChatApiProvider {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("lunachat", "network_v2");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile VelocityNetworkAuthority authority;
    private volatile ScheduledTask networkTask;

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
            int pending = bounded(config, "maxPending", 256);
            int receipts = bounded(config, "dedupCapacity", 4096);
            proxy.getChannelRegistrar().register(CHANNEL);
            AuthorityChannelStore store = new AuthorityChannelStore(dataDirectory);
            authority = new VelocityNetworkAuthority(proxy, logger, CHANNEL, store, secret, pending, receipts);
            networkTask = proxy.getScheduler().buildTask(this, authority::tick).repeat(Duration.ofSeconds(1)).schedule();
            logger.info("LunaChat network authority ready (API {}, wire 2)", authority.runtime().apiVersion());
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
    public void onShutdown(ProxyShutdownEvent event) {
        VelocityNetworkAuthority current = authority;
        authority = null;
        ScheduledTask task = networkTask;
        networkTask = null;
        if (task != null) task.cancel();
        if (current != null) current.close();
        proxy.getChannelRegistrar().unregister(CHANNEL);
    }

    @Override public Optional<LunaChatIntegrationApi> current() {
        VelocityNetworkAuthority current = authority;
        return current == null ? Optional.empty() : Optional.of(current.runtime());
    }

    private Properties loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("network.properties");
        Properties config = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) { config.load(input); }
        } else {
            config.setProperty("schema", "1");
            config.setProperty("sharePass", "");
            config.setProperty("maxPending", "256");
            config.setProperty("dedupCapacity", "4096");
            try (OutputStream output = Files.newOutputStream(file)) { config.store(output, "LunaChat Velocity network configuration"); }
            logger.warn("Set sharePass in network.properties and use the same integration.sharePass on every Paper server.");
        }
        int schema = Integer.parseInt(config.getProperty("schema", "0"));
        if (schema != 1) throw new IOException(schema > 1 ? "future network config schema" : "unsupported network config schema");
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

    private static int bounded(Properties config, String key, int fallback) {
        int value = Integer.parseInt(config.getProperty(key, Integer.toString(fallback)));
        if (value < 1 || value > 1_000_000) throw new IllegalArgumentException(key + " is outside bounds");
        return value;
    }
}
