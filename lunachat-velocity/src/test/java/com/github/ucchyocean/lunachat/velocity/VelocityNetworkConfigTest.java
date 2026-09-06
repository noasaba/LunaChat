package com.github.ucchyocean.lunachat.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VelocityNetworkConfigTest {
    @TempDir Path directory;

    @Test void migratesV1SequentiallyAndPreservesCommentsUnknownKeysAndSecrets() throws Exception {
        Path file = directory.resolve("network.properties");
        String legacy = "# operator comment\nschema=1\nsharePass=keep-this-secret\nmaxPending=12\n"
                + "dedupCapacity=34\nrole=network_authority\nserverId=old-proxy\ncustom.integration=value\n";
        Files.writeString(file, legacy);
        var config = new VelocityNetworkConfig(directory).load();
        String migrated = Files.readString(file);
        assertEquals("2", config.getProperty("config-version"));
        assertEquals("34", config.getProperty("receiptCapacity"));
        assertEquals("keep-this-secret", config.getProperty("sharePass"));
        assertEquals("value", config.getProperty("custom.integration"));
        assertTrue(migrated.contains("# operator comment"));
        assertTrue(migrated.contains("# migrated: schema=1"));
        assertTrue(migrated.contains("# migrated: dedupCapacity=34"));
        assertTrue(migrated.contains("# migrated: role=network_authority"));
        assertTrue(migrated.contains("# migrated: serverId=old-proxy"));
        assertNull(config.getProperty("role"));
        assertNull(config.getProperty("serverId"));
        assertEquals(legacy, Files.readString(directory.resolve("network.properties.config-v1.bak")));
    }

    @Test void migrationIsIdempotent() throws Exception {
        Files.writeString(directory.resolve("network.properties"), "schema=1\nsharePass=123456789012\n");
        var migration = new VelocityNetworkConfig(directory); migration.load();
        String once = Files.readString(directory.resolve("network.properties"));
        migration.load();
        assertEquals(once, Files.readString(directory.resolve("network.properties")));
    }

    @Test void rejectsFutureAndCorruptVersionsWithoutChangingOriginal() throws Exception {
        Path file = directory.resolve("network.properties");
        Files.writeString(file, "# future\nconfig-version=999\nsharePass=123456789012\n");
        String future = Files.readString(file);
        assertThrows(IOException.class, () -> new VelocityNetworkConfig(directory).load());
        assertEquals(future, Files.readString(file));
        Files.writeString(file, "schema=1\nsharePass=123456789012\nmaxPending=broken\n");
        String corrupt = Files.readString(file);
        assertThrows(IOException.class, () -> new VelocityNetworkConfig(directory).load());
        assertEquals(corrupt, Files.readString(file));
    }

    @Test void legacySharedSecretIsKeptInsteadOfInventingSharePass() throws Exception {
        Files.writeString(directory.resolve("network.properties"),
                "schema=1\nsharedSecret=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\n");
        var config = new VelocityNetworkConfig(directory).load();
        assertEquals("", config.getProperty("sharePass"));
        assertEquals("QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=", config.getProperty("sharedSecret"));
    }

    @Test void migratesKnownUnversionedLayoutThroughEveryVersion() throws Exception {
        Path file = directory.resolve("network.properties");
        String legacy = "# pre-version config\nsharePass=123456789012\nmaxPending=22\n"
                + "dedupCapacity=44\ncustom.integration=preserved\n";
        Files.writeString(file, legacy);
        var migration = new VelocityNetworkConfig(directory);
        var config = migration.load();
        String once = Files.readString(file);
        assertEquals("2", config.getProperty("config-version"));
        assertEquals("44", config.getProperty("receiptCapacity"));
        assertEquals("preserved", config.getProperty("custom.integration"));
        assertTrue(once.contains("# migrated: schema=1"));
        assertEquals(legacy, Files.readString(directory.resolve("network.properties.config-v0.bak")));
        migration.load();
        assertEquals(once, Files.readString(file));
    }

    @Test void rejectsEmptyUnknownOnlyAndCorruptUnversionedFilesWithoutMutation() throws Exception {
        Path file = directory.resolve("network.properties");
        for (String invalid : new String[] {
                "",
                "custom.integration=value\n",
                "sharePass=short\nmaxPending=12\n",
                "sharePass=123456789012\nmaxPending=broken\n",
                "sharedSecret=not-base64\nmaxPending=12\n"
        }) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new VelocityNetworkConfig(directory).load());
            assertEquals(invalid, Files.readString(file));
            assertFalse(Files.exists(directory.resolve("network.properties.config-v0.bak")));
        }
    }
}
