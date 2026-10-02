package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.core.InMemoryChannelDirectory;
import com.github.ucchyocean.lunachat.core.IntegrationRuntime;
import com.github.ucchyocean.lunachat.core.network.AcceptedMessageCodec;
import com.github.ucchyocean.lunachat.core.network.ChannelStateCodec;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec;
import com.github.ucchyocean.lunachat.core.network.ChannelCreateCodec;
import com.github.ucchyocean.lunachat.core.network.FrameAuthenticationException;
import com.github.ucchyocean.lunachat.core.network.FrameType;
import com.github.ucchyocean.lunachat.core.network.ReliableOutbox;
import com.github.ucchyocean.lunachat.core.network.ReplayWindow;
import com.github.ucchyocean.lunachat.core.network.ReplayFrameException;
import com.github.ucchyocean.lunachat.core.network.SecureFrame;
import com.github.ucchyocean.lunachat.core.network.SecureFrameCodec;
import com.github.ucchyocean.lunachat.core.network.PrivateMessageCodec;
import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.github.ucchyocean.lunachat.core.network.NetworkProtocol;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity network authority and authenticated routing state machine. */
final class VelocityNetworkAuthority implements AutoCloseable {
    private record Session(UUID id, long epoch, long revision) {}
    private record PendingExternal(AcceptedMessage message, CompletableFuture<AcceptedMessage> completion) {}
    private enum LogicalAdmission { NEW, PENDING, DUPLICATE, FULL }
    private final ProxyServer proxy;
    private final Logger logger;
    private final ChannelIdentifier channel;
    private final int protocolVersion;
    private final AuthorityChannelStore store;
    private final AuthorityMembershipStore memberships;
    private final InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
    private final SecureFrameCodec secure;
    private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
    private final AuthoritySnapshotCodec channelStates = new AuthoritySnapshotCodec();
    private final ChannelCreateCodec channelCreates = new ChannelCreateCodec();
    private final PrivateMessageCodec privateMessages = new PrivateMessageCodec();
    private final PresenceCodec presences = new PresenceCodec();
    private volatile PresenceVisibilityProvider presenceVisibilityProvider;
    private record PendingPrivate(String sourceNode, String targetNode, UUID target, Instant expires, int attempts) {}
    private final Map<UUID, PendingPrivate> privatePending = new ConcurrentHashMap<>();
    private final Map<UUID, PrivateMessageCodec.Request> privateRequests = new ConcurrentHashMap<>();
    private final Map<UUID, SecureFrame> privateFrames = new ConcurrentHashMap<>();
    private record CreateKey(String node, UUID session, UUID request) {}
    private record CreateReceipt(ChannelCreateCodec.Request request, String result, Instant expires) {}
    private final Map<CreateKey, CreateReceipt> createReceipts = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ReliableOutbox> outboxes = new ConcurrentHashMap<>();
    private final Map<String, ReliableOutbox> presenceOutboxes = new ConcurrentHashMap<>();
    /**
     * External publishes remain pending until a Paper edge acknowledges that
     * LunaChat accepted and rendered the canonical message.  Putting a frame
     * in an outbox is transport admission only; it is not API admission.
     */
    private final Map<UUID, PendingExternal> pendingExternal = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> inboundReceipts = new LinkedHashMap<>();
    private final Map<UUID, Instant> inboundPending = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int pendingCapacity;
    private final int receiptCapacity;
    private final IntegrationRuntime runtime;

