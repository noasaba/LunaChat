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
import java.util.Optional;
import java.util.stream.Collectors;

/** Durable, Velocity-owned channel catalog. Paper STATE frames only acknowledge it. */
final class AuthorityChannelStore {
    private static final int SCHEMA = 1;
    private final Path file;
    private final Map<ChannelId, ChannelDescriptor> channels = new HashMap<>();
    private String globalChannel = "";
    private String defaultChannel = "";
    private Set<String> forceJoinChannels = Set.of();
    record State(List<ChannelDescriptor> channels,
                 com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Settings settings) {}

    AuthorityChannelStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("channels.properties");
        load();
    }

    synchronized List<ChannelDescriptor> snapshot() {
        return channels.values().stream().sorted(java.util.Comparator.comparing(ChannelDescriptor::name)).toList();
    }
    synchronized com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Settings settings() {
        return new com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.Settings(globalChannel, defaultChannel, forceJoinChannels);
    }
    synchronized Optional<ChannelDescriptor> find(String name) {
        return channels.values().stream().filter(c -> c.name().equalsIgnoreCase(name) || c.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(name))).findFirst();
    }
    synchronized State state() { return new State(snapshot(), settings()); }
    synchronized void restore(State state) throws IOException {
        channels.clear();
        state.channels().forEach(channel -> channels.put(channel.id(), channel));
        globalChannel = state.settings().globalChannel();
        defaultChannel = state.settings().defaultChannel();
        forceJoinChannels = state.settings().forceJoinChannels();
        save();
    }
    synchronized ChannelDescriptor create(String name, boolean external) throws IOException {
        if (!name.matches("[0-9A-Za-z_-]{1,20}") || find(name).isPresent()) throw new IOException("invalid or duplicate canonical channel name");
        ChannelDescriptor created = new ChannelDescriptor(ChannelId.random(), name, Set.of(), external);
        channels.put(created.id(), created);
        try { save(); } catch (IOException failure) { channels.remove(created.id()); throw failure; }
        return created;
    }
    synchronized void delete(String name) throws IOException {
        ChannelDescriptor channel = find(name).orElseThrow(() -> new IOException("channel not found"));
        if (globalChannel.equals(channel.name()) || defaultChannel.equals(channel.name()) || forceJoinChannels.contains(channel.name())) throw new IOException("channel is referenced by authority settings");
        channels.remove(channel.id());
        try { save(); } catch (IOException failure) { channels.put(channel.id(), channel); throw failure; }
    }
    synchronized void alias(String name, String alias) throws IOException {
        ChannelDescriptor channel = find(name).orElseThrow(() -> new IOException("channel not found"));
        if (!alias.isEmpty() && (!alias.matches("[0-9A-Za-z_-]{1,20}") || find(alias).isPresent())) throw new IOException("invalid or duplicate alias");
        channels.put(channel.id(), new ChannelDescriptor(channel.id(), channel.name(), alias.isEmpty() ? Set.of() : Set.of(alias), channel.acceptsExternalMessages()));
        try { save(); } catch (IOException failure) { channels.put(channel.id(), channel); throw failure; }
    }
    synchronized void external(String name, boolean external) throws IOException {
        ChannelDescriptor channel = find(name).orElseThrow(() -> new IOException("channel not found"));
        ChannelDescriptor updated = new ChannelDescriptor(channel.id(), channel.name(), channel.aliases(), external);
        channels.put(channel.id(), updated);
        try { save(); } catch (IOException failure) { channels.put(channel.id(), channel); throw failure; }
    }
    synchronized void settings(String globalChannel, String defaultChannel, Set<String> force) throws IOException {
        if (!globalChannel.isEmpty() && find(globalChannel).isEmpty()
                || !defaultChannel.isEmpty() && find(defaultChannel).isEmpty()
                || force.stream().anyMatch(name -> find(name).isEmpty()))
            throw new IOException("settings reference an unknown canonical channel");
        String oldGlobal = this.globalChannel, oldDefault = this.defaultChannel;
        Set<String> oldForce = this.forceJoinChannels;
        this.globalChannel = canonicalName(globalChannel);
        this.defaultChannel = canonicalName(defaultChannel);
        this.forceJoinChannels = force.stream().map(this::canonicalName).collect(Collectors.toUnmodifiableSet());
        try { save(); } catch (IOException failure) {
            this.globalChannel = oldGlobal; this.defaultChannel = oldDefault; this.forceJoinChannels = oldForce; throw failure;
        }
    }
    synchronized void settings(String defaultChannel, Set<?> force) throws IOException {
        Set<String> names = force.stream().map(String::valueOf).collect(Collectors.toSet());
        settings(defaultChannel, defaultChannel, names);
    }

    private String canonicalName(String name) { return name.isEmpty() ? "" : find(name).orElseThrow().name(); }

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
        Map<ChannelId, ChannelDescriptor> previous = new HashMap<>(channels);
        channels.clear(); channels.putAll(replacement);
        try { save(); } catch (IOException failure) { channels.clear(); channels.putAll(previous); throw failure; }
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
        defaultChannel = properties.getProperty("defaultChannel", "");
        // Schema 1 originally used defaultChannel as both login default and
        // global/broadcast marker. Missing globalChannel migrates that meaning.
        globalChannel = properties.getProperty("globalChannel", defaultChannel);
        String force = properties.getProperty("forceJoinChannels", "");
        forceJoinChannels = force.isBlank() ? Set.of() : Arrays.stream(force.split(",", -1)).collect(Collectors.toUnmodifiableSet());
        if ((!globalChannel.isEmpty() && find(globalChannel).isEmpty())
                || (!defaultChannel.isEmpty() && find(defaultChannel).isEmpty())
                || forceJoinChannels.stream().anyMatch(name -> find(name).isEmpty()))
            throw new IOException("authority settings reference unknown channel");
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
        properties.setProperty("globalChannel", globalChannel);
        properties.setProperty("defaultChannel", defaultChannel);
        properties.setProperty("forceJoinChannels", String.join(",", forceJoinChannels));
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
