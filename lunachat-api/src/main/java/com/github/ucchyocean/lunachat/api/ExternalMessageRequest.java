package com.github.ucchyocean.lunachat.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Validated request to publish an external-origin message through LunaChat. */
public record ExternalMessageRequest(ChannelId channelId, ExternalMessageIdentity identity,
        MessageAuthor.External author, String content, Instant createdAt, Duration requestedLifetime) {
    public ExternalMessageRequest {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(author, "author");
        if (!identity.namespace().equals(author.namespace())) {
            throw new IllegalArgumentException("identity and author namespaces differ");
        }
        content = ApiConstraints.content(content);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(requestedLifetime, "requestedLifetime");
        if (requestedLifetime.compareTo(Duration.ofSeconds(1)) < 0
                || requestedLifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("requested lifetime must be within [1s, 24h]");
        }
    }
}
