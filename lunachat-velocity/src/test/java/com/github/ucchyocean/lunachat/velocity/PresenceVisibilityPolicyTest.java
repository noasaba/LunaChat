package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresenceVisibilityPolicyTest {
    @Test void noProviderMeansOrdinaryPlayerIsPublic() {
        var result = PresenceVisibilityPolicy.resolve(null, null);
        assertEquals(PresenceCodec.Visibility.PUBLIC, result.visibility());
        assertFalse(result.publicFallback());
    }

    @Test void onlyExplicitHiddenIsHidden() {
        var result = PresenceVisibilityPolicy.resolve(player -> PresenceCodec.Visibility.HIDDEN, null);
        assertEquals(PresenceCodec.Visibility.HIDDEN, result.visibility());
        assertFalse(result.publicFallback());
    }

    @Test void unknownImmediatelyPublishesPublicReplacement() {
        var provider = (PresenceVisibilityProvider) player -> PresenceCodec.Visibility.UNKNOWN;
        var finalState = PresenceVisibilityPolicy.resolve(provider, null);
        assertEquals(PresenceCodec.Visibility.PUBLIC, finalState.visibility());
        assertTrue(finalState.publicFallback());
    }

    @Test void apiFailureCannotMakeBothPaperAndNetworkNotificationsDisappear() {
        PresenceVisibilityProvider broken = player -> { throw new LinkageError("old API"); };
        var finalState = PresenceVisibilityPolicy.resolve(broken, null);
        assertEquals(PresenceCodec.Visibility.PUBLIC, finalState.visibility());
        assertTrue(finalState.publicFallback());
    }
}
