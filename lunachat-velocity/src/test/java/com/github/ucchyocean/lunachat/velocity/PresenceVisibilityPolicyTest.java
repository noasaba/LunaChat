package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresenceVisibilityPolicyTest {
    @Test void noProviderMeansOrdinaryPlayerIsPublic() {
        var result = PresenceVisibilityPolicy.resolve(null, null, 0);
        assertEquals(PresenceCodec.Visibility.PUBLIC, result.visibility());
        assertFalse(result.retry());
    }

    @Test void onlyExplicitHiddenIsHidden() {
        var result = PresenceVisibilityPolicy.resolve(player -> PresenceCodec.Visibility.HIDDEN, null, 0);
        assertEquals(PresenceCodec.Visibility.HIDDEN, result.visibility());
        assertFalse(result.retry());
    }

    @Test void unknownRetriesThreeTimesThenPublishesUnknownForSuppression() {
        var provider = (PresenceVisibilityProvider) player -> PresenceCodec.Visibility.UNKNOWN;
        assertTrue(PresenceVisibilityPolicy.resolve(provider, null, 0).retry());
        assertTrue(PresenceVisibilityPolicy.resolve(provider, null, 2).retry());
        var finalState = PresenceVisibilityPolicy.resolve(provider, null, 3);
        assertEquals(PresenceCodec.Visibility.UNKNOWN, finalState.visibility());
        assertFalse(finalState.retry());
    }
}
