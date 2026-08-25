package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.*;
import com.github.ucchyocean.lunachat.testkit.IntegrationApiContract;
import org.junit.jupiter.api.AfterEach;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class IntegrationRuntimeContractTest extends IntegrationApiContract {
    private final ChannelDescriptor descriptor = new ChannelDescriptor(ChannelId.random(), "global", Set.of("g"), true);
    private final InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
    private final IntegrationRuntime authority;
    private final IntegrationRuntime edge;
    public IntegrationRuntimeContractTest() {
        directory.put(descriptor);
        authority = IntegrationRuntime.authority(RuntimeRole.STANDALONE_AUTHORITY, directory,
                message -> CompletableFuture.completedFuture(message), Clock.systemUTC(), "paper-1", 32, 128);
        edge = IntegrationRuntime.edge(directory, Clock.systemUTC());
    }
    @Override protected LunaChatIntegrationApi authority() { return authority; }
    @Override protected LunaChatIntegrationApi edge() { return edge; }
    @Override protected ChannelDescriptor externalChannel() { return descriptor; }
    @AfterEach void close() { authority.close(); edge.close(); }
}
