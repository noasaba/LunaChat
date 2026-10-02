package com.github.ucchyocean.lc3.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class UtilityUrlTest {
    private static final String URL = "https://example.invalid/image.png?a=1&b=2#abc";

    @Test public void allowedColorsDoNotChangePlayerMessageUrl() {
        assertEquals("§aAlice " + URL + " §rhello",
                Utility.replaceColorCodeOutsideUrls("&aAlice " + URL + " &rhello"));
    }

    @Test public void removingColorsForUnprivilegedPlayerDoesNotChangeUrl() {
        assertEquals("Alice " + URL + " hello",
                Utility.stripColorCodeOutsideUrls("&aAlice " + URL + " §chello"));
    }

    @Test public void strippingPreservesMultipleUrlsAndHandlesNull() {
        assertNull(Utility.stripColorCodeOutsideUrls(null));
        assertEquals(URL + " https://example.invalid/?c=3",
                Utility.stripColorCodeOutsideUrls(URL + " &chttps://example.invalid/?c=3"));
    }
}
