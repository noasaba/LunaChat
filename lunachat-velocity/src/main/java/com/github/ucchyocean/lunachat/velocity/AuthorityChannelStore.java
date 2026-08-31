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

/** Durable Velocity authority state. Paper STATE frames are authenticated proposals. */
final class AuthorityChannelStore {
    private static final int SCHEMA = 1;
    private final Path file;
    private final Map<ChannelId, ChannelDescriptor> channels = new HashMap<>();
    private final Map<String, Map<ChannelId, ChannelDescriptor>> proposalsByNode = new HashMap<>();
    private boolean receivedFreshProposal;

    AuthorityChannelStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("channels.properties");
        load();
    }

    synchronized List<ChannelDescriptor> snapshot() {
        return channels.values().stream().sorted(java.util.Comparator.comparing(ChannelDescriptor::name)).toList();
    }

    synchronized void applyProposal(String sourceNode, List<ChannelDescriptor> proposal) throws IOException {
        if (!receivedFreshProposal) {
            proposalsByNode.clear();
            receivedFreshProposal = true;
        }
        Map<ChannelId, ChannelDescriptor> replacement = new LinkedHashMap<>();
        for (ChannelDescriptor channel : proposal) replacement.put(channel.id(), channel);
        proposalsByNode.put(sourceNode, Map.copyOf(replacement));
        rebuildFromFreshProposals();
        save();
    }

    synchronized void removeProposal(String sourceNode) throws IOException {
        if (!receivedFreshProposal || proposalsByNode.remove(sourceNode) == null) return;
        rebuildFromFreshProposals();
        save();
    }

    private void rebuildFromFreshProposals() {
        channels.clear();
        proposalsByNode.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().values().forEach(channel -> channels.put(channel.id(), channel)));
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
            channels.put(id, new ChannelDescriptor(id, properties.getProperty(key), aliasSet,
                    Boolean.parseBoolean(properties.getProperty(prefix + "external", "false"))));
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
