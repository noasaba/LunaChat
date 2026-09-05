package com.github.ucchyocean.lunachat.core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelCreateCodecTest {
    @Test void roundTripsBoundedRequest() throws Exception {
        var codec = new ChannelCreateCodec();
        var request = new ChannelCreateCodec.Request("global", true);
        assertEquals(request, codec.decode(codec.encode(request)));
    }

    @Test void rejectsInvalidNamesAndTrailingData() {
        var codec = new ChannelCreateCodec();
        assertThrows(IllegalArgumentException.class, () -> new ChannelCreateCodec.Request("bad name", false));
        byte[] valid = codec.encode(new ChannelCreateCodec.Request("global", false));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(java.io.IOException.class, () -> codec.decode(trailing));
    }
}
