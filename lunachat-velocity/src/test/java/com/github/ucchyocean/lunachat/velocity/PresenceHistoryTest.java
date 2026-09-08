package com.github.ucchyocean.lunachat.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PresenceHistoryTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void initialServerConnectedUsesEventDestinationBeforePlayerCurrentServerUpdates() {
        var join = new PresenceCodec.Event(UUID.randomUUID(), UUID.randomUUID(), "Alice",
                PresenceCodec.Kind.JOIN, null, "paper-1", PresenceCodec.Visibility.UNKNOWN);

        assertEquals("paper-1", LunaChatVelocity.resolveConnectedServer(join, null));
    }

    @Test
    void publicJoinProducesOnePublicNotificationAndHiddenJoinProducesNone() {
        PresenceHistory history = new PresenceHistory();
        var publicJoin = history.connected(UUID.randomUUID(), "Alice", "lobby",
                PresenceCodec.Visibility.PUBLIC, NOW);
        var hiddenJoin = history.connected(UUID.randomUUID(), "Bob", "lobby",
                PresenceCodec.Visibility.HIDDEN, NOW);

        assertEquals(1, publicJoin.stream()
                .filter(event -> event.visibility() == PresenceCodec.Visibility.PUBLIC).count());
        assertEquals(0, hiddenJoin.stream()
                .filter(event -> event.visibility() == PresenceCodec.Visibility.PUBLIC).count());
        assertEquals(PresenceCodec.Kind.JOIN, publicJoin.getFirst().kind());
    }

    @Test
    void twoPaperPublicVanishMoveRevealDisconnectHasNoDuplicatesAndKeepsOrder() {
        PresenceHistory history = new PresenceHistory();
        UUID player = UUID.randomUUID();
        var publicEvents = new ArrayList<PresenceCodec.Event>();
        publicEvents.addAll(history.connected(player, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC, NOW));
        assertTrue(history.visibilityChanged(player, "Alice", "lobby",
                PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(1)).isEmpty());
        history.connected(player, "Alice", "main", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(2));
        publicEvents.addAll(history.visibilityChanged(player, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(3)));
        assertTrue(history.visibilityChanged(player, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(4)).isEmpty());
        publicEvents.add(history.disconnected(player, "Alice", NOW.plusSeconds(5)));

        assertEquals(List.of(PresenceCodec.Kind.JOIN, PresenceCodec.Kind.MOVE, PresenceCodec.Kind.QUIT),
                publicEvents.stream().map(PresenceCodec.Event::kind).toList());
        assertEquals("lobby", publicEvents.get(1).from());
        assertEquals("main", publicEvents.get(1).to());
    }

    @Test
    void hiddenLoginAndMovesRevealAsLoginThenOneAggregatedMove() {
        PresenceHistory history = new PresenceHistory();
        UUID player = UUID.randomUUID();
        history.connected(player, "Alice", "lobby", PresenceCodec.Visibility.HIDDEN, NOW);
        history.connected(player, "Alice", "survival", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(1));
        history.connected(player, "Alice", "main", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(2));

        var reveal = history.visibilityChanged(player, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(3));

        assertEquals(2, reveal.size());
        assertEquals(PresenceCodec.Kind.LOGIN, reveal.get(0).kind());
        assertEquals("lobby", reveal.get(0).to());
        assertEquals(PresenceCodec.Kind.MOVE, reveal.get(1).kind());
        assertEquals("lobby", reveal.get(1).from());
        assertEquals("main", reveal.get(1).to());
        assertTrue(history.visibilityChanged(player, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(4)).isEmpty());
    }

    @Test
    void previouslyPublicPlayerDoesNotReceiveFictionalLogin() {
        PresenceHistory history = new PresenceHistory();
        UUID player = UUID.randomUUID();
        history.connected(player, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC, NOW);
        history.visibilityChanged(player, "Alice", "lobby", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(1));
        history.connected(player, "Alice", "main", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(2));

        var reveal = history.visibilityChanged(player, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(3));

        assertEquals(1, reveal.size());
        assertEquals(PresenceCodec.Kind.MOVE, reveal.get(0).kind());
        assertEquals("lobby", reveal.get(0).from());
        assertEquals("main", reveal.get(0).to());
    }

    @Test
    void unknownToPublicIsNotAnExplicitReappearance() {
        PresenceHistory history = new PresenceHistory();
        UUID player = UUID.randomUUID();
        history.connected(player, "Alice", "lobby", PresenceCodec.Visibility.UNKNOWN, NOW);

        assertTrue(history.visibilityChanged(player, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC,
                false, NOW.plusSeconds(1)).isEmpty());
    }

    @Test
    void ambiguousHiddenToPublicSnapshotConvergesWithoutInventingAReappearance() {
        PresenceHistory history = new PresenceHistory();
        UUID player = UUID.randomUUID();
        history.connected(player, "Alice", "lobby", PresenceCodec.Visibility.HIDDEN, NOW);

        assertTrue(history.visibilityChanged(player, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC,
                false, NOW.plusSeconds(1)).isEmpty());
        assertTrue(history.visibilityChanged(player, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC,
                false, NOW.plusSeconds(2)).isEmpty());
    }

    @Test
    void disconnectDiscardsHistoryAndUuidStatesNeverMix() {
        PresenceHistory history = new PresenceHistory();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        history.connected(first, "Alice", "lobby", PresenceCodec.Visibility.HIDDEN, NOW);
        history.disconnected(first, "Alice", NOW.plusSeconds(1));

        assertTrue(history.visibilityChanged(first, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(2)).isEmpty());
        assertTrue(history.visibilityChanged(second, "Alice", "main",
                PresenceCodec.Visibility.PUBLIC, NOW.plusSeconds(2)).isEmpty());
    }

    @Test
    void inactiveSnapshotBeforeDisconnectDoesNotConsumeQuitHistory() {
        PresenceHistory history = new PresenceHistory();
        UUID player = UUID.randomUUID();
        history.connected(player, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC, NOW);

        // Velocity may report the player inactive one scheduler tick before it
        // invokes DisconnectEvent.
        history.discardStale(NOW.plusSeconds(1), Set.of());
        PresenceCodec.Event quit = history.disconnected(player, "Alice", NOW.plusSeconds(2));

        assertNotNull(quit);
        assertEquals(PresenceCodec.Kind.QUIT, quit.kind());
        assertEquals(PresenceCodec.Visibility.PUBLIC, quit.visibility());
        assertEquals("lobby", quit.from());
    }

    @Test
    void timeoutCompactsHiddenPathAndInactivePlayersAreDiscarded() {
        PresenceHistory history = new PresenceHistory();
        UUID active = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        history.connected(active, "Alice", "lobby", PresenceCodec.Visibility.PUBLIC, NOW);
        history.visibilityChanged(active, "Alice", "lobby", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(1));
        history.connected(active, "Alice", "main", PresenceCodec.Visibility.HIDDEN, NOW.plusSeconds(2));
        history.connected(gone, "Bob", "lobby", PresenceCodec.Visibility.HIDDEN, NOW);

        history.discardStale(NOW.plusSeconds(6 * 60 * 60 + 2), Set.of(active));

        var reveal = history.visibilityChanged(active, "Alice", "main", PresenceCodec.Visibility.PUBLIC,
                NOW.plusSeconds(6 * 60 * 60 + 3));
        assertEquals(1, reveal.size());
        assertEquals(PresenceCodec.Kind.MOVE, reveal.get(0).kind());
        assertTrue(history.visibilityChanged(gone, "Bob", "main", PresenceCodec.Visibility.PUBLIC,
                NOW.plusSeconds(6 * 60 * 60 + 3)).isEmpty());
    }
}
