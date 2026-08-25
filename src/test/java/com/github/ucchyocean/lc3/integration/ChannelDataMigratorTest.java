package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lc3.util.YamlConfig;
import com.github.ucchyocean.lunachat.api.ChannelId;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ChannelDataMigratorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void migrationBacksUpAndIsIdempotent() throws Exception {
        File data = temporary.newFolder("data");
        File channels = new File(data, "channels");
        assertTrue(channels.mkdirs());
        File channel = new File(channels, "global.yml");
        Files.writeString(channel.toPath(), "name: global\nvisible: true\n", StandardCharsets.UTF_8);

        ChannelDataMigrator.migrate(data);
        YamlConfig first = YamlConfig.load(channel);
        String stableId = first.getString("channel_id");
        assertEquals(stableId, new ChannelId(stableId).value());
        assertEquals(1, first.getInt("schema_version", 0));
        assertFalse(first.getBoolean("accepts_external_messages", true));
        File backup = new File(data, "migration-backup-v0/channels/global.yml");
        assertTrue(backup.isFile());
        String backupContent = Files.readString(backup.toPath());

        ChannelDataMigrator.migrate(data);
        assertEquals(stableId, YamlConfig.load(channel).getString("channel_id"));
        assertEquals(backupContent, Files.readString(backup.toPath()));
    }

    @Test public void futureSchemaIsRejectedWithoutRewrite() throws Exception {
        File data = temporary.newFolder("future");
        File channels = new File(data, "channels");
        assertTrue(channels.mkdirs());
        File channel = new File(channels, "future.yml");
        String original = "schema_version: 999\nchannel_id: 00000000-0000-0000-0000-000000000001\n";
        Files.writeString(channel.toPath(), original, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ChannelDataMigrator.migrate(data));
        assertEquals(original, Files.readString(channel.toPath()));
    }

    @Test public void malformedYamlIsRejectedWithoutRewrite() throws Exception {
        File data = temporary.newFolder("malformed");
        File channels = new File(data, "channels");
        assertTrue(channels.mkdirs());
        File channel = new File(channels, "broken.yml");
        String original = "name: [unterminated\n";
        Files.writeString(channel.toPath(), original, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ChannelDataMigrator.migrate(data));
        assertEquals(original, Files.readString(channel.toPath()));
    }
}
