package com.github.ucchyocean.lc3.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PaperPresencePolicyTest {
    @Test public void suppressesBukkitPresenceOnlyWhenNetworkReplacementIsReady() {
        assertTrue(PaperPresencePolicy.suppressLocalMessage(true, true));
        assertFalse(PaperPresencePolicy.suppressLocalMessage(true, false));
        assertFalse(PaperPresencePolicy.suppressLocalMessage(false, true));
    }
}
