package com.github.ucchyocean.lunachat.core.network;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReliableOutboxTest {
    @Test void finalAttemptRetainsPayloadUntilItsAcknowledgementDeadline() {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        var outbox = new ReliableOutbox(1, 1, Duration.ofSeconds(1));
        UUID id = UUID.randomUUID();
        assertTrue(outbox.offer(id, new byte[]{1}, now.plusSeconds(60), now));
        assertEquals(1, outbox.pollDue(now, 1).size());
        assertTrue(outbox.contains(id, now.plusMillis(500)));
        assertArrayEquals(new byte[]{1}, outbox.payload(id, now.plusMillis(500)).orElseThrow());
        assertTrue(outbox.acknowledge(id));
    }

    @Test void exhaustedAttemptsReleaseCapacityAfterWaitingForFinalAck() {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        var outbox = new ReliableOutbox(1, 2, Duration.ofSeconds(1));
        UUID id = UUID.randomUUID();
        assertTrue(outbox.offer(id, new byte[]{1}, now.plusSeconds(60), now));
        var first = outbox.pollDue(now, 1).getFirst();
        var last = outbox.pollDue(now.plusSeconds(1), 1).getFirst();
        assertNotEquals(first.frameId(), last.frameId());
        assertEquals(id, last.logicalMessageId());
        assertTrue(outbox.contains(id, now.plusMillis(2999)));
        assertTrue(outbox.pollDue(now.plusMillis(2999), 1).isEmpty());
        assertFalse(outbox.contains(id, now.plusSeconds(3)));
        assertTrue(outbox.offer(UUID.randomUUID(), new byte[]{2}, now.plusSeconds(60), now.plusSeconds(3)));
    }

    @Test void messageExpiryStillLimitsTheFinalAcknowledgementWindow() {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        var outbox = new ReliableOutbox(1, 1, Duration.ofSeconds(10));
        UUID id = UUID.randomUUID();
        assertTrue(outbox.offer(id, new byte[]{1}, now.plusSeconds(1), now));
        outbox.pollDue(now, 1);
        assertFalse(outbox.contains(id, now.plusSeconds(1)));
    }
}
