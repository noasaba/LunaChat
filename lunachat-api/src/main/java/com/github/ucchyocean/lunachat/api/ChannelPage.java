package com.github.ucchyocean.lunachat.api;

import java.util.List;
import java.util.Objects;

/** Immutable bounded page and an opaque continuation cursor. */
public record ChannelPage(List<ChannelDescriptor> channels, String nextCursor) {
    public ChannelPage { channels = List.copyOf(Objects.requireNonNull(channels, "channels")); }
}
