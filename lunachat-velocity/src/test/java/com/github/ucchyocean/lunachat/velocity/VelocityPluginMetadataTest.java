package com.github.ucchyocean.lunachat.velocity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class VelocityPluginMetadataTest {
    @Test void generatedPluginVersionMatchesMavenProductVersion() throws Exception {
        try (var stream = LunaChatVelocity.class.getResourceAsStream("/velocity-plugin.json")) {
            assertNotNull(stream);
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var version = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
            assertTrue(version.find());
            assertNotNull(System.getProperty("expected.plugin.version"));
            assertEquals(System.getProperty("expected.plugin.version"), version.group(1));
        }
    }
}
