package com.github.ucchyocean.lunachat.api;

import java.util.UUID;

/** Persistent channel identity. Names and aliases are never routing keys. */
public record ChannelId(String value) {
    public ChannelId {
        ApiConstraints.text(value, "channelId", 36);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("channelId must be canonical UUID");
    }
    public static ChannelId random() { return new ChannelId(UUID.randomUUID().toString()); }
}
