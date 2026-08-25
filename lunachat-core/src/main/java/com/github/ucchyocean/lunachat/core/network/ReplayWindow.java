package com.github.ucchyocean.lunachat.core.network;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ReplayWindow {
    private final int capacity;
    private final Map<UUID, Instant> seen = new LinkedHashMap<>();
    public ReplayWindow(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }
    public synchronized boolean accept(UUID frameId, Instant expiresAt, Instant now) {
        purge(now);
        if (seen.containsKey(frameId)) return false;
        if (seen.size() >= capacity) return false;
        seen.put(frameId, expiresAt);
        return true;
    }
    private void purge(Instant now) {
        Iterator<Map.Entry<UUID, Instant>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) if (!iterator.next().getValue().isAfter(now)) iterator.remove();
    }
    public synchronized int size() { return seen.size(); }
}
