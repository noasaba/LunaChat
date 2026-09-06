package com.github.ucchyocean.lunachat.core.network;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PresenceCodecTest {
    @Test void connectionKindsAndVisibilityAreNotChatMessages() throws Exception {
        var codec = new PresenceCodec();
        for (var kind : PresenceCodec.Kind.values()) for (var visibility : PresenceCodec.Visibility.values()) {
            var event = new PresenceCodec.Event(UUID.randomUUID(), UUID.randomUUID(), "Alice", kind,
                    kind == PresenceCodec.Kind.MOVE ? "paper-1" : null,
                    kind == PresenceCodec.Kind.MOVE ? "paper-2" : null, visibility);
            assertEquals(event, codec.decode(codec.encode(event)));
        }
    }
}
