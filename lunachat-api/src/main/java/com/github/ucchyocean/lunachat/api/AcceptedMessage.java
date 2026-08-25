package com.github.ucchyocean.lunachat.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A logical message accepted after LunaChat filtering, authorization and final
 * content transformation. It does not assert that every client rendered it.
 */
public record AcceptedMessage(UUID messageId, ChannelId channelId, String channelName,
        MessageOrigin origin, MessageAuthor author, String sourceServerId, String content,
        Instant createdAt, Instant expiresAt) {
    public AcceptedMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(channelId, "channelId");
        channelName = ApiConstraints.text(channelName, "channelName", 128);
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(author, "author");
        sourceServerId = ApiConstraints.text(sourceServerId, "sourceServerId", 64);
        content = ApiConstraints.content(content);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Duration lifetime = Duration.between(createdAt, expiresAt);
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("message lifetime must be within (0, 24h]");
        }
    }
}
