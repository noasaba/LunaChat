package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.velocitypowered.api.proxy.Player;

/** Optional current-state visibility query. It requires no LunaChat-specific transition callback. */
@FunctionalInterface
public interface PresenceVisibilityProvider {
    PresenceCodec.Visibility visibility(Player player);
}
