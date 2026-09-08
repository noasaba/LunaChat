package com.github.ucchyocean.lc3.integration;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PaperPresencePolicyTest {
    @Test public void networkEdgesAlwaysUseBackendLocalPresence() {
        assertTrue(PaperPresencePolicy.useLocalPresence(true));
    }

    @Test public void hiddenSubjectIsNotAnnouncedToOrdinaryViewers() {
        assertTrue(PaperPresencePolicy.canReceiveLocalPresence(true, false));
        assertTrue(PaperPresencePolicy.canReceiveLocalPresence(false, true));
        org.junit.Assert.assertFalse(PaperPresencePolicy.canReceiveLocalPresence(false, false));
    }
}
