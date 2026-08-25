package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.core.InMemoryChannelDirectory;
import com.github.ucchyocean.lunachat.core.IntegrationRuntime;
import com.github.ucchyocean.lunachat.core.network.AcceptedMessageCodec;
import com.github.ucchyocean.lunachat.core.network.ChannelStateCodec;
import com.github.ucchyocean.lunachat.core.network.FrameAuthenticationException;
import com.github.ucchyocean.lunachat.core.network.FrameType;
import com.github.ucchyocean.lunachat.core.network.ReliableOutbox;
import com.github.ucchyocean.lunachat.core.network.ReplayWindow;
import com.github.ucchyocean.lunachat.core.network.ReplayFrameException;
import com.github.ucchyocean.lunachat.core.network.SecureFrame;
import com.github.ucchyocean.lunachat.core.network.SecureFrameCodec;
import com.github.ucchyocean.lunachat.api.RuntimeRole;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity network authority and authenticated routing state machine. */
final class VelocityNetworkAuthority implements AutoCloseable {
    private record Session(UUID id, long epoch) {}
    private record PendingExternal(AcceptedMessage message, CompletableFuture<AcceptedMessage> completion) {}
    private final ProxyServer proxy;
    private final Logger logger;
    private final ChannelIdentifier channel;
    private final AuthorityChannelStore store;
    private final InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
    private final SecureFrameCodec secure;
    private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
    private final ChannelStateCodec channelStates = new ChannelStateCodec();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ReliableOutbox> outboxes = new ConcurrentHashMap<>();
    /**
     * External publishes remain pending until a Paper edge acknowledges that
     * LunaChat accepted and rendered the canonical message.  Putting a frame
     * in an outbox is transport admission only; it is not API admission.
     */
    private final Map<UUID, PendingExternal> pendingExternal = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> inboundReceipts = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int pendingCapacity;
    private final int receiptCapacity;
    private final IntegrationRuntime runtime;

