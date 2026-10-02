package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class NetworkPlayerDirectoryTest {
    @Test
    public void resetRemovesPlayersWhoseQuitWasMissedBeforeResynchronization() {
        NetworkPlayerDirectory directory = new NetworkPlayerDirectory();
        UUID alice = UUID.randomUUID();
        directory.apply(event(alice, "Alice", PresenceCodec.Kind.SNAPSHOT));

        directory.reset();

        assertEquals(java.util.List.of(), directory.namesStartingWith(""));
    }

    @Test
    public void snapshotRepopulatesCurrentPlayersAfterReset() {
        NetworkPlayerDirectory directory = new NetworkPlayerDirectory();
        UUID stale = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        directory.apply(event(stale, "OfflinePlayer", PresenceCodec.Kind.SNAPSHOT));

        directory.reset();
        directory.apply(event(current, "OnlinePlayer", PresenceCodec.Kind.SNAPSHOT));

        assertEquals(java.util.List.of("OnlinePlayer"), directory.namesStartingWith("online"));
        assertEquals(java.util.List.of(), directory.namesStartingWith("offline"));
    }

    private static PresenceCodec.Event event(UUID player, String name, PresenceCodec.Kind kind) {
        return new PresenceCodec.Event(UUID.randomUUID(), player, name, kind, null, "paper-1",
                PresenceCodec.Visibility.PUBLIC);
    }
}
