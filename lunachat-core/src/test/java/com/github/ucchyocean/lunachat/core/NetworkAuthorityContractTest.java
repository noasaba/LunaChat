package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.RuntimeRole;
import com.github.ucchyocean.lunachat.testkit.IntegrationApiContract;
import org.junit.jupiter.api.AfterEach;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Runs the same public TCK with the role used by the Velocity adapter. */
public final class NetworkAuthorityContractTest extends IntegrationApiContract {
    private final ChannelDescriptor descriptor = new ChannelDescriptor(ChannelId.random(), "network", Set.of(), true);
    private final InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
    private final IntegrationRuntime authority;
    private final IntegrationRuntime edge;

    public NetworkAuthorityContractTest() {
        directory.put(descriptor);
        authority = IntegrationRuntime.authority(RuntimeRole.NETWORK_AUTHORITY, directory,
                message -> CompletableFuture.completedFuture(message), Clock.systemUTC(), "velocity", 32, 128);
        edge = IntegrationRuntime.edge(directory, Clock.systemUTC());
    }

    @Override protected LunaChatIntegrationApi authority() { return authority; }
    @Override protected LunaChatIntegrationApi edge() { return edge; }
    @Override protected ChannelDescriptor externalChannel() { return descriptor; }
    @AfterEach void close() { authority.close(); edge.close(); }
}