    VelocityNetworkAuthority(ProxyServer proxy, Logger logger, ChannelIdentifier channel,
            AuthorityChannelStore store, byte[] secret, int pendingCapacity, int receiptCapacity) {
        this.proxy = proxy;
        this.logger = logger;
        this.channel = channel;
        this.store = store;
        this.pendingCapacity = pendingCapacity;
        this.receiptCapacity = receiptCapacity;
        directory.replace(store.snapshot());
        secure = new SecureFrameCodec(1, secret, new ReplayWindow(receiptCapacity), Clock.systemUTC());
        proxy.getAllServers().forEach(server -> outboxes.put(server.getServerInfo().getName(),
                new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1))));
        runtime = IntegrationRuntime.authority(RuntimeRole.NETWORK_AUTHORITY, directory, this::commitExternal,
                Clock.systemUTC(), "velocity", pendingCapacity, receiptCapacity);
    }

    IntegrationRuntime runtime() { return runtime; }

    void handle(PluginMessageEvent event) {
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        String sourceNode = source.getServerInfo().getName();
        try {
            SecureFrame frame = secure.decode(event.getData());
            if (frame.type() == FrameType.HELLO) {
                String claimedNode = new String(frame.payload(), StandardCharsets.UTF_8);
                if (!sourceNode.equals(claimedNode)) throw new FrameAuthenticationException("backend identity mismatch");
                sessions.put(sourceNode, new Session(frame.sessionId(), frame.epoch()));
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.READY, null,
                        sourceNode.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(10));
                return;
            }
            requireSession(sourceNode, frame);
            if (frame.type() == FrameType.STATE) {
                store.applyProposal(channelStates.decode(frame.payload()));
                directory.replace(store.snapshot());
            } else if (frame.type() == FrameType.MESSAGE && frame.logicalMessageId() != null) {
                AcceptedMessage accepted = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(accepted.messageId()) || !sourceNode.equals(accepted.sourceServerId())) {
                    throw new FrameAuthenticationException("message identity mismatch");
                }
                boolean first = acceptLogical(accepted.messageId(), accepted.expiresAt());
                if (first) {
                    runtime.authorityGateway().accept(accepted);
                    enqueueForOtherBackends(sourceNode, accepted);
                }
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.ACK, accepted.messageId(),
                        new byte[0], Instant.now().plusSeconds(30));
            } else if (frame.type() == FrameType.ACK && frame.logicalMessageId() != null) {
                ReliableOutbox outbox = outboxes.get(sourceNode);
                if (outbox != null) outbox.acknowledge(frame.logicalMessageId());
                if (frame.payload().length > 0) {
                    AcceptedMessage acknowledged = messages.decode(frame.payload());
                    if (!frame.logicalMessageId().equals(acknowledged.messageId())) {
                        throw new FrameAuthenticationException("acknowledgement identity mismatch");
                    }
                    acknowledgeExternal(acknowledged);
                }
            }
        } catch (ReplayFrameException replay) {
            logger.debug("Discarded replayed LunaChat frame from {}", sourceNode);
        } catch (Exception rejected) {
            sessions.remove(sourceNode);
            logger.warn("Rejected LunaChat frame from {}: {}", sourceNode, rejected.getMessage());
        }
    }

    private CompletableFuture<AcceptedMessage> commitExternal(AcceptedMessage message) {
        CompletableFuture<AcceptedMessage> completion = new CompletableFuture<>();
        PendingExternal pending = new PendingExternal(message, completion);
        pendingExternal.put(message.messageId(), pending);
        int committed = 0;
        Instant now = Instant.now();
        for (ReliableOutbox outbox : outboxes.values()) {
            if (outbox.offer(message.messageId(), messages.encode(message), message.expiresAt(), now)) committed++;
        }
        if (committed == 0) {
            pendingExternal.remove(message.messageId(), pending);
            completion.complete(null);
        } else {
            if (committed < outboxes.size()) {
                logger.warn("External message {} admitted with partial backend coverage ({}/{})",
                        message.messageId(), committed, outboxes.size());
            }
        }
        return completion;
    }

    /** Completes the API admission only after a Paper edge's accepted ACK. */
    private void acknowledgeExternal(AcceptedMessage acknowledged) throws FrameAuthenticationException {
        PendingExternal pending = pendingExternal.get(acknowledged.messageId());
        if (pending == null) return;
        AcceptedMessage proposed = pending.message();
        if (!proposed.channelId().equals(acknowledged.channelId())
                || !proposed.origin().equals(acknowledged.origin())
                || !proposed.author().equals(acknowledged.author())
                || !proposed.sourceServerId().equals(acknowledged.sourceServerId())
                || !proposed.createdAt().equals(acknowledged.createdAt())
                || !proposed.expiresAt().equals(acknowledged.expiresAt())) {
            throw new FrameAuthenticationException("external acknowledgement changed message identity");
        }
        if (pendingExternal.remove(acknowledged.messageId(), pending)) {
            // The authority's canonical content remains authoritative.  The
            // Paper ACK is the acceptance signal, not a content replacement.
            pending.completion().complete(proposed);
        }
    }

    private void enqueueForOtherBackends(String sourceNode, AcceptedMessage message) {
        byte[] payload = messages.encode(message);
        Instant now = Instant.now();
        outboxes.forEach((node, outbox) -> {
            if (!node.equals(sourceNode) && !outbox.offer(message.messageId(), payload, message.expiresAt(), now)) {
                logger.warn("Network outbox for {} rejected logical message {}", node, message.messageId());
            }
        });
    }

    void tick() {
        Instant now = Instant.now();
        proxy.getAllServers().forEach(server -> outboxes.computeIfAbsent(server.getServerInfo().getName(),
                ignored -> new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1))));
        pendingExternal.forEach((messageId, pending) -> {
            boolean queued = outboxes.values().stream().anyMatch(outbox -> outbox.contains(messageId, now));
            if ((!pending.message().expiresAt().isAfter(now) || !queued)
                    && pendingExternal.remove(messageId, pending)) {
                pending.completion().complete(null);
            }
        });
        synchronized (this) {
            inboundReceipts.values().removeIf(expiry -> !expiry.isAfter(now));
        }
        outboxes.forEach((node, outbox) -> {
            Session session = sessions.get(node);
            if (session == null) return;
            for (ReliableOutbox.Attempt attempt : outbox.pollDue(now, 32)) {
                sendAttempt(node, session, attempt);
            }
        });
    }

    private synchronized boolean acceptLogical(UUID logicalId, Instant expiry) {
        Instant now = Instant.now();
        inboundReceipts.values().removeIf(value -> !value.isAfter(now));
        if (inboundReceipts.containsKey(logicalId)) return false;
        if (inboundReceipts.size() >= receiptCapacity) return false;
        inboundReceipts.put(logicalId, expiry.plus(Duration.ofMinutes(5)));
        return true;
    }

    private void requireSession(String node, SecureFrame frame) throws FrameAuthenticationException {
        Session expected = sessions.get(node);
        if (expected == null || !expected.id().equals(frame.sessionId()) || expected.epoch() != frame.epoch()) {
            throw new FrameAuthenticationException("unknown or stale session");
        }
    }

    private void sendAttempt(String node, Session session, ReliableOutbox.Attempt attempt) {
        SecureFrame frame = new SecureFrame(1, session.id(), session.epoch(), attempt.sequence(), attempt.frameId(),
                attempt.logicalMessageId(), FrameType.MESSAGE, Instant.now(), attempt.expiresAt(), attempt.payload());
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    private void sendNow(String node, UUID sessionId, long epoch, FrameType type, UUID logicalId,
            byte[] payload, Instant expiresAt) {
        SecureFrame frame = new SecureFrame(1, sessionId, epoch, sequence.incrementAndGet(), UUID.randomUUID(),
                logicalId, type, Instant.now(), expiresAt, payload);
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    @Override public void close() {
        sessions.clear();
        pendingExternal.values().forEach(pending -> pending.completion().complete(null));
        pendingExternal.clear();
        runtime.close();
    }
}
