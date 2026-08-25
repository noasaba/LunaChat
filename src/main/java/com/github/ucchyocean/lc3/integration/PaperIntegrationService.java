package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lc3.LunaChatConfig;
import com.github.ucchyocean.lc3.LunaChatBukkit;
import com.github.ucchyocean.lc3.channel.Channel;
import com.github.ucchyocean.lc3.channel.ChannelManager;
import com.github.ucchyocean.lc3.member.ChannelMember;
import com.github.ucchyocean.lc3.member.ChannelMemberBukkit;
import com.github.ucchyocean.lunachat.api.*;
import com.github.ucchyocean.lunachat.core.InMemoryChannelDirectory;
import com.github.ucchyocean.lunachat.core.IntegrationRuntime;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Paper discovery/lifecycle adapter around the platform-independent runtime. */
public final class PaperIntegrationService {
    private record Pending(AcceptedMessage proposed, CompletableFuture<AcceptedMessage> completion) {}
    private static volatile PaperIntegrationService current;
    private final LunaChatBukkit plugin;
    private final ChannelManager manager;
    private final InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
    private final ThreadLocal<Pending> externalCall = new ThreadLocal<>();
    private final IntegrationRuntime runtime;
    private final PaperNetworkEdge networkEdge;

    private PaperIntegrationService(LunaChatBukkit plugin, ChannelManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        LunaChatConfig config = plugin.getLunaChatConfig();
        refresh();
        if ("network_edge".equals(config.getIntegrationRole())) {
            runtime = IntegrationRuntime.edge(directory, Clock.systemUTC());
            networkEdge = PaperNetworkEdge.create(plugin, this, config);
        } else if ("standalone".equals(config.getIntegrationRole())) {
            runtime = IntegrationRuntime.authority(RuntimeRole.STANDALONE_AUTHORITY, directory,
                    this::deliverExternal, Clock.systemUTC(), config.getIntegrationServerId(),
                    config.getIntegrationMaxPending(), config.getIntegrationDedupCapacity());
            networkEdge = null;
        } else {
            throw new IllegalArgumentException("Unknown integration.role: " + config.getIntegrationRole());
        }
    }

    public static PaperIntegrationService start(LunaChatBukkit plugin, ChannelManager manager) {
        PaperIntegrationService service = new PaperIntegrationService(plugin, manager);
        current = service;
        Bukkit.getServicesManager().register(LunaChatIntegrationApi.class, service.runtime, plugin, ServicePriority.Normal);
        return service;
    }

    public static PaperIntegrationService current() { return current; }

    public void refresh() {
        directory.replace(channelSnapshot());
    }

    List<ChannelDescriptor> channelSnapshot() {
        return manager.getChannels().stream().filter(channel -> !channel.isPersonalChat() && channel.isVisible())
                .map(channel -> new ChannelDescriptor(channel.getChannelId(), channel.getName(),
                        channel.getAlias().isBlank() ? Set.of() : Set.of(channel.getAlias()),
                        channel.isAcceptsExternalMessages() && !channel.isWorldRange())).toList();
    }

    private java.util.concurrent.CompletionStage<AcceptedMessage> deliverExternal(AcceptedMessage proposed) {
        return renderAccepted(proposed);
    }

    java.util.concurrent.CompletionStage<AcceptedMessage> renderAccepted(AcceptedMessage proposed) {
        CompletableFuture<AcceptedMessage> completion = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Channel channel = manager.getChannels().stream()
                    .filter(candidate -> candidate.getChannelId().equals(proposed.channelId())).findFirst().orElse(null);
            if (channel == null || !channel.isAcceptsExternalMessages()) {
                completion.complete(null);
                return;
            }
            externalCall.set(new Pending(proposed, completion));
            try {
                MessageAuthor.External author = (MessageAuthor.External) proposed.author();
                channel.chatFromOtherSource(author.displayName(), proposed.origin().namespace(), proposed.content());
                if (!completion.isDone()) completion.complete(null);
            } catch (RuntimeException error) {
                completion.completeExceptionally(error);
            } finally {
                externalCall.remove();
            }
        });
        return completion;
    }

    /** Called after filters/events have fixed final content, before local rendering. */
    public void accepted(Channel channel, ChannelMember member, String finalContent) {
        Pending pending = externalCall.get();
        if (pending != null) {
            AcceptedMessage proposed = pending.proposed();
            pending.completion().complete(new AcceptedMessage(proposed.messageId(), channel.getChannelId(),
                    channel.getName(), proposed.origin(), proposed.author(), proposed.sourceServerId(), finalContent,
                    proposed.createdAt(), proposed.expiresAt()));
            return;
        }
        if (runtime.runtimeRole() == RuntimeRole.NETWORK_EDGE) {
            if (networkEdge != null) networkEdge.offer(localMessage(channel, member, finalContent));
            return;
        }
        runtime.authorityGateway().accept(localMessage(channel, member, finalContent));
    }

    private AcceptedMessage localMessage(Channel channel, ChannelMember member, String finalContent) {
        Instant now = Instant.now();
        UUID logicalId = UUID.randomUUID();
        MessageAuthor author;
        MessageOrigin origin;
        if (member instanceof ChannelMemberBukkit bukkit && bukkit.getPlayer() != null) {
            Player player = bukkit.getPlayer();
            author = new MessageAuthor.Player(player.getUniqueId(), player.getName(), member.getDisplayName());
            origin = new MessageOrigin(OriginKind.MINECRAFT, "lunachat.minecraft", logicalId.toString());
        } else {
            author = new MessageAuthor.System(member == null ? "system" : member.getDisplayName());
            origin = new MessageOrigin(OriginKind.SYSTEM, "lunachat.system", logicalId.toString());
        }
        return new AcceptedMessage(logicalId, channel.getChannelId(), channel.getName(),
                origin, author, plugin.getLunaChatConfig().getIntegrationServerId(), finalContent,
                now, now.plus(Duration.ofMinutes(5)));
    }

    public LunaChatIntegrationApi api() { return runtime; }

    void networkReady() {
        runtime.mutableStatus().update(NetworkState.READY, "AUTHORITY_CONNECTED");
    }

    void networkUnavailable(String diagnostic) {
        runtime.mutableStatus().update(NetworkState.UNAVAILABLE, diagnostic);
    }

    public void stop() {
        Bukkit.getServicesManager().unregister(LunaChatIntegrationApi.class, runtime);
        if (networkEdge != null) networkEdge.close();
        runtime.close();
        if (current == this) current = null;
    }
}
