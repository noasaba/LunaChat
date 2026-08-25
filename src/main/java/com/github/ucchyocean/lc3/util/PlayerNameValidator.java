/*
 * @license LGPLv3
 */
package com.github.ucchyocean.lc3.util;

import java.util.UUID;

/** Fast, side-effect-free validation before server profile lookups. */
public final class PlayerNameValidator {

    public static final int DEFAULT_MAX_LENGTH = 16;

    private PlayerNameValidator() {
    }

    public static boolean isValidName(String name, int maxLength) {
        if ( name == null || name.isEmpty() || name.length() > maxLength ) return false;
        for ( int i = 0; i < name.length(); i++ ) {
            char character = name.charAt(i);
            if ( !(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '_' ) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidUuidReference(String value) {
        if ( value == null || !value.startsWith("$") ) return false;
        try {
            UUID.fromString(value.substring(1));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
