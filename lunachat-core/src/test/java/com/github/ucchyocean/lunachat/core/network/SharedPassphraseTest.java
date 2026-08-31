package com.github.ucchyocean.lunachat.core.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedPassphraseTest {
    @Test void derivesStableDomainSpecific256BitKey() {
        byte[] first = SharedPassphrase.derive("correct horse battery staple");
        byte[] second = SharedPassphrase.derive("correct horse battery staple");
        byte[] different = SharedPassphrase.derive("correct horse battery stable");

        assertEquals(32, first.length);
        assertArrayEquals(first, second);
        assertFalse(Arrays.equals(first, different));
    }

    @Test void rejectsShortPassphrases() {
        assertThrows(IllegalArgumentException.class, () -> SharedPassphrase.derive("too-short"));
    }
}
