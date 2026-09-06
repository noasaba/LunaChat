package com.github.ucchyocean.lunachat.velocity;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PrivateSenderValidationTest {
    @Test void carrierIsNotComparedWithLogicalSender() {
        UUID sender = UUID.randomUUID();
        var logicalSender = new PrivateSenderValidation.PlayerState(sender, "Tomochan10", "paper-1", true);
        var result = PrivateSenderValidation.validate(logicalSender, sender, "Tomochan10", "paper-1");
        assertTrue(result.accepted());
    }

    @Test void senderMustCurrentlyBeOnTheSourceBackend() {
        UUID sender = UUID.randomUUID();
        var player = new PrivateSenderValidation.PlayerState(sender, "Tomochan10", "paper-2", true);
        var result = PrivateSenderValidation.validate(player, sender, "Tomochan10", "paper-1");
        assertFalse(result.accepted());
        assertEquals("NOT_FOUND", result.resultStatus());
    }

    @Test void forgedUuidOrNameIsRejectedWithoutDisconnectingTheBackend() {
        UUID sender = UUID.randomUUID();
        var player = new PrivateSenderValidation.PlayerState(sender, "Tomochan10", "paper-1", true);
        assertFalse(PrivateSenderValidation.validate(player, UUID.randomUUID(), "Tomochan10", "paper-1").accepted());
        assertFalse(PrivateSenderValidation.validate(player, sender, "noa_berry", "paper-1").accepted());
    }
}
