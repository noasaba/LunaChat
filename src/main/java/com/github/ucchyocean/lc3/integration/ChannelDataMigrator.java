package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lc3.util.YamlConfig;
import com.github.ucchyocean.lunachat.api.ChannelId;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Idempotent v0 to v1 stable-channel-ID migration with pre-migration backup. */
public final class ChannelDataMigrator {
    public static final int CURRENT_SCHEMA = 1;
    private ChannelDataMigrator() {}

    public static void migrate(File dataFolder) throws IOException {
        File channels = new File(dataFolder, "channels");
        if (!channels.isDirectory()) return;
        File backup = new File(dataFolder, "migration-backup-v0/channels");
        File[] files = channels.listFiles((directory, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) migrateFile(file, backup);
    }

    static void migrateFile(File file, File backupFolder) throws IOException {
        try {
            YamlConfig yaml;
            try (FileInputStream input = new FileInputStream(file)) { yaml = YamlConfig.load(input); }
            int schema = yaml.getInt("schema_version", 0);
            if (schema > CURRENT_SCHEMA) throw new IOException("Future channel schema " + schema + " in " + file.getName());
            String id = yaml.getString("channel_id");
            if (schema == CURRENT_SCHEMA && id != null) {
                new ChannelId(id);
                return;
            }
            Files.createDirectories(backupFolder.toPath());
            File backup = new File(backupFolder, file.getName());
            if (!backup.exists()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            yaml.set("schema_version", CURRENT_SCHEMA);
            yaml.set("channel_id", id == null ? ChannelId.random().value() : new ChannelId(id).value());
            if (!yaml.contains("accepts_external_messages")) yaml.set("accepts_external_messages", false);
            yaml.save(file);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid channel data in " + file.getName(), invalid);
        }
    }
}
