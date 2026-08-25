/*
 * @license LGPLv3
 */
package com.github.ucchyocean.lc3.util;

import junit.framework.TestCase;

public class PlayerNameValidatorTest extends TestCase {

    public void testValidMinecraftNames() {
        assertTrue(PlayerNameValidator.isValidName("Player_123", 16));
        assertFalse(PlayerNameValidator.isValidName("", 16));
        assertFalse(PlayerNameValidator.isValidName("name-with-dash", 16));
        assertFalse(PlayerNameValidator.isValidName("abcdefghijklmnopq", 16));
    }

    public void testUuidReferences() {
        assertTrue(PlayerNameValidator.isValidUuidReference(
                "$123e4567-e89b-12d3-a456-426614174000"));
        assertFalse(PlayerNameValidator.isValidUuidReference("$not-a-uuid"));
        assertFalse(PlayerNameValidator.isValidUuidReference(null));
    }
}
