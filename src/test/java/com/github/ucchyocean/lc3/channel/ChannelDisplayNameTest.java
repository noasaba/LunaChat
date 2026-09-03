package com.github.ucchyocean.lc3.channel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChannelDisplayNameTest {
    @Test public void minecraftNamespaceDoesNotAppearInDisplayName() {
        assertEquals("noa_berry", Channel.displayNameForOtherSource("noa_berry", "lunachat.minecraft"));
    }

    @Test public void externalSourceRemainsIdentifiableForLegacyCallers() {
        assertEquals("Noa@web", Channel.displayNameForOtherSource("Noa", "web"));
    }
}
