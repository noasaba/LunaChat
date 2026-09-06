package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.velocitypowered.api.proxy.Player;

/** Pure bounded policy so provider absence and UNKNOWN cannot be conflated. */
final class PresenceVisibilityPolicy {
    record Decision(PresenceCodec.Visibility visibility, boolean retry) {}

    private PresenceVisibilityPolicy() {}

    static Decision resolve(PresenceVisibilityProvider provider, Player player, int attempt) {
        if (provider == null) return new Decision(PresenceCodec.Visibility.PUBLIC, false);
        PresenceCodec.Visibility visibility;
        try {
            visibility = provider.visibility(player);
        } catch (RuntimeException failure) {
            visibility = PresenceCodec.Visibility.UNKNOWN;
        }
        if (visibility == null) visibility = PresenceCodec.Visibility.UNKNOWN;
        return new Decision(visibility,
                visibility == PresenceCodec.Visibility.UNKNOWN && attempt < 3);
    }
}
