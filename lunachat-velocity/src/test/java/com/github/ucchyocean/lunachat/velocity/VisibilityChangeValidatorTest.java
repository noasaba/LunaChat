package com.github.ucchyocean.lunachat.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisibilityChangeValidatorTest {
    @Test
    void rejectsReorderedAndDuplicateSequence() {
        VisibilityChangeValidator validator = new VisibilityChangeValidator();
        assertTrue(validator.reserve(10));
        assertFalse(validator.reserve(10));
        assertFalse(validator.reserve(9));
        assertTrue(validator.reserve(11));
    }

    @Test
    void rejectsDisconnectedReconnectedAndWrongServerCallbacks() {
        VisibilityChangeValidator validator = new VisibilityChangeValidator();
        var change = new SVSyncVisibilityIntegration.Change(UUID.randomUUID(), PresenceCodec.Visibility.HIDDEN,
                PresenceCodec.Visibility.PUBLIC, true, "main", 1, true);
        assertTrue(validator.validAtApply(change, 2, 2, true, "main"));
        assertFalse(validator.validAtApply(change, 1, 2, true, "main"));
        assertFalse(validator.validAtApply(change, 2, 2, false, "main"));
        assertFalse(validator.validAtApply(change, 2, 2, true, "lobby"));
    }
}
