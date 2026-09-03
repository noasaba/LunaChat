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
