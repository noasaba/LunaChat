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
        Files.createDirectories(directory); file = directory.resolve("membership-state.bin");
        if (Files.exists(file)) {
            state = codec.decode(Files.readAllBytes(file));
            if (!state.channels().equals(catalog)) {
                // Metadata updates and additions retain stable identities. Removing
                // identities requires an explicit migration, never a silent reset.
                Set<ChannelId> ids = new HashSet<>(); catalog.forEach(c -> ids.add(c.id()));
                if (state.channels().stream().anyMatch(c -> !ids.contains(c.id())))
                    throw new IOException("canonical channel removed or replaced: explicit membership migration required");
                Snapshot updated = new Snapshot(state.revision() + 1, catalog, state.members(), state.joinable());
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
                    } else throw new IOException("unknown import field");
                }
                state = new Snapshot(1, catalog, members, joinable);
                persist(state);
            } catch (IllegalArgumentException invalid) { throw new IOException("invalid membership import", invalid); }
        }
    }
    synchronized Snapshot snapshot() { return state; }
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
        Snapshot proposal = new Snapshot(state.revision() + 1, state.channels(), next, state.joinable());
        persist(proposal); state = proposal; return "APPLIED";
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
