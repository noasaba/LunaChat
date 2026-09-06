package com.github.ucchyocean.lunachat.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VelocityStartupGateTest {
    @Test
    void startsOnlyAfterFirstSuccessfulListenerBind() {
        VelocityStartupGate gate = new VelocityStartupGate();

        assertTrue(gate.onListenerBound());
        assertFalse(gate.onListenerBound());
    }

    @Test
    void bindCannotStartResourcesAfterShutdown() {
        VelocityStartupGate gate = new VelocityStartupGate();

        gate.close();

        assertFalse(gate.onListenerBound());
    }
}
