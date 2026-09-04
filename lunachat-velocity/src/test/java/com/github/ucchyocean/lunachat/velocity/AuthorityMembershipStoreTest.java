package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.*;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorityMembershipStoreTest {
    @TempDir Path directory;
    private final ChannelDescriptor channel = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
    private final UUID player = UUID.randomUUID();
    private List<ChannelDescriptor> catalog() { return List.of(channel); }
    private void seed(boolean joinable) throws Exception {
        Files.writeString(directory.resolve("membership-import.properties"), "schema=1\nsource=selected-backend\n"
                + "channel." + channel.id().value() + ".members=" + player + "\n"
                + "channel." + channel.id().value() + ".joinable=" + joinable + "\n");
    }
    @Test void importsOnceAndLeaveSurvivesRestartAndStaleJoin() throws Exception {
        seed(true);
        var store = new AuthorityMembershipStore(directory, catalog());
        Key key = new Key(channel.id(), player);
        assertTrue(store.snapshot().members().getFirst().joined());
        assertEquals("APPLIED", store.change(new Change(key, false, 1)));
        store = new AuthorityMembershipStore(directory, catalog());
        assertFalse(store.snapshot().members().getFirst().joined(), "remaining seed must never reimport");
        assertEquals("STALE", store.change(new Change(key, true, 1)), "old join cannot undo kick/leave");
        assertEquals("APPLIED", store.change(new Change(key, true, store.snapshot().version(key))));
        store = new AuthorityMembershipStore(directory, catalog());
        assertTrue(store.snapshot().members().getFirst().joined(), "explicit fresh join persists");
        assertTrue(Files.exists(directory.resolve("membership-import.properties")), "original import is preserved");
    }
    @Test void independentMembersDoNotConflictAndRetriesAreIdempotent() throws Exception {
        seed(true); var store = new AuthorityMembershipStore(directory, catalog());
        var a = new Key(channel.id(), UUID.randomUUID()); var b = new Key(channel.id(), UUID.randomUUID());
        assertEquals("APPLIED", store.change(new Change(a, true, 0)));
        assertEquals("APPLIED", store.change(new Change(b, true, 0)));
        long revision = store.snapshot().revision();
        assertEquals("APPLIED", store.change(new Change(a, true, 0)));
        assertEquals(revision, store.snapshot().revision());
    }
    @Test void restrictedChannelsDoNotGainNewMembersOrModeratorRights() throws Exception {
        seed(false); var store = new AuthorityMembershipStore(directory, catalog());
        assertEquals("JOIN_DISABLED", store.change(new Change(new Key(channel.id(), UUID.randomUUID()), true, 0)));
        assertEquals(1, store.snapshot().members().size());
    }
    @Test void invalidCanonicalImportFailsWithoutCreatingState() throws Exception {
        Files.writeString(directory.resolve("membership-import.properties"),
                "schema=1\nchannel." + ChannelId.random().value() + ".members=" + player + "\n");
        assertThrows(java.io.IOException.class, () -> new AuthorityMembershipStore(directory, catalog()));
        assertFalse(Files.exists(directory.resolve("membership-state.bin")));
    }
    @Test void corruptPersistedStateFailsClosedWithoutFallingBackToSeed() throws Exception {
        seed(true); new AuthorityMembershipStore(directory, catalog());
        Files.write(directory.resolve("membership-state.bin"), new byte[]{1, 2, 3});
        assertThrows(java.io.IOException.class, () -> new AuthorityMembershipStore(directory, catalog()));
    }

    @Test void catalogMetadataAndAdditionsPreserveMembersButReplacementFailsClosed() throws Exception {
        seed(true); var original = new AuthorityMembershipStore(directory, catalog());
        var renamed = new ChannelDescriptor(channel.id(), "global", Set.of("g"), false);
        var extra = new ChannelDescriptor(ChannelId.random(), "staff", Set.of(), false);
        var updated = new AuthorityMembershipStore(directory, List.of(renamed, extra));
        assertEquals(original.snapshot().members(), updated.snapshot().members());
        assertEquals(original.snapshot().joinable(), updated.snapshot().joinable());
        assertTrue(updated.snapshot().revision() > original.snapshot().revision());
        byte[] durable = Files.readAllBytes(directory.resolve("membership-state.bin"));
        var replacement = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
        assertThrows(java.io.IOException.class,
                () -> new AuthorityMembershipStore(directory, List.of(replacement, extra)));
        assertArrayEquals(durable, Files.readAllBytes(directory.resolve("membership-state.bin")));
    }

    @Test void failedPersistenceDoesNotAcknowledgeOrPublishMembership() throws Exception {
        seed(true); var store = new AuthorityMembershipStore(directory, catalog());
        var before = store.snapshot();
        // A nonempty directory at the destination deterministically prevents replacement.
        Files.delete(directory.resolve("membership-state.bin"));
        Files.createDirectory(directory.resolve("membership-state.bin"));
        Files.writeString(directory.resolve("membership-state.bin/block"), "test");
        assertThrows(java.io.IOException.class,
                () -> store.change(new Change(new Key(channel.id(), player), false, 1)));
        assertEquals(before, store.snapshot());
    }
}
