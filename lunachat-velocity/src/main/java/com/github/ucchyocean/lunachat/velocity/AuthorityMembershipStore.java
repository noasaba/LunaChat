package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec;
import com.github.ucchyocean.lunachat.core.network.AuthoritySnapshotCodec.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Durable single writer. A present state file permanently fences legacy import. */
final class AuthorityMembershipStore {
    private final Path file;
    private final AuthoritySnapshotCodec codec = new AuthoritySnapshotCodec();
    private Snapshot state;

    AuthorityMembershipStore(Path directory, List<ChannelDescriptor> catalog) throws IOException {
        this(directory, catalog, AuthoritySnapshotCodec.Settings.empty());
    }
    AuthorityMembershipStore(Path directory, List<ChannelDescriptor> catalog, AuthoritySnapshotCodec.Settings settings) throws IOException {
        Files.createDirectories(directory); file = directory.resolve("membership-state.bin");
        if (Files.exists(file)) {
            state = codec.decode(Files.readAllBytes(file));
            if (!state.channels().equals(catalog)) {
                // Metadata updates and additions retain stable identities. Removing
                // identities requires an explicit migration, never a silent reset.
                Set<ChannelId> ids = new HashSet<>(); catalog.forEach(c -> ids.add(c.id()));
                if (state.channels().stream().anyMatch(c -> !ids.contains(c.id())))
                    throw new IOException("canonical channel removed or replaced: explicit membership migration required");
                Snapshot updated = new Snapshot(state.revision() + 1, catalog, state.members(), state.joinable(), state.policies(), settings);
                persist(updated); state = updated;
            }
        } else {
            Path seed = directory.resolve("membership-import.properties");
            Properties input = new Properties();
            if (Files.exists(seed)) try (var stream = Files.newInputStream(seed)) { input.load(stream); }
            if (Files.exists(seed) && !"1".equals(input.getProperty("schema"))) throw new IOException("invalid import schema");
            Set<ChannelId> ids = new HashSet<>(); catalog.forEach(c -> ids.add(c.id()));
            List<Member> members = new ArrayList<>(); Set<ChannelId> joinable = new HashSet<>();
            try {
                for (String key : input.stringPropertyNames()) {
                    if (key.equals("schema") || key.equals("source")) continue;
                    if (!key.startsWith("channel.")) throw new IOException("unknown import key");
                    String[] parts = key.split("\\.");
                    if (parts.length != 3) throw new IOException("invalid import key");
                    ChannelId id = new ChannelId(parts[1]);
                    if (!ids.contains(id)) throw new IOException("import channel is absent from canonical catalog");
                    if (parts[2].equals("members")) {
                        String value = input.getProperty(key).trim();
                        if (!value.isEmpty()) for (String uuid : value.split(","))
                            members.add(new Member(new Key(id, UUID.fromString(uuid.trim())), true, 1));
                    } else if (parts[2].equals("joinable")) {
                        String value = input.getProperty(key);
                        if (!value.equals("true") && !value.equals("false")) throw new IOException("invalid join policy");
                        if (value.equals("true")) joinable.add(id);
                    } else if (!Set.of("moderators", "banned", "muted", "ban_expires", "mute_expires", "password", "visible", "world").contains(parts[2])) {
                        throw new IOException("unknown import field");
                    }
                }
                state = new Snapshot(1, catalog, members, joinable, policies(input, catalog), settings);
                persist(state);
            } catch (IllegalArgumentException invalid) { throw new IOException("invalid membership import", invalid); }
        }
        if (!state.settings().equals(settings)) {
            Snapshot updated = new Snapshot(state.revision() + 1, state.channels(), state.members(), state.joinable(), state.policies(), settings);
            persist(updated); state = updated;
        }
        // This Velocity-only file is the ongoing management surface. It is
        // optional; when present it atomically replaces policy fields only,
        // never membership imported from a Paper backend.
        Path policyFile = directory.resolve("membership-policy.properties");
        if (Files.exists(policyFile)) {
            Properties policyInput = new Properties();
            try (var stream = Files.newInputStream(policyFile)) { policyInput.load(stream); }
            if (!"1".equals(policyInput.getProperty("schema"))) throw new IOException("invalid policy schema");
            List<Policy> policy = policies(policyInput, catalog, true);
            if (!policy.equals(state.policies())) {
                Snapshot updated = new Snapshot(state.revision() + 1, state.channels(), state.members(), state.joinable(), policy, state.settings());
                persist(updated); state = updated;
            }
        }
    }
    synchronized Snapshot snapshot() { return state; }
    synchronized void refreshCatalog(List<ChannelDescriptor> catalog, AuthoritySnapshotCodec.Settings settings) throws IOException {
        Set<ChannelId> ids = new HashSet<>(); catalog.forEach(c -> ids.add(c.id()));
        if (state.channels().stream().anyMatch(c -> !ids.contains(c.id()))) throw new IOException("canonical channel removed: explicit membership migration required");
        Set<ChannelId> joinable = new HashSet<>(state.joinable());
        Set<ChannelId> existing = new HashSet<>(); state.channels().forEach(c -> existing.add(c.id()));
        // New channels are open; preserve existing/imported restrictions.
        catalog.stream().filter(c -> !existing.contains(c.id())).forEach(c -> joinable.add(c.id()));
        Snapshot next = new Snapshot(state.revision() + 1, catalog, state.members(), joinable, state.policies(), settings);
        persist(next); state = next;
    }
    synchronized String change(Change change) throws IOException {
        if (state.channels().stream().noneMatch(c -> c.id().equals(change.key().channel()))) return "UNKNOWN_CHANNEL";
        Member previous = state.members().stream().filter(m -> m.key().equals(change.key())).findFirst().orElse(null);
        boolean joined = previous != null && previous.joined();
        // Idempotent retries never mutate; opposite stale requests cannot undo a leave/kick.
        if (joined == change.joined()) return "APPLIED";
        if (state.version(change.key()) != change.expectedVersion()) return "STALE";
        if (change.joined() && !state.joinable().contains(change.key().channel())) return "JOIN_DISABLED";
        List<Member> next = new ArrayList<>(state.members()); next.removeIf(m -> m.key().equals(change.key()));
        next.add(new Member(change.key(), change.joined(), state.revision() + 1));
        Snapshot proposal = new Snapshot(state.revision() + 1, state.channels(), next, state.joinable(), state.policies(), state.settings());
        persist(proposal); state = proposal; return "APPLIED";
    }
    private static List<Policy> policies(Properties input, List<ChannelDescriptor> catalog) throws IOException {
        return policies(input, catalog, false);
    }
    private static List<Policy> policies(Properties input, List<ChannelDescriptor> catalog, boolean requireComplete) throws IOException {
        List<Policy> result = new ArrayList<>();
        for (ChannelDescriptor channel : catalog) {
            String prefix = "channel." + channel.id().value() + ".";
            if (requireComplete) for (String field : List.of("moderators", "banned", "muted", "ban_expires", "mute_expires", "password", "visible", "world"))
                if (!input.containsKey(prefix + field)) throw new IOException("incomplete Velocity policy file for " + channel.id());
            Set<UUID> moderators = ids(input, prefix + "moderators");
            Set<UUID> banned = ids(input, prefix + "banned");
            Set<UUID> muted = ids(input, prefix + "muted");
            Map<UUID, Long> banExpires = expiry(input, prefix + "ban_expires", banned);
            Map<UUID, Long> muteExpires = expiry(input, prefix + "mute_expires", muted);
            String password = input.getProperty(prefix + "password", "");
            boolean visible = bool(input, prefix + "visible", true);
            boolean world = bool(input, prefix + "world", false);
            if (!moderators.isEmpty() || !banned.isEmpty() || !muted.isEmpty() || !password.isEmpty() || !visible || world)
                result.add(new Policy(channel.id(), moderators, banned, muted, banExpires, muteExpires, password, visible, world));
        }
        return result;
    }
    private static Set<UUID> ids(Properties input, String key) throws IOException {
        String value = input.getProperty(key, "").trim(); Set<UUID> result = new LinkedHashSet<>();
        try { if (!value.isEmpty()) for (String text : value.split(",")) if (!result.add(UUID.fromString(text.trim()))) throw new IOException("duplicate policy UUID"); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid policy UUID", invalid); }
        return result;
    }
    private static Map<UUID, Long> expiry(Properties input, String key, Set<UUID> allowed) throws IOException {
        String value = input.getProperty(key, "").trim(); Map<UUID, Long> result = new LinkedHashMap<>();
        try { if (!value.isEmpty()) for (String pair : value.split(",")) {
            String[] parts=pair.split("@", -1);
            if (parts.length != 2) throw new IOException("invalid policy expiry");
            UUID id=UUID.fromString(parts[0]); long time=Long.parseLong(parts[1]);
            if (!allowed.contains(id) || time<=0 || result.put(id,time)!=null) throw new IOException("invalid policy expiry");
        } }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid policy expiry", invalid); }
        return result;
    }
    private static boolean bool(Properties input, String key, boolean fallback) throws IOException {
        String value=input.getProperty(key); if(value==null) return fallback;
        if(!value.equals("true") && !value.equals("false")) throw new IOException("invalid policy boolean"); return Boolean.parseBoolean(value);
    }
    private void persist(Snapshot next) throws IOException {
        final byte[] bytes;
        try { bytes = codec.encode(next); }
        catch (IllegalArgumentException capacity) { throw new IOException("membership snapshot exceeds supported capacity", capacity); }
        Path temp = Files.createTempFile(file.getParent(), "membership-", ".tmp");
        try {
            Files.write(temp, bytes);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
