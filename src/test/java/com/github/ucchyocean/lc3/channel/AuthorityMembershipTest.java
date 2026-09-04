package com.github.ucchyocean.lc3.channel;

import com.github.ucchyocean.lc3.LunaChatConfig;
import com.github.ucchyocean.lc3.LunaChatStandalone;
import com.github.ucchyocean.lc3.member.ChannelMemberOther;
import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class AuthorityMembershipTest {
    @Test public void fullStateConvergesIndependentReplicasAndRestoresOfflineMembers() throws Exception {
        var plugin = new LunaChatStandalone(Files.createTempDirectory("lunachat-replicas-test").toFile());
        plugin.onEnable();
        var role = LunaChatConfig.class.getDeclaredField("integrationRole");
        role.setAccessible(true); role.set(plugin.getLunaChatConfig(), "network_edge");
        var first = new ChannelManager(); var second = new ChannelManager();
        var descriptor = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
        var key = new com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Key(
                descriptor.id(), java.util.UUID.randomUUID());
        var joined = new com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Snapshot(
                1, List.of(descriptor), List.of(new com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Member(
                        key, true, 1)), Set.of(descriptor.id()));
        second.applyAuthoritySnapshot(joined); first.applyAuthoritySnapshot(joined);
        assertEquals(first.getChannel("global").getMembers(), second.getChannel("global").getMembers());
        assertEquals("$" + key.player(), first.getChannel("global").getMembers().getFirst().toString());
        first.applyAuthoritySnapshot(joined);
        assertEquals(1, first.getChannel("global").getMembers().size());
        var left = new com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Snapshot(
                2, List.of(descriptor), List.of(new com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Member(
                        key, false, 2)), Set.of(descriptor.id()));
        first.applyAuthoritySnapshot(left); second.applyAuthoritySnapshot(left);
        var reconnected = new ChannelManager(); reconnected.applyAuthoritySnapshot(left);
        assertTrue(first.getChannel("global").getMembers().isEmpty());
        assertTrue(second.getChannel("global").getMembers().isEmpty());
        assertTrue(reconnected.getChannel("global").getMembers().isEmpty());
    }
    @Test public void identicalCatalogReapplicationPreservesLocalMembership() throws Exception {
        var plugin = new LunaChatStandalone(Files.createTempDirectory("lunachat-membership-test").toFile());
        plugin.onEnable();
        var role = LunaChatConfig.class.getDeclaredField("integrationRole");
        role.setAccessible(true);
        role.set(plugin.getLunaChatConfig(), "network_edge");
        var manager = new ChannelManager();
        var descriptor = new ChannelDescriptor(ChannelId.random(), "global", Set.of(), true);
        manager.applyAuthoritySnapshot(List.of(descriptor));
        var channel = manager.getChannel("global");
        var member = new ChannelMemberOther("online-player");
        channel.getMembers().add(member);
        channel.getHided().add(member);
        manager.applyAuthoritySnapshot(List.of(descriptor));
        assertEquals(List.of(member), manager.getChannel("global").getMembers());
        assertEquals(List.of(member), manager.getChannel("global").getHided());
        assertEquals(descriptor.id(), manager.getChannel("global").getChannelId());
    }
}