    VelocityNetworkAuthority(ProxyServer proxy, Logger logger, ChannelIdentifier channel,
            AuthorityChannelStore store, byte[] secret, int pendingCapacity, int receiptCapacity) throws IOException {
        this.proxy = proxy;
        this.logger = logger;
        this.channel = channel;
        this.protocolVersion = channel.getId().endsWith("network_v6") ? 6
                : channel.getId().endsWith("network_v7") ? 7 : NetworkProtocol.VERSION;
        this.store = store;
        this.memberships = new AuthorityMembershipStore(store.directory(), store.snapshot(), store.settings());
        this.pendingCapacity = pendingCapacity;
        this.receiptCapacity = receiptCapacity;
        directory.replace(store.snapshot());
        secure = new SecureFrameCodec(protocolVersion, secret, new ReplayWindow(receiptCapacity), Clock.systemUTC());
        proxy.getAllServers().forEach(server -> {
            String node = server.getServerInfo().getName();
            outboxes.put(node, new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1)));
            presenceOutboxes.put(node, new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1)));
        });
        runtime = IntegrationRuntime.authority(RuntimeRole.NETWORK_AUTHORITY, directory, this::commitExternal,
                Clock.systemUTC(), "velocity", pendingCapacity, receiptCapacity);
    }

    IntegrationRuntime runtime() { return runtime; }
    void setPresenceVisibilityProvider(PresenceVisibilityProvider provider) { presenceVisibilityProvider = provider; }
    synchronized java.util.List<com.github.ucchyocean.lunachat.api.ChannelDescriptor> channels() { return store.snapshot(); }
    synchronized AuthoritySnapshotCodec.Settings snapshotSettings() { return store.settings(); }
    @FunctionalInterface private interface StoreMutation { void run() throws IOException; }
    private synchronized void mutateStore(StoreMutation mutation) throws IOException {
        AuthorityChannelStore.State before = store.state();
        try { mutation.run(); refreshAuthority(); }
        catch (IOException failure) {
            try { store.restore(before); } catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
    synchronized void createChannel(String name, boolean external) throws IOException { mutateStore(() -> store.create(name, external)); }
    synchronized void deleteChannel(String name) throws IOException { mutateStore(() -> store.delete(name)); }
    synchronized void setAlias(String name, String alias) throws IOException { mutateStore(() -> store.alias(name, alias)); }
    synchronized void setSettings(String globalChannel, String defaultChannel, java.util.Set<String> force) throws IOException {
        mutateStore(() -> store.settings(globalChannel, defaultChannel, force));
    }
    /** Source-compatible test/migration helper: old default also denoted global. */
    synchronized void setSettings(String defaultChannel, java.util.Set<String> force) throws IOException {
        setSettings(defaultChannel, defaultChannel, force);
    }
    synchronized void setExternal(String name, boolean external) throws IOException { mutateStore(() -> store.external(name, external)); }
    synchronized void setJoinable(String name, boolean joinable) throws IOException {
        memberships.setJoinable(requireChannel(name).id(), joinable); publishAuthority();
    }
    synchronized void setPassword(String name, String password) throws IOException {
        if (password.length() > 64) throw new IOException("password exceeds 64 characters");
        updatePolicy(name, policy -> new AuthoritySnapshotCodec.Policy(policy.channel(), policy.moderators(), policy.banned(),
                policy.muted(), policy.banExpires(), policy.muteExpires(), password, policy.visible(), policy.worldRange()));
    }
    synchronized void setVisible(String name, boolean visible) throws IOException {
        updatePolicy(name, policy -> new AuthoritySnapshotCodec.Policy(policy.channel(), policy.moderators(), policy.banned(),
                policy.muted(), policy.banExpires(), policy.muteExpires(), policy.password(), visible, policy.worldRange()));
    }
    synchronized void setWorld(String name, boolean world) throws IOException {
        updatePolicy(name, policy -> new AuthoritySnapshotCodec.Policy(policy.channel(), policy.moderators(), policy.banned(),
                policy.muted(), policy.banExpires(), policy.muteExpires(), policy.password(), policy.visible(), world));
    }
    synchronized void setPlayerPolicy(String name, String field, UUID player, boolean enabled, Long expiry) throws IOException {
        updatePolicy(name, policy -> {
            Set<UUID> moderators = new HashSet<>(policy.moderators());
            Set<UUID> banned = new HashSet<>(policy.banned());
            Set<UUID> muted = new HashSet<>(policy.muted());
            Map<UUID, Long> banExpires = new HashMap<>(policy.banExpires());
            Map<UUID, Long> muteExpires = new HashMap<>(policy.muteExpires());
            Set<UUID> selected = switch (field) { case "moderator" -> moderators; case "ban" -> banned; case "mute" -> muted; default -> throw new IllegalArgumentException("unknown policy field"); };
            if (enabled) selected.add(player); else selected.remove(player);
            if (field.equals("ban")) { if (enabled && expiry != null) banExpires.put(player, expiry); else banExpires.remove(player); }
            if (field.equals("mute")) { if (enabled && expiry != null) muteExpires.put(player, expiry); else muteExpires.remove(player); }
            return new AuthoritySnapshotCodec.Policy(policy.channel(), moderators, banned, muted, banExpires, muteExpires,
                    policy.password(), policy.visible(), policy.worldRange());
        });
    }
    private void updatePolicy(String name, java.util.function.UnaryOperator<AuthoritySnapshotCodec.Policy> mutation) throws IOException {
        memberships.setPolicy(requireChannel(name).id(), mutation); publishAuthority();
    }
    private com.github.ucchyocean.lunachat.api.ChannelDescriptor requireChannel(String name) throws IOException {
        return store.find(name).orElseThrow(() -> new IOException("channel not found"));
    }
    private void publishAuthority() {
        directory.replace(store.snapshot());
        for (String node : sessions.keySet()) sendState(node);
    }
    synchronized String statusLine() {
        long synchronizedNodes = sessions.keySet().stream().filter(this::isCatalogSynchronized).count();
        return "LunaChat authority READY: channels=" + store.snapshot().size() + ", connectedBackends="
                + sessions.size() + ", synchronizedBackends=" + synchronizedNodes + ", revision=" + memberships.snapshot().revision();
    }
    private void refreshAuthority() throws IOException {
        memberships.refreshCatalog(store.snapshot(), store.settings());
        publishAuthority();
    }

    synchronized void handle(PluginMessageEvent event) {
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        String sourceNode = source.getServerInfo().getName();
        try {
            SecureFrame frame = secure.decode(event.getData());
            if (frame.type() == FrameType.HELLO) {
                Session existing = sessions.get(sourceNode);
                // A periodic heartbeat is not a new handshake. Replaying the
                // READY handshake here would reset Paper's synchronized flag.
                // A STATE response refreshes Paper's authority lease without
                // opening an unauthenticated window for in-flight messages.
                if (existing != null && existing.id().equals(frame.sessionId())
                        && existing.epoch() == frame.epoch() && isCatalogSynchronized(sourceNode)) {
                    sendState(sourceNode);
                    return;
                }
                sessions.put(sourceNode, new Session(frame.sessionId(), frame.epoch(), -1));
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.READY, null,
                        sourceNode.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(10));
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.STATE, null,
                        channelStates.encode(memberships.snapshot()), Instant.now().plusSeconds(30));
                return;
            }
            requireSession(sourceNode, frame);
            if (frame.type() == FrameType.STATE) {
                var acknowledged = channelStates.decode(frame.payload());
                if (acknowledged.revision() < memberships.snapshot().revision()) {
                    sendState(sourceNode);
                    return;
                }
                if (!memberships.snapshot().equals(acknowledged)) {
                    throw new FrameAuthenticationException("channel catalog acknowledgement mismatch");
                }
                Session session = sessions.get(sourceNode);
                sessions.put(sourceNode, new Session(session.id(), session.epoch(), acknowledged.revision()));
                if (protocolVersion >= 7) sendPresenceSnapshot(sourceNode);
            } else if (frame.type() == FrameType.MEMBER_CHANGE && frame.logicalMessageId() != null) {
                // Only authenticated server connections reach this path. Paper
                // invokes it after its existing command permissions/events.
                var change = channelStates.decodeChange(frame.payload());
                String result;
                try { result = memberships.change(change); }
                catch (IOException unavailable) {
                    logger.error("LunaChat membership update could not be persisted; existing state retained", unavailable);
                    result = "UNAVAILABLE";
                }
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.MEMBER_RESULT,
                        frame.logicalMessageId(), result.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(30));
                for (String node : sessions.keySet()) sendState(node);
            } else if (frame.type() == FrameType.CHANNEL_CREATE && frame.logicalMessageId() != null) {
                if (!isCatalogSynchronized(sourceNode)) return;
                var request = channelCreates.decode(frame.payload());
                createReceipts.values().removeIf(receipt -> !receipt.expires().isAfter(Instant.now()));
                var key = new CreateKey(sourceNode, frame.sessionId(), frame.logicalMessageId());
                var receipt = createReceipts.get(key);
                String result;
                if (receipt != null) {
                    if (!receipt.request().equals(request)) throw new FrameAuthenticationException("create request identity mismatch");
                    result = receipt.result();
                } else if (createReceipts.size() >= receiptCapacity) {
                    result = "UNAVAILABLE";
                } else if (store.find(request.name()).isPresent()) {
                    result = "EXISTS";
                } else {
                    try {
                        store.create(request.name(), request.acceptsExternalMessages());
                        refreshAuthority();
                        result = "APPLIED";
                    } catch (IOException unavailable) {
                        logger.error("LunaChat canonical channel could not be persisted; request rejected", unavailable);
                        result = "UNAVAILABLE";
                    }
                }
                if (receipt == null && !result.equals("UNAVAILABLE")) {
                    createReceipts.put(key, new CreateReceipt(request, result, frame.expiresAt().plusSeconds(30)));
                }
                sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.CHANNEL_CREATE_RESULT,
                        frame.logicalMessageId(), result.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(30));
            } else if (frame.type() == FrameType.MESSAGE && frame.logicalMessageId() != null) {
                if (!isCatalogSynchronized(sourceNode)) {
                    // requireSession already authenticated this sender. The
                    // outbox retries without an ACK after catalog sync finishes.
                    logger.debug("Deferred LunaChat message from {} pending catalog synchronization", sourceNode);
                    return;
                }
                AcceptedMessage accepted = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(accepted.messageId()) || !sourceNode.equals(accepted.sourceServerId())) {
                    throw new FrameAuthenticationException("message identity mismatch");
                }
                LogicalAdmission admission = reserveLogical(accepted.messageId(), accepted.expiresAt());
                if (admission == LogicalAdmission.DUPLICATE) {
                    sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.ACK, accepted.messageId(),
                            new byte[0], Instant.now().plusSeconds(30));
                } else if (admission == LogicalAdmission.NEW) {
                    runtime.authorityGateway().acceptAsync(accepted).whenComplete((admitted, failure) -> {
                        if (failure == null && Boolean.TRUE.equals(admitted)
                                && completeLogical(accepted.messageId(), accepted.expiresAt())) {
                            enqueueForOtherBackends(sourceNode, accepted);
                            sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.ACK, accepted.messageId(),
                                    new byte[0], Instant.now().plusSeconds(30));
                        } else {
                            releaseLogical(accepted.messageId());
                        }
                    });
                }
            } else if (frame.type() == FrameType.PRIVATE_REQUEST && frame.logicalMessageId() != null) {
                handlePrivateRequest(source, frame);
            } else if (frame.type() == FrameType.PRIVATE_ACK && frame.logicalMessageId() != null) {
                handlePrivateAck(sourceNode, frame);
            } else if (frame.type() == FrameType.PRESENCE_ACK && frame.logicalMessageId() != null) {
                logger.debug("Received PRESENCE_ACK from {}: eventId={}", sourceNode, frame.logicalMessageId());
                ReliableOutbox presenceOutbox = presenceOutboxes.get(sourceNode);
                if (presenceOutbox != null) presenceOutbox.acknowledge(frame.logicalMessageId());
            } else if (frame.type() == FrameType.ACK && frame.logicalMessageId() != null) {
                ReliableOutbox outbox = outboxes.get(sourceNode);
                byte[] proposedPayload = outbox == null ? null
                        : outbox.payload(frame.logicalMessageId(), Instant.now()).orElse(null);
                if (frame.payload().length == 0) {
                    if (proposedPayload == null) return;
                    AcceptedMessage proposed = messages.decode(proposedPayload);
                    if (proposed.origin().kind() == com.github.ucchyocean.lunachat.api.OriginKind.EXTERNAL
                            || pendingExternal.containsKey(frame.logicalMessageId())) {
                        throw new FrameAuthenticationException("external acknowledgement payload is required");
                    }
                    outbox.acknowledge(frame.logicalMessageId());
                    return;
                }
                AcceptedMessage acknowledged = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(acknowledged.messageId())) {
                    throw new FrameAuthenticationException("acknowledgement identity mismatch");
                }
                if (proposedPayload != null) validateStableIdentity(messages.decode(proposedPayload), acknowledged);
                PendingExternal pending = validateExternalAcknowledgement(acknowledged);
                if (outbox != null) outbox.acknowledge(frame.logicalMessageId());
                completeExternalAcknowledgement(pending, acknowledged);
            }
        } catch (ReplayFrameException replay) {
            logger.debug("Discarded replayed LunaChat frame from {}", sourceNode);
        } catch (Exception rejected) {
            sessions.remove(sourceNode);
            logger.warn("Rejected LunaChat frame from {}: {}", sourceNode, rejected.getMessage());
        }
    }

    private void handlePrivateRequest(ServerConnection source, SecureFrame frame) throws IOException, FrameAuthenticationException {
        PrivateMessageCodec.Request request = privateMessages.decodeRequest(frame.payload());
        logger.debug("PM request received: requestId={}, sourceNode={}", frame.logicalMessageId(), source.getServerInfo().getName());
        var sender = proxy.getPlayer(request.sender()).orElse(null);
        String sourceNode = source.getServerInfo().getName();
        PrivateSenderValidation.Result senderValidation = PrivateSenderValidation.validate(
                sender == null ? null : new PrivateSenderValidation.PlayerState(sender.getUniqueId(),
                        sender.getUsername(), sender.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null), sender.isActive()),
                request.sender(), request.senderName(), sourceNode);
        if (!senderValidation.accepted()) {
            logger.info("PM sender rejected: requestId={}, reason={}", frame.logicalMessageId(), senderValidation.reason());
            sendPrivateResult(sourceNode, frame, senderValidation.resultStatus(), null, "", null);
            return;
        }
        var matches = proxy.getAllPlayers().stream()
                .filter(player -> player.getUsername().equalsIgnoreCase(request.targetName())).toList();
        if (matches.size() != 1) { sendNow(sourceNode, frame.sessionId(), frame.epoch(), FrameType.PRIVATE_RESULT,
                frame.logicalMessageId(), privateMessages.encode(new PrivateMessageCodec.Result("NOT_FOUND", null, "", null)), Instant.now().plusSeconds(10)); return; }
        var target = matches.iterator().next();
        if (target.getUniqueId().equals(request.sender())) { sendPrivateResult(sourceNode, frame, "SELF", null, "", null); return; }
        PresenceCodec.Visibility visibility = visibilityOf(target);
        if (visibility != PresenceCodec.Visibility.PUBLIC) {
            logger.info("PM visibility rejected: requestId={}, state={}", frame.logicalMessageId(), visibility);
            sendPrivateResult(sourceNode, frame, "NOT_FOUND", null, "", null); return;
        }
        var current = target.getCurrentServer();
        if (current.isEmpty()) { sendPrivateResult(sourceNode, frame, "NOT_FOUND", null, "", null); return; }
        String targetNode = current.get().getServerInfo().getName();
        if (!isCatalogSynchronized(targetNode)) { sendPrivateResult(sourceNode, frame, "BACKEND_UNAVAILABLE", null, "", null); return; }
        privatePending.put(frame.logicalMessageId(), new PendingPrivate(sourceNode, targetNode, target.getUniqueId(), frame.expiresAt(), 0));
        privateRequests.put(frame.logicalMessageId(), request); privateFrames.put(frame.logicalMessageId(), frame);
        logger.debug("PM delivery queued: requestId={}, sourceNode={}, targetNode={}", frame.logicalMessageId(),
                sourceNode, targetNode);
        sendPrivateDelivery(frame.logicalMessageId(), frame, request, target.getUniqueId(), target.getUsername(), targetNode);
    }

    private void sendPrivateDelivery(UUID id, SecureFrame requestFrame, PrivateMessageCodec.Request request, UUID target, String targetName, String node) {
        Session session = sessions.get(node); if (session == null) return;
        sendNow(node, session.id(), session.epoch(), FrameType.PRIVATE_DELIVERY, id,
                privateMessages.encode(new PrivateMessageCodec.Delivery(request.sender(), request.senderName(), target, targetName, request.content())), requestFrame.expiresAt());
    }

    private void handlePrivateAck(String targetNode, SecureFrame frame) throws IOException, FrameAuthenticationException {
        PendingPrivate pending = privatePending.get(frame.logicalMessageId());
        if (pending == null) return;
        if (!pending.targetNode().equals(targetNode)) return;
        String status = new String(frame.payload(), StandardCharsets.UTF_8);
        if (!Set.of("DELIVERED", "NOT_FOUND", "RENDER_FAILED").contains(status)) {
            throw new FrameAuthenticationException("invalid private acknowledgement status");
        }
        PrivateMessageCodec.Request request = privateRequests.get(frame.logicalMessageId());
        privatePending.remove(frame.logicalMessageId()); privateRequests.remove(frame.logicalMessageId()); privateFrames.remove(frame.logicalMessageId());
        Session source = sessions.get(pending.sourceNode()); if (source == null) return;
        sendNow(pending.sourceNode(), source.id(), source.epoch(), FrameType.PRIVATE_RESULT, frame.logicalMessageId(),
                privateMessages.encode(new PrivateMessageCodec.Result(status, pending.target(),
                        request == null ? "" : request.targetName(), null)), Instant.now().plusSeconds(10));
        logger.debug("PM delivery result: requestId={}, status={}", frame.logicalMessageId(), status);
    }

    private void sendPrivateResult(String node, SecureFrame frame, String status, UUID target, String name, String content) {
        Session session=sessions.get(node); if(session!=null) sendNow(node,session.id(),session.epoch(),FrameType.PRIVATE_RESULT,frame.logicalMessageId(),privateMessages.encode(new PrivateMessageCodec.Result(status,target,name,content)),Instant.now().plusSeconds(10));
    }

    private synchronized CompletableFuture<AcceptedMessage> commitExternal(AcceptedMessage message) {
        AcceptedMessage canonical = messages.canonicalize(message);
        CompletableFuture<AcceptedMessage> completion = new CompletableFuture<>();
        PendingExternal pending = new PendingExternal(canonical, completion);
        pendingExternal.put(canonical.messageId(), pending);
        int committed = 0;
        Instant now = Instant.now();
        for (Map.Entry<String, ReliableOutbox> entry : outboxes.entrySet()) {
            if (isCatalogSynchronized(entry.getKey())
                    && entry.getValue().offer(canonical.messageId(), messages.encode(canonical), canonical.expiresAt(), now)) committed++;
        }
        if (committed == 0) {
            pendingExternal.remove(canonical.messageId(), pending);
            completion.complete(null);
        } else {
            if (committed < outboxes.size()) {
                logger.warn("External message {} admitted with partial backend coverage ({}/{})",
                        canonical.messageId(), committed, outboxes.size());
            }
        }
        return completion;
    }

    private PendingExternal validateExternalAcknowledgement(AcceptedMessage acknowledged)
            throws FrameAuthenticationException {
        PendingExternal pending = pendingExternal.get(acknowledged.messageId());
        if (pending != null) validateStableIdentity(pending.message(), acknowledged);
        return pending;
    }

    /** Completes API admission with the content finalized by the accepting Paper. */
    private void completeExternalAcknowledgement(PendingExternal pending, AcceptedMessage acknowledged) {
        if (pending == null) return;
        if (pendingExternal.remove(acknowledged.messageId(), pending)) {
            pending.completion().complete(acknowledged);
        }
    }

    private static void validateStableIdentity(AcceptedMessage proposed, AcceptedMessage acknowledged)
            throws FrameAuthenticationException {
        if (!proposed.messageId().equals(acknowledged.messageId())
                || !proposed.channelId().equals(acknowledged.channelId())
                || !proposed.origin().equals(acknowledged.origin())
                || !proposed.author().equals(acknowledged.author())
                || !proposed.sourceServerId().equals(acknowledged.sourceServerId())
                || !proposed.createdAt().equals(acknowledged.createdAt())
                || !proposed.expiresAt().equals(acknowledged.expiresAt())) {
            throw new FrameAuthenticationException("acknowledgement changed message identity");
        }
    }

    private void enqueueForOtherBackends(String sourceNode, AcceptedMessage message) {
        AcceptedMessage canonical = messages.canonicalize(message);
        byte[] payload = messages.encode(canonical);
        Instant now = Instant.now();
        outboxes.forEach((node, outbox) -> {
            if (!node.equals(sourceNode) && isCatalogSynchronized(node)
                    && !outbox.offer(canonical.messageId(), payload, canonical.expiresAt(), now)) {
                logger.warn("Network outbox for {} rejected logical message {}", node, canonical.messageId());
            }
        });
    }

    void tick() {
        Instant now = Instant.now();
        java.util.Set<String> activeNodes = proxy.getAllServers().stream()
                .map(server -> server.getServerInfo().getName()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        activeNodes.forEach(node -> outboxes.computeIfAbsent(node,
                ignored -> new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1))));
        activeNodes.forEach(node -> presenceOutboxes.computeIfAbsent(node,
                ignored -> new ReliableOutbox(pendingCapacity, 8, Duration.ofSeconds(1))));
        outboxes.keySet().stream().filter(node -> !activeNodes.contains(node)).toList().forEach(node -> {
            outboxes.remove(node);
            presenceOutboxes.remove(node);
            sessions.remove(node);
        });
        directory.replace(store.snapshot());
        synchronized (this) {
            pendingExternal.forEach((messageId, pending) -> {
                boolean queued = outboxes.values().stream().anyMatch(outbox -> outbox.contains(messageId, now));
                if ((!pending.message().expiresAt().isAfter(now) || !queued)
                        && pendingExternal.remove(messageId, pending)) {
                    pending.completion().complete(null);
                }
            });
        }
        synchronized (this) {
            inboundReceipts.values().removeIf(expiry -> !expiry.isAfter(now));
            inboundPending.values().removeIf(expiry -> !expiry.isAfter(now));
        }
        processPrivatePending(now);
        outboxes.forEach((node, outbox) -> {
            Session session = sessions.get(node);
            if (session == null) return;
            if (!isCatalogSynchronized(node)) { sendState(node); return; }
            for (ReliableOutbox.Attempt attempt : outbox.pollDue(now, 32)) {
                sendAttempt(node, session, attempt);
            }
        });
        presenceOutboxes.forEach((node, outbox) -> {
            Session session = sessions.get(node);
            if (session == null || !isCatalogSynchronized(node)) return;
            for (ReliableOutbox.Attempt attempt : outbox.pollDue(now, 32)) {
                sendPresenceAttempt(node, session, attempt);
            }
        });
    }

    private void processPrivatePending(Instant now) {
        privatePending.forEach((id, pending) -> {
            if (!pending.expires().isAfter(now)) {
                completePrivateFailure(id, pending, "TIMEOUT");
                return;
            }
            var target = proxy.getPlayer(pending.target()).orElse(null);
            var current = target == null || !target.isActive() ? null : target.getCurrentServer().orElse(null);
            if (current == null) {
                completePrivateFailure(id, pending, "NOT_FOUND");
                return;
            }
            String node = current.getServerInfo().getName();
            if (node.equals(pending.targetNode())) return;
            if (pending.attempts() >= 1 || !isCatalogSynchronized(node)) {
                completePrivateFailure(id, pending, "BACKEND_UNAVAILABLE");
                return;
            }
            PrivateMessageCodec.Request request = privateRequests.get(id);
            SecureFrame requestFrame = privateFrames.get(id);
            PendingPrivate retried = new PendingPrivate(pending.sourceNode(), node, pending.target(),
                    pending.expires(), pending.attempts() + 1);
            if (request == null || requestFrame == null || !privatePending.replace(id, pending, retried)) return;
            sendPrivateDelivery(id, requestFrame, request, pending.target(), target.getUsername(), node);
        });
    }

    private void completePrivateFailure(UUID id, PendingPrivate pending, String status) {
        if (!privatePending.remove(id, pending)) return;
        PrivateMessageCodec.Request request = privateRequests.remove(id);
        SecureFrame requestFrame = privateFrames.remove(id);
        if (requestFrame == null) return;
        sendPrivateResult(pending.sourceNode(), requestFrame, status, pending.target(),
                request == null ? "" : request.targetName(), null);
        logger.debug("PM delivery result: requestId={}, status={}", id, status);
    }

    void publishPresence(PresenceCodec.Event event) {
        // JOIN/QUIT is intentionally Paper-local. Keep the Wire 8 PRESENCE
        // snapshot for tab completion, but never enqueue a network event that
        // could turn a backend switch into a cross-server MOVE notification.
        logger.debug("Ignored network presence publication: kind={}, player={}", event.kind(), event.player());
    }

    /** Wire 8 edges update the player directory without rendering JOIN/QUIT chat. */
    synchronized void refreshPlayerDirectory(com.velocitypowered.api.proxy.Player player, String server) {
        sendPlayerDirectoryUpdate(new PresenceCodec.Event(UUID.randomUUID(), player.getUniqueId(),
                player.getUsername(), PresenceCodec.Kind.SNAPSHOT, null, server, visibilityOf(player)));
    }

    synchronized void removePlayerFromDirectory(UUID player, String name) {
        sendPlayerDirectoryUpdate(new PresenceCodec.Event(UUID.randomUUID(), player, name,
                PresenceCodec.Kind.QUIT, null, null, PresenceCodec.Visibility.PUBLIC));
    }

    private void sendPlayerDirectoryUpdate(PresenceCodec.Event update) {
        if (protocolVersion < 8) return;
        byte[] payload = presences.encode(update);
        // Do not replay stale deltas after reconnect. The periodic full snapshot
        // repairs failed sends, and only synchronized Wire 8 edges ignore rendering.
        sessions.forEach((node, session) -> {
            if (isCatalogSynchronized(node)) {
                sendNow(node, session.id(), session.epoch(), FrameType.PRESENCE, update.eventId(),
                        payload, Instant.now().plusSeconds(10));
            }
        });
    }

    private void sendPresenceSnapshot(String node) {
        Session session = sessions.get(node); if (session == null) return;
        proxy.getAllPlayers().forEach(player -> player.getCurrentServer().ifPresent(server -> {
            PresenceCodec.Visibility visibility = visibilityOf(player);
            PresenceCodec.Event snapshot = new PresenceCodec.Event(UUID.randomUUID(), player.getUniqueId(),
                    player.getUsername(), PresenceCodec.Kind.SNAPSHOT, null, server.getServerInfo().getName(), visibility);
            sendNow(node, session.id(), session.epoch(), FrameType.PRESENCE, snapshot.eventId(),
                    presences.encode(snapshot), Instant.now().plusSeconds(10));
        }));
    }

    private PresenceCodec.Visibility visibilityOf(com.velocitypowered.api.proxy.Player player) {
        PresenceVisibilityProvider provider = presenceVisibilityProvider;
        PresenceVisibilityPolicy.Decision decision = PresenceVisibilityPolicy.resolve(provider, player);
        if (decision.publicFallback()) {
            logger.warn("Presence snapshot visibility lookup failed; using PUBLIC fallback for online player {}",
                    player.getUniqueId());
        }
        return decision.visibility();
    }

    private synchronized LogicalAdmission reserveLogical(UUID logicalId, Instant expiry) {
        Instant now = Instant.now();
        inboundReceipts.values().removeIf(value -> !value.isAfter(now));
        inboundPending.values().removeIf(value -> !value.isAfter(now));
        if (inboundReceipts.containsKey(logicalId)) return LogicalAdmission.DUPLICATE;
        if (inboundPending.containsKey(logicalId)) return LogicalAdmission.PENDING;
        if (inboundReceipts.size() + inboundPending.size() >= receiptCapacity) return LogicalAdmission.FULL;
        inboundPending.put(logicalId, expiry);
        return LogicalAdmission.NEW;
    }

    private synchronized boolean completeLogical(UUID logicalId, Instant expiry) {
        if (inboundPending.remove(logicalId) == null || !expiry.isAfter(Instant.now())) return false;
        inboundReceipts.put(logicalId, expiry.plus(Duration.ofMinutes(5)));
        return true;
    }

    private synchronized void releaseLogical(UUID logicalId) {
        inboundPending.remove(logicalId);
    }

    private void requireSession(String node, SecureFrame frame) throws FrameAuthenticationException {
        Session expected = sessions.get(node);
        if (expected == null || !expected.id().equals(frame.sessionId()) || expected.epoch() != frame.epoch()) {
            throw new FrameAuthenticationException("unknown or stale session");
        }
    }

    private boolean isCatalogSynchronized(String node) {
        Session session = sessions.get(node);
        return session != null && session.revision() == memberships.snapshot().revision();
    }

    private void sendState(String node) {
        Session session = sessions.get(node);
        if (session != null) sendNow(node, session.id(), session.epoch(), FrameType.STATE, null,
                channelStates.encode(memberships.snapshot()), Instant.now().plusSeconds(30));
    }

    private void sendAttempt(String node, Session session, ReliableOutbox.Attempt attempt) {
                SecureFrame frame = new SecureFrame(protocolVersion, session.id(), session.epoch(), attempt.sequence(), attempt.frameId(),
                attempt.logicalMessageId(), FrameType.MESSAGE, Instant.now(), attempt.expiresAt(), attempt.payload());
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    private void sendPresenceAttempt(String node, Session session, ReliableOutbox.Attempt attempt) {
        SecureFrame frame = new SecureFrame(protocolVersion, session.id(), session.epoch(), attempt.sequence(),
                attempt.frameId(), attempt.logicalMessageId(), FrameType.PRESENCE, Instant.now(),
                attempt.expiresAt(), attempt.payload());
        logger.debug("Dispatching presence frame to node {}: frameId={}, logicalId={}, attempt={}",
                node, attempt.frameId(), attempt.logicalMessageId(), attempt.attempt());
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    private void sendNow(String node, UUID sessionId, long epoch, FrameType type, UUID logicalId,
            byte[] payload, Instant expiresAt) {
        SecureFrame frame = new SecureFrame(protocolVersion, sessionId, epoch, sequence.incrementAndGet(), UUID.randomUUID(),
                logicalId, type, Instant.now(), expiresAt, payload);
        proxy.getServer(node).ifPresent(server -> server.sendPluginMessage(channel, secure.encode(frame)));
    }

    @Override public void close() {
        sessions.clear();
        presenceOutboxes.clear();
        pendingExternal.values().forEach(pending -> pending.completion().complete(null));
        pendingExternal.clear();
        runtime.close();
    }
}
