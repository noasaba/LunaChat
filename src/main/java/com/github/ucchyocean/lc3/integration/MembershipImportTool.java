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
            // Restricted channels need a richer access-policy migration. Never
            // turn a password/world/ban restricted channel into an open replica.
            if (!Boolean.TRUE.equals(data.get("visible")) || !"".equals(data.get("password"))
                    || !Boolean.FALSE.equals(data.get("world")) || !emptyList(data.get("banned")))
                throw new IOException("restricted channel requires explicit access-policy migration: " + name);
            if (!(data.get("members") instanceof List<?> members)) throw new IOException("invalid members list");
            Set<String> ids = new LinkedHashSet<>();
            for (Object member : members) {
                if (!(member instanceof String value) || !value.startsWith("$"))
                    throw new IOException("non-player member requires explicit migration");
                String uuid = value.substring(1);
                if (!UUID.fromString(uuid).toString().equals(uuid) || !ids.add(uuid))
                    throw new IOException("invalid or duplicate member UUID");
            }
            seed.setProperty("channel." + id + ".members", String.join(",", ids));
            seed.setProperty("channel." + id + ".joinable", "true");
            imported++;
        }
        if (imported == 0) throw new IOException("no canonical source channels found; refusing empty migration");
        try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            seed.store(stream, "One-time selected-backend migration. Never delete membership-state.bin to reimport.");
        }
    }

    private static boolean emptyList(Object value) { return value instanceof List<?> list && list.isEmpty(); }
}
