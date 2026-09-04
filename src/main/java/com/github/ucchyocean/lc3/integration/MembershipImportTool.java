package com.github.ucchyocean.lc3.integration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Offline, explicit single-source export. Never modifies a Paper data file. */
public final class MembershipImportTool {
    private MembershipImportTool() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException(
                "Usage: MembershipImportTool <selected-Paper-channels-dir> <Velocity-channels.properties> <new-output-file>");
        export(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("Membership seed exported. Install only before first membership-capable authority startup.");
    }

    static void export(Path source, Path catalogFile, Path output) throws IOException {
        Properties catalog = new Properties();
        try (var input = Files.newInputStream(catalogFile)) { catalog.load(input); }
        if (!"1".equals(catalog.getProperty("schema"))) throw new IOException("unsupported catalog schema");
        Properties seed = new Properties(); seed.setProperty("schema", "1");
        seed.setProperty("source", source.toAbsolutePath().normalize().toString());
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        int imported = 0;
        for (String key : new TreeSet<>(catalog.stringPropertyNames())) {
            if (!key.startsWith("channel.") || !key.endsWith(".name")) continue;
            String id = key.substring(8, key.length() - 5);
            if (!UUID.fromString(id).toString().equals(id)) throw new IOException("noncanonical UUID");
            String name = catalog.getProperty(key);
            if (!name.matches("[A-Za-z0-9_-]+")) throw new IOException("channel name needs explicit migration review");
            Path file = source.resolve(name + ".yml");
            if (!Files.exists(file)) continue;
            final Map<?, ?> data;
            try (var reader = Files.newBufferedReader(file)) {
                Object loaded = yaml.load(reader);
                if (!(loaded instanceof Map<?, ?> map)) throw new IOException("invalid channel YAML");
                data = map;
            } catch (RuntimeException malformed) { throw new IOException("invalid channel YAML", malformed); }
            if (!id.equals(data.get("channel_id"))) throw new IOException("canonical channel ID mismatch: " + name);
            Set<String> members = playerIds(data.get("members"));
            Set<String> moderators = playerIds(data.get("moderator"));
            Set<String> banned = playerIds(data.get("banned"));
            Set<String> muted = playerIds(data.get("muted"));
            String prefix = "channel." + id + ".";
            seed.setProperty(prefix + "members", String.join(",", members));
            // Only a plainly open channel permits a fresh voluntary join. An
            // imported private/world/banned channel retains its access state.
            boolean open = Boolean.TRUE.equals(data.get("visible")) && "".equals(data.get("password"))
                    && Boolean.FALSE.equals(data.get("world")) && banned.isEmpty();
            seed.setProperty(prefix + "joinable", Boolean.toString(open));
            seed.setProperty(prefix + "moderators", String.join(",", moderators));
            seed.setProperty(prefix + "banned", String.join(",", banned));
            seed.setProperty(prefix + "muted", String.join(",", muted));
            seed.setProperty(prefix + "password", string(data.get("password")));
            seed.setProperty(prefix + "visible", Boolean.toString(Boolean.TRUE.equals(data.get("visible"))));
            seed.setProperty(prefix + "world", Boolean.toString(Boolean.TRUE.equals(data.get("world"))));
            seed.setProperty(prefix + "ban_expires", expiry(data.get("ban_expires"), banned));
            seed.setProperty(prefix + "mute_expires", expiry(data.get("mute_expires"), muted));
            imported++;
        }
        if (imported == 0) throw new IOException("no canonical source channels found; refusing empty migration");
        try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            seed.store(stream, "One-time selected-backend migration. Never delete membership-state.bin to reimport.");
        }
    }

    private static Set<String> playerIds(Object value) throws IOException {
        if (value == null) return Set.of();
        if (!(value instanceof List<?> list)) throw new IOException("invalid player list");
        Set<String> result = new LinkedHashSet<>();
        for (Object member : list) {
            if (!(member instanceof String text) || !text.startsWith("$")) throw new IOException("non-player member requires explicit migration");
            String uuid = text.substring(1);
            if (!UUID.fromString(uuid).toString().equals(uuid) || !result.add(uuid)) throw new IOException("invalid or duplicate player UUID");
        }
        return result;
    }
    private static String expiry(Object value, Set<String> allowed) throws IOException {
        if (!(value instanceof Map<?, ?> values)) return "";
        List<String> result = new ArrayList<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String member) || !member.startsWith("$") || !allowed.contains(member.substring(1))
                    || !(entry.getValue() instanceof Number time) || time.longValue() <= 0) throw new IOException("invalid expiry");
            result.add(member.substring(1) + "@" + time.longValue());
        }
        return String.join(",", result);
    }
    private static String string(Object value) throws IOException {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > 64) throw new IOException("invalid password"); return text;
    }
}
