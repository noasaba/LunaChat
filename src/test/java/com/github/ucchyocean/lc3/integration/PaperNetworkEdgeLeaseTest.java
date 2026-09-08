package com.github.ucchyocean.lc3.integration;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PaperNetworkEdgeLeaseTest {
    @Test
    public void authorityLeaseExpiresSoBukkitPresenceCanFallBack() {
        long response = 1_000_000L;
        assertTrue(PaperNetworkEdge.authorityLeaseValid(response, response + 10_000L));
        assertFalse(PaperNetworkEdge.authorityLeaseValid(response, response + 25_001L));
        assertFalse(PaperNetworkEdge.authorityLeaseValid(0L, response));
    }
}
