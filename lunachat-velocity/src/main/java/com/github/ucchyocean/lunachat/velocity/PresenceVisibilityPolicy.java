package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import com.velocitypowered.api.proxy.Player;

/** Pure policy so provider absence, explicit HIDDEN, and failed UNKNOWN stay distinct. */
final class PresenceVisibilityPolicy {
    record Decision(PresenceCodec.Visibility visibility, boolean publicFallback) {}

    private PresenceVisibilityPolicy() {}

    static Decision resolve(PresenceVisibilityProvider provider, Player player) {
        if (provider == null) return new Decision(PresenceCodec.Visibility.PUBLIC, false);
        PresenceCodec.Visibility visibility;
        try {
            visibility = provider.visibility(player);
        } catch (RuntimeException | LinkageError failure) {
            visibility = PresenceCodec.Visibility.UNKNOWN;
        }
        if (visibility == null) visibility = PresenceCodec.Visibility.UNKNOWN;
        if (visibility == PresenceCodec.Visibility.UNKNOWN) {
            // Paper has already suppressed its local event once the authenticated
            // network edge is ready. Falling back immediately also covers a
            // short-lived login followed by disconnect; delayed retries could
            // otherwise miss both events after the player becomes inactive.
            return new Decision(PresenceCodec.Visibility.PUBLIC, true);
        }
        return new Decision(visibility, false);
    }
}
