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
import com.github.ucchyocean.lunachat.core.network.SharedPassphrase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;

/** Authenticated, bounded Paper edge. Local chat never depends on this transport. */
final class PaperNetworkEdge implements PluginMessageListener, AutoCloseable {
    static final String CHANNEL = "lunachat:network_v2";
    private static final int PROTOCOL = 2;
    private final LunaChatBukkit plugin;
    private final PaperIntegrationService integration;
    private volatile String nodeId = "";
    private final SecureFrameCodec secure;
    private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
    private final ChannelStateCodec channelStates = new ChannelStateCodec();
    private final ReliableOutbox outbox;
    private final int dedupCapacity;
    private record InboundPending(CompletableFuture<AcceptedMessage> completion, Instant expiresAt) {}
    private final Map<UUID, AcceptedMessage> inboundReceipts = new LinkedHashMap<>();
    private final Map<UUID, InboundPending> inboundPending = new LinkedHashMap<>();
    private final UUID sessionId = UUID.randomUUID();
    private final long epoch = System.currentTimeMillis();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean catalogSynchronized = new AtomicBoolean();
    private long lastHelloMillis;
    private int taskId;

    static PaperNetworkEdge create(LunaChatBukkit plugin, PaperIntegrationService integration, LunaChatConfig config) {
        if (!config.getIntegrationSharePass().isBlank()) {
            return new PaperNetworkEdge(plugin, integration, config,
                    SharedPassphrase.derive(config.getIntegrationSharePass()));
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(config.getIntegrationSharedSecret());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("integration.sharedSecret must be valid Base64", invalid);
        }
        if (secret.length < 32) {
            throw new IllegalArgumentException("network_edge requires integration.sharePass; legacy sharedSecret must decode to at least 32 bytes");
        }
        return new PaperNetworkEdge(plugin, integration, config, secret);
    }

