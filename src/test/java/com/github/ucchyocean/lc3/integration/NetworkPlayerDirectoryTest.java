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

        assertEquals(java.util.List.of(), directory.namesStartingWith("", true));
    }

    @Test
    public void snapshotRepopulatesCurrentPlayersAfterReset() {
        NetworkPlayerDirectory directory = new NetworkPlayerDirectory();
        UUID stale = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        directory.apply(event(stale, "OfflinePlayer", PresenceCodec.Kind.SNAPSHOT));

        directory.reset();
        directory.apply(event(current, "OnlinePlayer", PresenceCodec.Kind.SNAPSHOT));

        assertEquals(java.util.List.of("OnlinePlayer"), directory.namesStartingWith("online", true));
        assertEquals(java.util.List.of(), directory.namesStartingWith("offline", true));
    }

    @Test
    public void expiredAuthorityLeaseHidesAndDiscardsCachedPlayers() {
        NetworkPlayerDirectory directory = new NetworkPlayerDirectory();
        directory.apply(event(UUID.randomUUID(), "OfflinePlayer", PresenceCodec.Kind.SNAPSHOT));
        long lastFrame = 1_000_000L;
        assertEquals(java.util.List.of("OfflinePlayer"), directory.namesStartingWith("",
                PaperNetworkEdge.authorityLeaseValid(lastFrame, lastFrame + 10_000L)));
        assertEquals(java.util.List.of(), directory.namesStartingWith("",
                PaperNetworkEdge.authorityLeaseValid(lastFrame, lastFrame + 25_001L)));
        assertEquals(java.util.List.of(), directory.namesStartingWith("", true));
    }

    @Test
    public void quitRemovesCandidateWithoutWaitingForFullSnapshot() {
        NetworkPlayerDirectory directory = new NetworkPlayerDirectory();
        UUID player = UUID.randomUUID();
        directory.apply(event(player, "Alice", PresenceCodec.Kind.SNAPSHOT));
        directory.apply(event(player, "Alice", PresenceCodec.Kind.QUIT));
        assertEquals(java.util.List.of(), directory.namesStartingWith("a", true));
    }

    @Test
    public void hiddenSnapshotRemovesPreviouslyPublicCandidate() {
        NetworkPlayerDirectory directory = new NetworkPlayerDirectory();
        UUID player = UUID.randomUUID();
        directory.apply(event(player, "Alice", PresenceCodec.Kind.SNAPSHOT));
        directory.apply(new PresenceCodec.Event(UUID.randomUUID(), player, "Alice", PresenceCodec.Kind.SNAPSHOT,
                null, "paper-1", PresenceCodec.Visibility.HIDDEN));
        assertEquals(java.util.List.of(), directory.namesStartingWith("", true));
    }

    private static PresenceCodec.Event event(UUID player, String name, PresenceCodec.Kind kind) {
        return new PresenceCodec.Event(UUID.randomUUID(), player, name, kind, null, "paper-1",
                PresenceCodec.Visibility.PUBLIC);
    }
}
