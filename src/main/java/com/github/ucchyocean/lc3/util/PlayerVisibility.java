package com.github.ucchyocean.lc3.util;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.ucchyocean.lc3.member.ChannelMember;
import com.github.ucchyocean.lc3.member.ChannelMemberBukkit;

/** Viewer-specific player visibility policy for the Paper implementation. */
public final class PlayerVisibility {

    private PlayerVisibility() {
    }

    /** Non-player senders retain the historical, unrestricted behavior. */
    public static boolean isVisibleTo(ChannelMember sender, ChannelMember target) {
        if (!(sender instanceof ChannelMemberBukkit senderBukkit)
                || senderBukkit.getPlayer() == null) {
            return true;
        }
        if (!(target instanceof ChannelMemberBukkit targetBukkit)) {
            return true;
        }
        Player viewer = senderBukkit.getPlayer();
        Player targetPlayer = targetBukkit.getPlayer();
        if (targetPlayer == null) {
            return false;
        }
        return viewer.equals(targetPlayer) || viewer.canSee(targetPlayer);
    }

    /** Returns online names visible to the supplied player, or all names for console. */
    public static List<String> getVisibleOnlinePlayerNames(ChannelMember sender) {
        List<String> names = new ArrayList<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (isVisibleTo(sender, ChannelMember.getChannelMember(target))) {
                names.add(target.getName());
            }
        }
        return names;
    }
}
