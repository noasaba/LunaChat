package com.github.ucchyocean.lunachat.api;

/** Bounded page request; callers must treat the cursor as opaque. */
public record ChannelPageRequest(int limit, String cursor) {
    public ChannelPageRequest {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be within [1, 100]");
        if (cursor != null && cursor.length() > 256) throw new IllegalArgumentException("cursor too long");
    }
}
