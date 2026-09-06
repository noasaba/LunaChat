package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.velocitypowered.api.proxy.Player;

/** Optional SVSync (or equivalent) seam. No provider means normal players are PUBLIC. */
@FunctionalInterface
public interface PresenceVisibilityProvider {
    PresenceCodec.Visibility visibility(Player player);
}
