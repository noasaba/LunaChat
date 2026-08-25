package com.github.ucchyocean.lunachat.testkit;

import com.github.ucchyocean.lunachat.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Reusable TCK for every standalone/network authority implementation. */
public abstract class IntegrationApiContract {
    protected abstract LunaChatIntegrationApi authority();
    protected abstract LunaChatIntegrationApi edge();
    protected abstract ChannelDescriptor externalChannel();

    protected ExternalMessageRequest request(String externalId) {
        ChannelDescriptor channel = externalChannel();
        return new ExternalMessageRequest(channel.id(), new ExternalMessageIdentity("lunabridge.discord", externalId),
                new MessageAuthor.External("lunabridge.discord", "user-1", "Discord User"), "hello",
                Instant.now(), Duration.ofMinutes(5));
    }

    @Test public void authorityAdvertisesRequiredCapabilities() {
        assertTrue(authority().capabilities().contains(Capability.OBSERVE_ACCEPTED_MESSAGES));
        assertTrue(authority().capabilities().contains(Capability.PUBLISH_EXTERNAL_MESSAGES));
        assertNotEquals(RuntimeRole.NETWORK_EDGE, authority().runtimeRole());
    }

    @Test public void edgeCannotMasqueradeAsAuthority() throws Exception {
        assertEquals(RuntimeRole.NETWORK_EDGE, edge().runtimeRole());
        assertFalse(edge().capabilities().contains(Capability.PUBLISH_EXTERNAL_MESSAGES));
        ExternalPublishResult result = edge().messages().publishExternal(request("edge-1")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(PublishStatus.UNAVAILABLE, result.status());
        assertThrows(UnsupportedOperationException.class, () -> edge().messages().observeAcceptedMessages(message -> {}));
    }

    @Test public void externalIdentityIsIdempotent() throws Exception {
        ExternalPublishResult first = authority().messages().publishExternal(request("same-id")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        ExternalPublishResult duplicate = authority().messages().publishExternal(request("same-id")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(PublishStatus.ACCEPTED, first.status());
        assertEquals(PublishStatus.DUPLICATE, duplicate.status());
        assertEquals(first.messageId(), duplicate.messageId());
    }
}
