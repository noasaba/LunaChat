package com.github.ucchyocean.lunachat.velocity;

import java.io.IOException;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Base64;

/** Line-preserving migration for the operator-edited Velocity configuration. */
final class VelocityNetworkConfig {
    static final int CURRENT_VERSION = 2;
    private final Path file;

    VelocityNetworkConfig(Path directory) { file = directory.resolve("network.properties"); }

    Properties load() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            String fresh = "# LunaChat Velocity network configuration\n"
                    + "# Set the same passphrase in every Paper integration.sharePass.\n"
                    + "config-version=2\nsharePass=\nmaxPending=256\nreceiptCapacity=4096\n";
            validate(fresh); writeAtomic(fresh); return properties(fresh);
        }
        String original = Files.readString(file, StandardCharsets.UTF_8);
        int version = version(original);
        if (version > CURRENT_VERSION) throw new IOException("future network config version " + version);
        if (version < 0) throw new IOException("unsupported network config version " + version);
        int originalVersion = version;
        String migrated = original;
        while (version < CURRENT_VERSION) {
            migrated = switch (version) {
                case 0 -> migrate0To1(migrated);
                case 1 -> migrate1To2(migrated);
                default -> throw new IOException("missing migration from network config version " + version);
            };
            version++;
        }
        validate(migrated);
        if (!migrated.equals(original)) {
            Path backup = file.resolveSibling(file.getFileName() + ".config-v" + originalVersion + ".bak");
            if (!Files.exists(backup)) Files.copy(file, backup);
            writeAtomic(migrated);
        }
        return properties(migrated);
    }

    private static int version(String text) throws IOException {
        Properties values = properties(text);
        String current = values.getProperty("config-version");
        if (current != null) return integer(current, "config-version");
        String legacy = values.getProperty("schema");
        if (legacy != null) return integer(legacy, "schema");
        validateUnversionedLegacy(values, text);
        return 0;
    }

    private static void validateUnversionedLegacy(Properties values, String text) throws IOException {
        if (text.isBlank() || values.isEmpty()) throw new IOException("empty unversioned network config");
        boolean hasPassphrase = values.containsKey("sharePass");
        boolean hasEncodedSecret = values.containsKey("sharedSecret");
        if (hasPassphrase == hasEncodedSecret) {
            throw new IOException("ambiguous unversioned network config: exactly one legacy secret key is required");
        }
        if (hasPassphrase && values.getProperty("sharePass", "").trim().length() < 12) {
            throw new IOException("sharePass must contain at least 12 characters");
        }
        if (hasEncodedSecret && values.getProperty("sharedSecret", "").isBlank()) {
            throw new IOException("legacy sharedSecret is empty");
        }
        if (hasEncodedSecret) validateEncodedSecret(values.getProperty("sharedSecret"));
        if (values.containsKey("maxPending")) bounded(values, "maxPending", 256);
        if (values.containsKey("dedupCapacity")) bounded(values, "dedupCapacity", 4096);
    }

    private static String migrate0To1(String text) {
        StringBuilder result = new StringBuilder(text);
        if (!text.endsWith("\n") && !text.endsWith("\r")) result.append('\n');
        result.append("# Added by LunaChat: identifies the validated legacy Velocity config layout.\n")
                .append("schema=1\n");
        return result.toString();
    }

    private static String migrate1To2(String text) throws IOException {
        Properties old = properties(text);
        StringBuilder result = new StringBuilder();
        boolean hasSharePass = old.containsKey("sharePass");
        boolean hasPending = old.containsKey("maxPending");
        boolean hasReceipts = old.containsKey("receiptCapacity");
        for (String line : text.split("\\R", -1)) {
            String key = key(line);
            if ("schema".equals(key)) {
                result.append("# migrated: ").append(line).append("  # renamed to config-version; this is operator config, not state schema\n");
            } else if ("dedupCapacity".equals(key)) {
                result.append("# migrated: ").append(line).append("  # renamed to receiptCapacity\n");
                if (!hasReceipts) { result.append("receiptCapacity=").append(value(line)).append('\n'); hasReceipts = true; }
            } else if ("role".equals(key)) {
                result.append("# migrated: ").append(line)
                        .append("  # removed: Velocity is always the network authority\n");
            } else if ("serverId".equals(key)) {
                result.append("# migrated: ").append(line)
                        .append("  # removed: backend identities come from registered servers\n");
            } else {
                result.append(line).append('\n');
            }
        }
        if (!hasSharePass) result.append("# New in config version 2; leave blank to continue using a valid legacy sharedSecret.\nsharePass=\n");
        if (!hasPending) result.append("maxPending=256\n");
        if (!hasReceipts) result.append("receiptCapacity=4096\n");
        result.append("config-version=2\n");
        return result.toString();
    }

    private static void validate(String text) throws IOException {
        Map<String, Integer> known = new HashMap<>();
        for (String line : text.split("\\R")) {
            String key = key(line);
            if (key != null && (key.equals("config-version") || key.equals("sharePass") || key.equals("sharedSecret")
                    || key.equals("maxPending") || key.equals("receiptCapacity"))) {
                if (known.merge(key, 1, Integer::sum) > 1) throw new IOException("duplicate network config key " + key);
            }
        }
        Properties values = properties(text);
        if (integer(values.getProperty("config-version", "0"), "config-version") != CURRENT_VERSION)
            throw new IOException("invalid migrated network config version");
        bounded(values, "maxPending", 256); bounded(values, "receiptCapacity", 4096);
        String pass = values.getProperty("sharePass", "").trim();
        if (!pass.isEmpty() && pass.length() < 12) throw new IOException("sharePass must contain at least 12 characters");
        if (values.containsKey("sharedSecret")) validateEncodedSecret(values.getProperty("sharedSecret"));
    }

    private static void validateEncodedSecret(String encoded) throws IOException {
        try {
            if (Base64.getDecoder().decode(encoded).length < 32) {
                throw new IOException("legacy sharedSecret must decode to at least 32 bytes");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("legacy sharedSecret is not valid Base64", invalid);
        }
    }

    static int bounded(Properties values, String key, int fallback) throws IOException {
        int value = integer(values.getProperty(key, Integer.toString(fallback)), key);
        if (value < 1 || value > 1_000_000) throw new IOException(key + " is outside bounds");
        return value;
    }
    private static int integer(String text, String key) throws IOException {
        try { return Integer.parseInt(text.trim()); }
        catch (RuntimeException invalid) { throw new IOException("invalid integer for " + key, invalid); }
    }
    private static Properties properties(String text) throws IOException {
        Properties result = new Properties(); result.load(new StringReader(text)); return result;
    }
    private static String key(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null;
        int equals = trimmed.indexOf('='); int colon = trimmed.indexOf(':');
        int split = equals < 0 ? colon : colon < 0 ? equals : Math.min(equals, colon);
        if (split < 0) return null;
        return trimmed.substring(0, split).trim();
    }
    private static String value(String line) {
        int equals = line.indexOf('='); int colon = line.indexOf(':');
        int split = equals < 0 ? colon : colon < 0 ? equals : Math.min(equals, colon);
        return split < 0 ? "" : line.substring(split + 1).trim();
    }
    private void writeAtomic(String text) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "network-config-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