    private PaperNetworkEdge(LunaChatBukkit plugin, PaperIntegrationService integration, LunaChatConfig config, byte[] secret) {
        this.plugin = plugin;
        this.integration = integration;
        this.secure = new SecureFrameCodec(PROTOCOL, secret,
                new ReplayWindow(config.getIntegrationDedupCapacity()), Clock.systemUTC());
        this.dedupCapacity = config.getIntegrationDedupCapacity();
        this.outbox = new ReliableOutbox(config.getIntegrationMaxPending(), 8, Duration.ofSeconds(1));
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 20L);
    }

    void offer(AcceptedMessage message) {
        AcceptedMessage canonical = messages.canonicalize(message);
        if (!outbox.offer(canonical.messageId(), messages.encode(canonical), canonical.expiresAt(), Instant.now())) {
            plugin.getLogger().warning("LunaChat network outbox full; local message remains local: " + message.messageId());
        }
    }

    private void tick() {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) return;
        long nowMillis = System.currentTimeMillis();
        if (!isReady() || nowMillis - lastHelloMillis >= 10_000L) {
            send(carrier, FrameType.HELLO, null, new byte[0], Instant.now().plusSeconds(10));
            lastHelloMillis = nowMillis;
            return;
        }
        if (!isReady()) return;
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
                String assignedNode = new String(frame.payload(), StandardCharsets.UTF_8).trim();
                if (assignedNode.isEmpty() || assignedNode.length() > 128) {
                    throw new FrameAuthenticationException("invalid assigned node identity");
                }
                nodeId = assignedNode;
                ready.set(true);
                catalogSynchronized.set(false);
                integration.networkConnected();
            } else if (frame.type() == FrameType.STATE && ready.get()) {
                java.util.List<com.github.ucchyocean.lunachat.api.ChannelDescriptor> catalog = channelStates.decode(frame.payload());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        integration.applyAuthorityCatalog(catalog);
                        catalogSynchronized.set(true);
                        send(player, FrameType.STATE, null, channelStates.encode(catalog), Instant.now().plusSeconds(30));
                    } catch (RuntimeException rejectedCatalog) {
                        catalogSynchronized.set(false);
                        ready.set(false);
                        integration.networkUnavailable("CHANNEL_CATALOG_REJECTED");
                        plugin.getLogger().warning("Rejected LunaChat authority catalog: " + rejectedCatalog.getMessage());
                    }
                });
            } else if (frame.type() == FrameType.ACK && frame.logicalMessageId() != null) {
                outbox.acknowledge(frame.logicalMessageId());
            } else if (frame.type() == FrameType.MESSAGE && frame.logicalMessageId() != null && isReady()) {
                AcceptedMessage proposed = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(proposed.messageId())) {
                    throw new FrameAuthenticationException("logical identity mismatch");
                }
                receiveMessage(player, proposed);
            }
        } catch (ReplayFrameException replay) {
            plugin.getLogger().fine("Discarded replayed LunaChat network frame");
        } catch (Exception rejected) {
            ready.set(false);
            catalogSynchronized.set(false);
            integration.networkUnavailable("AUTHORITY_FRAME_REJECTED");
            plugin.getLogger().warning("Rejected LunaChat network frame: " + rejected.getMessage());
        }
    }

    private void receiveMessage(Player carrier, AcceptedMessage proposed) {
        Instant now = Instant.now();
        AcceptedMessage already;
        InboundPending pending;
        boolean start = false;
        synchronized (this) {
            purgeInbound(now);
            already = inboundReceipts.get(proposed.messageId());
            if (already == null) pending = inboundPending.get(proposed.messageId());
            else pending = null;
            if (already == null && pending == null) {
                if (inboundReceipts.size() + inboundPending.size() >= dedupCapacity) return;
                pending = new InboundPending(new CompletableFuture<>(), proposed.expiresAt().plus(Duration.ofMinutes(5)));
                inboundPending.put(proposed.messageId(), pending);
                start = true;
            }
        }
        if (already != null) {
            sendAck(carrier, already);
            return;
        }
        if (start) {
            InboundPending current = pending;
            integration.renderAccepted(proposed).whenComplete((accepted, error) -> {
                synchronized (this) {
                    inboundPending.remove(proposed.messageId());
                    if (error == null && accepted != null && accepted.expiresAt().isAfter(Instant.now())) {
                        purgeInbound(Instant.now());
                        if (inboundReceipts.size() < dedupCapacity) inboundReceipts.put(accepted.messageId(), accepted);
                    }
                }
                if (error == null && accepted != null && accepted.expiresAt().isAfter(Instant.now())) {
                    current.completion().complete(accepted);
                } else {
                    current.completion().complete(null);
                }
            });
        }
        pending.completion().whenComplete((accepted, error) -> {
            if (error == null && accepted != null && accepted.expiresAt().isAfter(Instant.now())) {
                Bukkit.getScheduler().runTask(plugin, () -> sendAck(carrier, accepted));
            }
        });
    }

    private void sendAck(Player carrier, AcceptedMessage accepted) {
        if (!ready.get()) return;
        send(carrier, FrameType.ACK, accepted.messageId(), messages.encode(accepted),
                Instant.now().plusSeconds(30));
    }

    boolean isReady() { return ready.get() && catalogSynchronized.get() && !nodeId.isBlank(); }

    String nodeId() {
        if (!isReady()) throw new IllegalStateException("network node identity is not assigned");
        return nodeId;
    }

    private synchronized void purgeInbound(Instant now) {
        inboundReceipts.values().removeIf(message -> !message.expiresAt().plus(Duration.ofMinutes(5)).isAfter(now));
        inboundPending.values().removeIf(pending -> !pending.expiresAt().isAfter(now));
    }

    @Override public void close() {
        ready.set(false);
        catalogSynchronized.set(false);
        nodeId = "";
        if (taskId != 0) Bukkit.getScheduler().cancelTask(taskId);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }
}
