package com.github.ucchyocean.lunachat.core.network;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record SecureFrame(int protocolVersion, UUID sessionId, long epoch, long sequence,
        UUID frameId, UUID logicalMessageId, FrameType type, Instant sentAt, Instant expiresAt, byte[] payload) {
    public SecureFrame {
        if (protocolVersion < 1 || epoch < 0 || sequence < 0) throw new IllegalArgumentException("invalid frame counters");
        Objects.requireNonNull(sessionId); Objects.requireNonNull(frameId); Objects.requireNonNull(type);
        Objects.requireNonNull(sentAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(payload);
        if (!expiresAt.isAfter(sentAt)) throw new IllegalArgumentException("frame already expired");
        if (payload.length > 65535) throw new IllegalArgumentException("payload too large");
        payload = Arrays.copyOf(payload, payload.length);
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
