package com.github.ucchyocean.lunachat.core.network;

import com.github.ucchyocean.lunachat.api.*;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthoritySnapshotCodecTest {
    @Test void roundTripPreservesMembersAndExitTombstones() throws Exception {
        var channel = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
        var key = new Key(channel.id(), UUID.randomUUID());
        var player = UUID.randomUUID();
        var policy = new Policy(channel.id(), Set.of(player), Set.of(player), Set.of(player),
                Map.of(player, 123L), Map.of(player, 456L), "private", false, true);
        var state = new Snapshot(3, List.of(channel), List.of(new Member(key, false, 3)), Set.of(channel.id()), List.of(policy));
        var codec = new AuthoritySnapshotCodec();
        assertEquals(state, codec.decode(codec.encode(state)));
        var change = new Change(key, true, 3);
        assertEquals(change, codec.decodeChange(codec.encodeChange(change)));
    }
    @Test void roundTripKeepsGlobalAndLoginDefaultIndependent() throws Exception {
        var global = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
        var defaultChannel = new ChannelDescriptor(ChannelId.random(), "social", Set.of(), false);
        var settings = new Settings("global", "social", Set.of("social"));
        var state = new Snapshot(1, List.of(global, defaultChannel), List.of(), Set.of(global.id(), defaultChannel.id()), List.of(), settings);
        assertEquals(state, new AuthoritySnapshotCodec().decode(new AuthoritySnapshotCodec().encode(state)));
    }
    @Test void rejectsLegacyCatalogAndMalformedState() throws Exception {
        var codec = new AuthoritySnapshotCodec();
        assertThrows(java.io.IOException.class, () -> codec.decode(new ChannelStateCodec().encode(List.of())));
        assertThrows(java.io.IOException.class, () -> codec.decodeChange(new byte[42]));
        var channel = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
        var entry = new Member(new Key(channel.id(), UUID.randomUUID()), true, 1);
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(1, List.of(channel), List.of(entry, entry), Set.of()));
    }
}
