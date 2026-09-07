package com.github.ucchyocean.lc3.integration;

import static org.junit.Assert.assertEquals;

import com.github.ucchyocean.lunachat.core.network.NetworkProtocol;
import org.junit.Test;

public class PaperNetworkProtocolVersionTest {
    @Test
    public void channelAndSignedFrameAlwaysUseTheSameSharedVersion() {
        assertEquals(NetworkProtocol.VERSION, PaperNetworkEdge.PROTOCOL);
        assertEquals(NetworkProtocol.CHANNEL, PaperNetworkEdge.CHANNEL);
        assertEquals("lunachat:network_v" + PaperNetworkEdge.PROTOCOL, PaperNetworkEdge.CHANNEL);
    }
}
