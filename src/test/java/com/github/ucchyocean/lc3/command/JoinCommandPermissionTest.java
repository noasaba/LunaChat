package com.github.ucchyocean.lc3.command;

import com.github.ucchyocean.lc3.member.ChannelMemberDummy;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JoinCommandPermissionTest {

    @Test
    public void unspecifiedPerChannelSpeakPermissionDefaultsToAllowed() {
        assertTrue(JoinCommand.hasSpeakPermission(member(false, false), "global"));
    }

    @Test
    public void explicitPerChannelSpeakPermissionIsHonored() {
        assertTrue(JoinCommand.hasSpeakPermission(member(true, true), "global"));
        assertFalse(JoinCommand.hasSpeakPermission(member(true, false), "global"));
    }

    private static ChannelMemberDummy member(boolean permissionSet, boolean permitted) {
        return new ChannelMemberDummy() {
            @Override
            public boolean isPermissionSet(String node) {
                return permissionSet;
            }

            @Override
            public boolean hasPermission(String node) {
                return permitted;
            }
        };
    }
}
