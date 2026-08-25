package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lc3.LunaChatBukkit;
import com.github.ucchyocean.lc3.LunaChatConfig;
import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.core.network.AcceptedMessageCodec;
import com.github.ucchyocean.lunachat.core.network.FrameAuthenticationException;
import com.github.ucchyocean.lunachat.core.network.FrameType;
import com.github.ucchyocean.lunachat.core.network.ChannelStateCodec;
import com.github.ucchyocean.lunachat.core.network.ReliableOutbox;
import com.github.ucchyocean.lunachat.core.network.ReplayWindow;
import com.github.ucchyocean.lunachat.core.network.ReplayFrameException;
import com.github.ucchyocean.lunachat.core.network.SecureFrame;
import com.github.ucchyocean.lunachat.core.network.SecureFrameCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticated, bounded Paper edge. Local chat never depends on this transport. */
final class PaperNetworkEdge implements PluginMessageListener, AutoCloseable {
    static final String CHANNEL = "lunachat:network_v1";
    private static final int PROTOCOL = 1;
    private final LunaChatBukkit plugin;
    private final PaperIntegrationService integration;
    private final String nodeId;
    private final SecureFrameCodec secure;
    private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
    private final ChannelStateCodec channelStates = new ChannelStateCodec();
    private final ReliableOutbox outbox;
    private final UUID sessionId = UUID.randomUUID();
    private final long epoch = System.currentTimeMillis();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean ready = new AtomicBoolean();
    private int taskId;

    static PaperNetworkEdge create(LunaChatBukkit plugin, PaperIntegrationService integration, LunaChatConfig config) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(config.getIntegrationSharedSecret());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("integration.sharedSecret must be valid Base64", invalid);
        }
        if (secret.length < 32) throw new IllegalArgumentException("network_edge requires a shared secret of at least 32 bytes");
        return new PaperNetworkEdge(plugin, integration, config, secret);
    }

    private PaperNetworkEdge(LunaChatBukkit plugin, PaperIntegrationService integration, LunaChatConfig config, byte[] secret) {
        this.plugin = plugin;
        this.integration = integration;
        this.nodeId = config.getIntegrationServerId();
        this.secure = new SecureFrameCodec(PROTOCOL, secret,
                new ReplayWindow(config.getIntegrationDedupCapacity()), Clock.systemUTC());
        this.outbox = new ReliableOutbox(config.getIntegrationMaxPending(), 8, Duration.ofSeconds(1));
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 20L);
    }

    void offer(AcceptedMessage message) {
        if (!outbox.offer(message.messageId(), messages.encode(message), message.expiresAt(), Instant.now())) {
            plugin.getLogger().warning("LunaChat network outbox full; local message remains local: " + message.messageId());
        }
    }

    private void tick() {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) return;
        if (!ready.get()) {
            send(carrier, FrameType.HELLO, null, nodeId.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(10));
            return;
        }
        for (ReliableOutbox.Attempt attempt : outbox.pollDue(Instant.now(), 32)) {
            SecureFrame frame = new SecureFrame(PROTOCOL, sessionId, epoch, attempt.sequence(), attempt.frameId(),
                    attempt.logicalMessageId(), FrameType.MESSAGE, Instant.now(), attempt.expiresAt(), attempt.payload());
            carrier.sendPluginMessage(plugin, CHANNEL, secure.encode(frame));
        }
    }

    private void send(Player carrier, FrameType type, UUID logicalId, byte[] payload, Instant expiresAt) {
        SecureFrame frame = new SecureFrame(PROTOCOL, sessionId, epoch, sequence.incrementAndGet(), UUID.randomUUID(),
                logicalId, type, Instant.now(), expiresAt, payload);
        carrier.sendPluginMessage(plugin, CHANNEL, secure.encode(frame));
    }

    @Override public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] data) {
        if (!CHANNEL.equals(channel)) return;
        try {
            SecureFrame frame = secure.decode(data);
            if (!sessionId.equals(frame.sessionId()) || frame.epoch() != epoch) {
                throw new FrameAuthenticationException("session mismatch");
            }
            if (frame.type() == FrameType.READY) {
                if (!nodeId.equals(new String(frame.payload(), StandardCharsets.UTF_8))) {
                    throw new FrameAuthenticationException("node mismatch");
                }
                ready.set(true);
                integration.networkReady();
                send(player, FrameType.STATE, null, channelStates.encode(integration.channelSnapshot()), Instant.now().plusSeconds(30));
            } else if (frame.type() == FrameType.ACK && frame.logicalMessageId() != null) {
                outbox.acknowledge(frame.logicalMessageId());
            } else if (frame.type() == FrameType.MESSAGE && frame.logicalMessageId() != null && ready.get()) {
                AcceptedMessage proposed = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(proposed.messageId())) {
                    throw new FrameAuthenticationException("logical identity mismatch");
                }
                integration.renderAccepted(proposed).whenComplete((accepted, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error == null && accepted != null && accepted.expiresAt().isAfter(Instant.now())) {
                                Player replyCarrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                                if (replyCarrier != null) send(replyCarrier, FrameType.ACK, accepted.messageId(),
                                        messages.encode(accepted), Instant.now().plusSeconds(30));
                            }
                        }));
            }
        } catch (ReplayFrameException replay) {
            plugin.getLogger().fine("Discarded replayed LunaChat network frame");
        } catch (Exception rejected) {
            ready.set(false);
            integration.networkUnavailable("AUTHORITY_FRAME_REJECTED");
            plugin.getLogger().warning("Rejected LunaChat network frame: " + rejected.getMessage());
        }
    }

    @Override public void close() {
        ready.set(false);
        if (taskId != 0) Bukkit.getScheduler().cancelTask(taskId);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }
}
