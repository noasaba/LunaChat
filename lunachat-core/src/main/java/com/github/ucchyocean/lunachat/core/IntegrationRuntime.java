package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.*;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class IntegrationRuntime implements LunaChatIntegrationApi, AutoCloseable {
    public static final ApiVersion API_VERSION = new ApiVersion(1, 0, 0);
    private final RuntimeRole role;
    private final Set<Capability> capabilities;
    private final ChannelQueryService channels;
    private final MessageGateway messages;
    private final LifecycleNetworkStatus status;
    private final BoundedMessageGateway authorityGateway;

    public static IntegrationRuntime authority(RuntimeRole role, ChannelDirectory directory,
            ExternalDeliverySink sink, java.time.Clock clock, String serverId, int pending, int receipts) {
        if (role == RuntimeRole.NETWORK_EDGE) throw new IllegalArgumentException("edge is not an authority");
        LifecycleNetworkStatus status = new LifecycleNetworkStatus(clock, NetworkState.READY, "READY");
        BoundedMessageGateway gateway = new BoundedMessageGateway(directory, sink, clock, serverId, pending, receipts);
        return new IntegrationRuntime(role, directory, gateway, status, gateway);
    }

    public static IntegrationRuntime edge(ChannelDirectory directory, java.time.Clock clock) {
        LifecycleNetworkStatus status = new LifecycleNetworkStatus(clock, NetworkState.UNAVAILABLE, "AUTHORITY_NOT_CONNECTED");
        MessageGateway unavailable = new MessageGateway() {
            @Override public Subscription observeAcceptedMessages(AcceptedMessageListener listener) {
                throw new UnsupportedOperationException("NETWORK_EDGE does not expose authority observations");
            }
            @Override public java.util.concurrent.CompletionStage<ExternalPublishResult> publishExternal(ExternalMessageRequest request) {
                return CompletableFuture.completedFuture(ExternalPublishResult.rejected(
                        PublishStatus.UNAVAILABLE, true, "NOT_AUTHORITY"));
            }
        };
        return new IntegrationRuntime(RuntimeRole.NETWORK_EDGE, directory, unavailable, status, null);
    }

    private IntegrationRuntime(RuntimeRole role, ChannelDirectory directory, MessageGateway messages,
            LifecycleNetworkStatus status, BoundedMessageGateway authorityGateway) {
        this.role = role;
        this.channels = new BoundedChannelQueryService(directory);
        this.messages = messages;
        this.status = status;
        this.authorityGateway = authorityGateway;
        this.capabilities = role == RuntimeRole.NETWORK_EDGE
                ? Set.copyOf(EnumSet.of(Capability.QUERY_CHANNELS, Capability.QUERY_NETWORK_STATUS))
                : Set.copyOf(EnumSet.allOf(Capability.class));
    }

    @Override public ApiVersion apiVersion() { return API_VERSION; }
    @Override public RuntimeRole runtimeRole() { return role; }
    @Override public Set<Capability> capabilities() { return capabilities; }
    @Override public ChannelQueryService channels() { return channels; }
    @Override public MessageGateway messages() { return messages; }
    @Override public NetworkStatusService networkStatus() { return status; }
    public BoundedMessageGateway authorityGateway() {
        if (authorityGateway == null) throw new IllegalStateException("runtime is not an authority");
        return authorityGateway;
    }
    public LifecycleNetworkStatus mutableStatus() { return status; }
    @Override public void close() {
        status.update(NetworkState.SHUTTING_DOWN, "SHUTTING_DOWN");
        if (authorityGateway != null) authorityGateway.close();
    }
}
