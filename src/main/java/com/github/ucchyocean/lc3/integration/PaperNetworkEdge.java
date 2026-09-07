package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lc3.LunaChatBukkit;
import com.github.ucchyocean.lc3.Messages;
import com.github.ucchyocean.lc3.LunaChatConfig;
import com.github.ucchyocean.lc3.channel.ChannelManager;
import com.github.ucchyocean.lc3.channel.Channel;
import com.github.ucchyocean.lc3.member.ChannelMember;
import com.github.ucchyocean.lc3.util.PlayerVisibility;
import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.core.network.AcceptedMessageCodec;
import com.github.ucchyocean.lunachat.core.network.FrameAuthenticationException;
import com.github.ucchyocean.lunachat.core.network.FrameType;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec;
import com.github.ucchyocean.lunachat.core.network.ChannelCreateCodec;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.core.network.ReliableOutbox;
import com.github.ucchyocean.lunachat.core.network.ReplayWindow;
import com.github.ucchyocean.lunachat.core.network.ReplayFrameException;
import com.github.ucchyocean.lunachat.core.network.SecureFrame;
import com.github.ucchyocean.lunachat.core.network.SecureFrameCodec;
import com.github.ucchyocean.lunachat.core.network.SharedPassphrase;
import com.github.ucchyocean.lunachat.core.network.PrivateMessageCodec;
import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.github.ucchyocean.lunachat.core.network.NetworkProtocol;
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
    static final String CHANNEL = NetworkProtocol.CHANNEL;
    static final int PROTOCOL = NetworkProtocol.VERSION;
    private final LunaChatBukkit plugin;
    private final PaperIntegrationService integration;
    private volatile String nodeId = "";
    private final SecureFrameCodec secure;
    private final AcceptedMessageCodec messages = new AcceptedMessageCodec();
    private final AuthoritySnapshotCodec channelStates = new AuthoritySnapshotCodec();
    private final ChannelCreateCodec channelCreates = new ChannelCreateCodec();
    private final PrivateMessageCodec privateMessages = new PrivateMessageCodec();
    private final PresenceCodec presences = new PresenceCodec();
    private final Map<UUID, PendingChange> changes = new LinkedHashMap<>();
    private record PendingChange(AuthoritySnapshotCodec.Change change, Instant expires,
            CompletableFuture<Boolean> completion) {}
    private static final class PendingCreate {
        private final String name;
        private final Instant expires;
        private final CompletableFuture<PaperIntegrationService.ChannelCreationResult> completion;
        private boolean applied;
        private PendingCreate(String name, Instant expires,
                CompletableFuture<PaperIntegrationService.ChannelCreationResult> completion) {
            this.name = name; this.expires = expires; this.completion = completion;
        }
    }
    private final Map<UUID, PendingCreate> creates = new LinkedHashMap<>();
    private AuthoritySnapshotCodec.Snapshot snapshot;
    private final ReliableOutbox outbox;
    private final int dedupCapacity;
    private record InboundPending(CompletableFuture<AcceptedMessage> completion, Instant expiresAt) {}
    private final Map<UUID, AcceptedMessage> inboundReceipts = new LinkedHashMap<>();
    private final Map<UUID, InboundPending> inboundPending = new LinkedHashMap<>();
    private final Map<UUID, CompletableFuture<PrivateMessageCodec.Result>> privateRequests = new LinkedHashMap<>();
    private final Map<UUID, Instant> privateReceipts = new LinkedHashMap<>();
    private final Map<UUID, String> networkPlayers = new LinkedHashMap<>();
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

    CompletableFuture<PrivateMessageCodec.Result> requestPrivate(UUID sender, String senderName, String targetName, String content) {
        CompletableFuture<PrivateMessageCodec.Result> result = new CompletableFuture<>();
        if (!isReady() || privateRequests.size() >= 256) { result.complete(new PrivateMessageCodec.Result("UNAVAILABLE", null, "", null)); return result; }
        UUID id = UUID.randomUUID(); privateRequests.put(id, result);
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) { privateRequests.remove(id); result.complete(new PrivateMessageCodec.Result("CARRIER_UNAVAILABLE", null, "", null)); return result; }
        send(carrier, FrameType.PRIVATE_REQUEST, id, privateMessages.encode(new PrivateMessageCodec.Request(sender,senderName,targetName,content)), Instant.now().plusSeconds(10));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { CompletableFuture<PrivateMessageCodec.Result> p=privateRequests.remove(id); if(p!=null)p.complete(new PrivateMessageCodec.Result("TIMEOUT",null,"",null)); }, 200L);
        return result;
    }

    private void tick() {
        // Expire even with no carrier or while authority synchronization is down.
        var expired = new java.util.ArrayList<PendingChange>();
        changes.values().removeIf(pending -> {
            if (pending.expires().isAfter(Instant.now())) return false;
            expired.add(pending); return true;
        });
        expired.forEach(pending -> pending.completion().complete(false));
        creates.values().removeIf(pending -> {
            if (pending.expires.isAfter(Instant.now())) return false;
            pending.completion.complete(PaperIntegrationService.ChannelCreationResult.UNAVAILABLE);
            return true;
        });
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) {
            integration.networkAwaitingPlayerCarrier();
            return;
        }
        long nowMillis = System.currentTimeMillis();
        if (!isReady() || nowMillis - lastHelloMillis >= 10_000L) {
            send(carrier, FrameType.HELLO, null, new byte[0], Instant.now().plusSeconds(10));
            lastHelloMillis = nowMillis;
            return;
        }
        if (!isReady()) return;
        var iterator = changes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().expires().isAfter(Instant.now())) {
                entry.getValue().completion().complete(false); iterator.remove(); continue;
            }
            send(carrier, FrameType.MEMBER_CHANGE, entry.getKey(),
                    channelStates.encodeChange(entry.getValue().change()), entry.getValue().expires());
        }
        for (Map.Entry<UUID, PendingCreate> entry : creates.entrySet()) {
            PendingCreate pending = entry.getValue();
            if (!pending.applied) send(carrier, FrameType.CHANNEL_CREATE, entry.getKey(),
                    channelCreates.encode(new ChannelCreateCodec.Request(pending.name, false)), pending.expires);
        }
        for (ReliableOutbox.Attempt attempt : outbox.pollDue(Instant.now(), 32)) {
            SecureFrame frame = new SecureFrame(PROTOCOL, sessionId, epoch, attempt.sequence(), attempt.frameId(),
                    attempt.logicalMessageId(), FrameType.MESSAGE, Instant.now(), attempt.expiresAt(), attempt.payload());
            carrier.sendPluginMessage(plugin, CHANNEL, secure.encode(frame));
        }
    }

    /** Starts the carrier-dependent handshake immediately on player login. */
    void connectNow(Player carrier) {
        if (carrier == null || isReady()) return;
        integration.networkConnecting();
        send(carrier, FrameType.HELLO, null, new byte[0], Instant.now().plusSeconds(10));
        lastHelloMillis = System.currentTimeMillis();
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
                var catalog = channelStates.decode(frame.payload());
                catalogSynchronized.set(false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (snapshot == null || catalog.revision() >= snapshot.revision()) {
                            integration.applyAuthoritySnapshot(catalog);
                            snapshot = catalog;
                        }
                        completeCreatedChannels();
                        catalogSynchronized.set(true);
                        send(player, FrameType.STATE, null, channelStates.encode(snapshot), Instant.now().plusSeconds(30));
                    } catch (RuntimeException rejectedCatalog) {
                        catalogSynchronized.set(false);
                        ready.set(false);
                        integration.networkUnavailable("CHANNEL_CATALOG_REJECTED");
                        plugin.getLogger().warning("Rejected LunaChat authority catalog: " + rejectedCatalog.getMessage());
                    }
                });
            } else if (frame.type() == FrameType.MEMBER_RESULT && frame.logicalMessageId() != null) {
                PendingChange pending = changes.remove(frame.logicalMessageId());
                if (pending != null) pending.completion().complete(
                        "APPLIED".equals(new String(frame.payload(), StandardCharsets.UTF_8)));
            } else if (frame.type() == FrameType.CHANNEL_CREATE_RESULT && frame.logicalMessageId() != null) {
                PendingCreate pending = creates.get(frame.logicalMessageId());
                if (pending != null) {
                    String result = new String(frame.payload(), StandardCharsets.UTF_8);
                    if ("APPLIED".equals(result)) {
                        pending.applied = true;
                        completeCreatedChannels();
                    } else {
                        creates.remove(frame.logicalMessageId());
                        pending.completion.complete("EXISTS".equals(result)
                                ? PaperIntegrationService.ChannelCreationResult.EXISTS
                                : PaperIntegrationService.ChannelCreationResult.REJECTED);
                    }
                }
            } else if (frame.type() == FrameType.ACK && frame.logicalMessageId() != null) {
                outbox.acknowledge(frame.logicalMessageId());
            } else if (frame.type() == FrameType.MESSAGE && frame.logicalMessageId() != null && isReady()) {
                AcceptedMessage proposed = messages.decode(frame.payload());
                if (!frame.logicalMessageId().equals(proposed.messageId())) {
                    throw new FrameAuthenticationException("logical identity mismatch");
                }
                receiveMessage(player, proposed);
            } else if (frame.type() == FrameType.PRIVATE_RESULT && frame.logicalMessageId() != null) {
                CompletableFuture<PrivateMessageCodec.Result> completion = privateRequests.remove(frame.logicalMessageId());
                if (completion != null) completion.complete(privateMessages.decodeResult(frame.payload()));
            } else if (frame.type() == FrameType.PRIVATE_DELIVERY && frame.logicalMessageId() != null && isReady()) {
                PrivateMessageCodec.Delivery delivery = privateMessages.decodeDelivery(frame.payload());
                UUID requestId = frame.logicalMessageId();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String status = receivePrivate(requestId, delivery);
                    send(player, FrameType.PRIVATE_ACK, requestId, status.getBytes(StandardCharsets.UTF_8), Instant.now().plusSeconds(10));
                });
            } else if (frame.type() == FrameType.PRESENCE && isReady()) {
                PresenceCodec.Event presence = presences.decode(frame.payload());
                updateNetworkPlayers(presence);
                Bukkit.getScheduler().runTask(plugin, () -> renderPresence(presence));
                send(player, FrameType.PRESENCE_ACK, frame.logicalMessageId(), new byte[0], Instant.now().plusSeconds(10));
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

    private synchronized String receivePrivate(UUID requestId, PrivateMessageCodec.Delivery delivery) {
        Instant now = Instant.now();
        privateReceipts.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (privateReceipts.containsKey(requestId)) {
            plugin.getLogger().fine("PM duplicate delivery acknowledged: requestId=" + requestId);
            return "DELIVERED";
        }
        Player target = Bukkit.getPlayer(delivery.target());
        String status = target == null ? "NOT_FOUND" : deliverPrivate(target, delivery);
        if ("DELIVERED".equals(status)) privateReceipts.put(requestId, now.plusSeconds(30));
        plugin.getLogger().fine("PM delivery rendered: requestId=" + requestId + ", status=" + status);
        return status;
    }

    private String deliverPrivate(Player target, PrivateMessageCodec.Delivery delivery) {
        if (!target.getUniqueId().equals(delivery.target())) return "NOT_FOUND";
        ChannelManager manager = integration.channelManager();
        Channel channel = manager.createPersonalChannel(delivery.senderName() + ">" + delivery.targetName(), ChannelMember.getChannelMember(target));
        if (channel == null) return "RENDER_FAILED";
        channel.setVisible(false); channel.addMember(ChannelMember.getChannelMember(target));
        channel.setPrivateMessageTo(ChannelMember.getChannelMember(target));
        channel.chat(new com.github.ucchyocean.lc3.member.ChannelMemberOther(delivery.senderName()), delivery.content());
        com.github.ucchyocean.lc3.command.DataMaps.rememberPrivate(delivery.sender(), delivery.senderName(),
                delivery.target(), delivery.targetName());
        return "DELIVERED";
    }

    private void renderPresence(PresenceCodec.Event p) {
        if (p.kind() == PresenceCodec.Kind.SNAPSHOT) return;
        if (p.visibility() == PresenceCodec.Visibility.PUBLIC) {
            String text = switch (p.kind()) {
                case JOIN -> Messages.presenceJoin(p.name());
                case LOGIN -> Messages.presenceLogin(p.name());
                case MOVE -> Messages.presenceMove(p.name(), p.from(), p.to());
                case QUIT -> Messages.presenceQuit(p.name());
                case SNAPSHOT -> "";
            };
            if (!text.isEmpty()) Bukkit.broadcastMessage(text);
        } else if (p.visibility() == PresenceCodec.Visibility.HIDDEN) {
            for (Player viewer : Bukkit.getOnlinePlayers()) if (viewer.hasPermission("lunachat.presence.hidden")) {
                viewer.sendMessage("[presence hidden] "+p.name()+" "+p.kind().name().toLowerCase());
            }
        } else {
            plugin.getLogger().fine("Presence notification withheld while visibility is UNKNOWN: player=" + p.player()
                    + ", kind=" + p.kind() + ", from=" + p.from() + ", to=" + p.to());
        }
    }

    private synchronized void updateNetworkPlayers(PresenceCodec.Event event) {
        if (event.visibility() == PresenceCodec.Visibility.PUBLIC) {
            if (event.kind() == PresenceCodec.Kind.QUIT) networkPlayers.remove(event.player());
            else networkPlayers.put(event.player(), event.name());
        } else {
            networkPlayers.remove(event.player());
        }
    }

    synchronized java.util.List<String> visibleNetworkPlayerNames(ChannelMember sender, String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Player local : Bukkit.getOnlinePlayers()) {
            ChannelMember member = ChannelMember.getChannelMember(local);
            if (PlayerVisibility.isVisibleTo(sender, member) && local.getName().toLowerCase(java.util.Locale.ROOT).startsWith(needle)) names.add(local.getName());
        }
        for (String name : networkPlayers.values()) if (name.toLowerCase(java.util.Locale.ROOT).startsWith(needle)) names.add(name);
        return java.util.List.copyOf(names);
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

    CompletableFuture<Boolean> requestMembership(ChannelId channel, UUID player, boolean joined) {
        if (!Bukkit.isPrimaryThread()) {
            var completion = new CompletableFuture<Boolean>();
            Bukkit.getScheduler().runTask(plugin, () -> requestMembership(channel, player, joined)
                    .whenComplete((result, error) -> {
                        if (error != null) completion.completeExceptionally(error);
                        else completion.complete(result);
                    }));
            return completion;
        }
        if (!isReady() || snapshot == null || changes.size() >= 256) return CompletableFuture.completedFuture(false);
        var key = new AuthoritySnapshotCodec.Key(channel, player);
        if (changes.values().stream().anyMatch(p -> p.change().key().equals(key)))
            return CompletableFuture.completedFuture(false);
        var completion = new CompletableFuture<Boolean>();
        changes.put(UUID.randomUUID(), new PendingChange(new AuthoritySnapshotCodec.Change(key, joined,
                snapshot.version(key)), Instant.now().plusSeconds(30), completion));
        return completion;
    }

    CompletableFuture<PaperIntegrationService.ChannelCreationResult> requestChannelCreation(String name) {
        try { new ChannelCreateCodec.Request(name, false); }
        catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(PaperIntegrationService.ChannelCreationResult.REJECTED);
        }
        if (!Bukkit.isPrimaryThread()) {
            var completion = new CompletableFuture<PaperIntegrationService.ChannelCreationResult>();
            Bukkit.getScheduler().runTask(plugin, () -> requestChannelCreation(name).whenComplete((result, error) -> {
                if (error != null) completion.completeExceptionally(error); else completion.complete(result);
            }));
            return completion;
        }
        if (!isReady() || snapshot == null || creates.size() >= 64) {
            return CompletableFuture.completedFuture(PaperIntegrationService.ChannelCreationResult.UNAVAILABLE);
        }
        if (creates.values().stream().anyMatch(pending -> pending.name.equalsIgnoreCase(name))) {
            return CompletableFuture.completedFuture(PaperIntegrationService.ChannelCreationResult.REJECTED);
        }
        var completion = new CompletableFuture<PaperIntegrationService.ChannelCreationResult>();
        creates.put(UUID.randomUUID(), new PendingCreate(name, Instant.now().plusSeconds(30), completion));
        return completion;
    }

    private void completeCreatedChannels() {
        var iterator = creates.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingCreate pending = iterator.next().getValue();
            if (pending.applied && snapshot != null && snapshot.channels().stream()
                    .anyMatch(channel -> channel.name().equalsIgnoreCase(pending.name))) {
                iterator.remove();
                pending.completion.complete(PaperIntegrationService.ChannelCreationResult.CREATED);
            }
        }
    }

    String nodeId() {
        if (!isReady()) throw new IllegalStateException("network node identity is not assigned");
        return nodeId;
    }

    private synchronized void purgeInbound(Instant now) {
        inboundReceipts.values().removeIf(message -> !message.expiresAt().plus(Duration.ofMinutes(5)).isAfter(now));
        inboundPending.values().removeIf(pending -> !pending.expiresAt().isAfter(now));
    }

    @Override public void close() {
        changes.values().forEach(p -> p.completion().complete(false)); changes.clear();
        creates.values().forEach(p -> p.completion.complete(PaperIntegrationService.ChannelCreationResult.UNAVAILABLE));
        creates.clear();
        ready.set(false);
        catalogSynchronized.set(false);
        nodeId = "";
        if (taskId != 0) Bukkit.getScheduler().cancelTask(taskId);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }
}
