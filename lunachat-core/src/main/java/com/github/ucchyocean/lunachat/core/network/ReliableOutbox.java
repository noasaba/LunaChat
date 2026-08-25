package com.github.ucchyocean.lunachat.core.network;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Bounded retry state. Each retry receives a fresh frame identity and sequence. */
public final class ReliableOutbox {
    public record Attempt(UUID logicalMessageId, UUID frameId, long sequence, int attempt, byte[] payload, Instant expiresAt) {
        public Attempt { payload = Arrays.copyOf(payload, payload.length); }
        @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    }
    private static final class Entry {
        final UUID logicalId; final byte[] payload; final Instant expiresAt; int attempts; Instant nextAttempt;
        Entry(UUID id, byte[] payload, Instant expiresAt, Instant now) { this.logicalId=id; this.payload=Arrays.copyOf(payload,payload.length); this.expiresAt=expiresAt; this.nextAttempt=now; }
    }
    private final int capacity;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long sequence;
    public ReliableOutbox(int capacity, int maxAttempts, Duration baseBackoff) {
        if (capacity < 1 || maxAttempts < 1 || baseBackoff.isNegative() || baseBackoff.isZero()) throw new IllegalArgumentException("invalid outbox bounds");
        this.capacity=capacity; this.maxAttempts=maxAttempts; this.baseBackoff=baseBackoff;
    }
    public synchronized boolean offer(UUID logicalId, byte[] payload, Instant expiresAt, Instant now) {
        purge(now);
        if (entries.containsKey(logicalId)) return true;
        if (entries.size() >= capacity || !expiresAt.isAfter(now)) return false;
        entries.put(logicalId, new Entry(logicalId, payload, expiresAt, now)); return true;
    }
    public synchronized List<Attempt> pollDue(Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        purge(now); List<Attempt> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (result.size() == limit) break;
            if (entry.attempts >= maxAttempts || entry.nextAttempt.isAfter(now)) continue;
            entry.attempts++;
            long multiplier = 1L << Math.min(entry.attempts - 1, 10);
            entry.nextAttempt = now.plus(baseBackoff.multipliedBy(multiplier));
            result.add(new Attempt(entry.logicalId, UUID.randomUUID(), ++sequence, entry.attempts, entry.payload, entry.expiresAt));
        }
        return List.copyOf(result);
    }
    public synchronized boolean acknowledge(UUID logicalId) { return entries.remove(logicalId) != null; }
    public synchronized boolean contains(UUID logicalId, Instant now) { purge(now); return entries.containsKey(logicalId); }
    private void purge(Instant now) { entries.values().removeIf(entry -> !entry.expiresAt.isAfter(now) || entry.attempts >= maxAttempts); }
    public synchronized int size() { return entries.size(); }
}
