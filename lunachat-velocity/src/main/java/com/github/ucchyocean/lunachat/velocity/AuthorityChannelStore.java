package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Durable, Velocity-owned channel catalog. Paper STATE frames only acknowledge it. */
final class AuthorityChannelStore {
    private static final int SCHEMA = 1;
    private final Path file;
    private final Map<ChannelId, ChannelDescriptor> channels = new HashMap<>();

    AuthorityChannelStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("channels.properties");
        load();
    }

    synchronized List<ChannelDescriptor> snapshot() {
        return channels.values().stream().sorted(java.util.Comparator.comparing(ChannelDescriptor::name)).toList();
    }

    Path directory() { return file.getParent(); }

    /** Replaces the Velocity-owned catalog; Paper nodes never call this method. */
    synchronized void replace(List<ChannelDescriptor> proposal) throws IOException {
        Map<ChannelId, ChannelDescriptor> replacement = new LinkedHashMap<>();
        java.util.Set<String> lookupNames = new java.util.HashSet<>();
        for (ChannelDescriptor channel : proposal) {
            if (replacement.put(channel.id(), channel) != null
                    || !lookupNames.add(channel.name().toLowerCase())) {
                throw new IOException("duplicate authority channel identity");
            }
            for (String alias : channel.aliases()) {
                if (!lookupNames.add(alias.toLowerCase())) throw new IOException("duplicate authority channel alias");
            }
        }
        channels.clear();
        channels.putAll(replacement);
        save();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        int schema = Integer.parseInt(properties.getProperty("schema", "0"));
        if (schema > SCHEMA) throw new IOException("future channel authority schema " + schema);
        if (schema != SCHEMA) throw new IOException("unsupported channel authority schema " + schema);
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("channel.") || !key.endsWith(".name")) continue;
            String idText = key.substring(8, key.length() - 5);
            ChannelId id = new ChannelId(idText);
            String prefix = "channel." + idText + ".";
            String aliases = properties.getProperty(prefix + "aliases", "");
            Set<String> aliasSet = aliases.isBlank() ? Set.of() : Arrays.stream(aliases.split(",", -1))
                    .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
            ChannelDescriptor descriptor = new ChannelDescriptor(id, properties.getProperty(key), aliasSet,
                    Boolean.parseBoolean(properties.getProperty(prefix + "external", "false")));
            if (channels.put(id, descriptor) != null) throw new IOException("duplicate channel id");
        }
        validateLoadedCatalog();
    }

    private void validateLoadedCatalog() throws IOException {
        java.util.Set<String> lookupNames = new java.util.HashSet<>();
        for (ChannelDescriptor channel : channels.values()) {
            if (!lookupNames.add(channel.name().toLowerCase())) throw new IOException("duplicate channel name");
            for (String alias : channel.aliases()) {
                if (!lookupNames.add(alias.toLowerCase())) throw new IOException("duplicate channel alias");
            }
        }
    }

    private void save() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schema", Integer.toString(SCHEMA));
        for (ChannelDescriptor channel : channels.values()) {
            String prefix = "channel." + channel.id().value() + ".";
            properties.setProperty(prefix + "name", channel.name());
            properties.setProperty(prefix + "aliases", String.join(",", channel.aliases()));
            properties.setProperty(prefix + "external", Boolean.toString(channel.acceptsExternalMessages()));
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "LunaChat Velocity authority state; schema is fail-closed");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
