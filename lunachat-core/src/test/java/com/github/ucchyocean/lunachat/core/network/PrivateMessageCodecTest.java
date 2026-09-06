package com.github.ucchyocean.lunachat.core.network;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PrivateMessageCodecTest {
    @Test void requestDeliveryAndResultRoundTripWithoutLosingUuidOrText() throws Exception {
        var codec = new PrivateMessageCodec();
        UUID sender = UUID.randomUUID(), target = UUID.randomUUID();
        var request = new PrivateMessageCodec.Request(sender, "Alice", "Bob", "hello");
        assertEquals(request, codec.decodeRequest(codec.encode(request)));
        var delivery = new PrivateMessageCodec.Delivery(sender, "Alice", target, "Bob", "hello");
        assertEquals(delivery, codec.decodeDelivery(codec.encode(delivery)));
        var result = new PrivateMessageCodec.Result("DELIVERED", target, "Bob", null);
        assertEquals(result, codec.decodeResult(codec.encode(result)));
    }

    @Test void rejectsTrailingBytes() {
        var codec = new PrivateMessageCodec();
        byte[] encoded = codec.encode(new PrivateMessageCodec.Request(UUID.randomUUID(), "A", "B", "x"));
        byte[] malformed = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(java.io.IOException.class, () -> codec.decodeRequest(malformed));
    }
}
