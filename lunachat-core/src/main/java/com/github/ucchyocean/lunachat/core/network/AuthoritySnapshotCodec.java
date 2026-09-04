package com.github.ucchyocean.lunachat.core.network;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import java.io.*;
import java.util.*;

/** Internal full authority state. Membership tombstones prevent stale joins. */
public final class AuthoritySnapshotCodec {
    public record Key(ChannelId channel, UUID player) {}
    public record Member(Key key, boolean joined, long version) {
        public Member { if (version < 1) throw new IllegalArgumentException("invalid member version"); }
    }
    public record Snapshot(long revision, List<ChannelDescriptor> channels, List<Member> members,
                           Set<ChannelId> joinable) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("invalid revision");
            channels = List.copyOf(channels); members = List.copyOf(members); joinable = Set.copyOf(joinable);
            Set<ChannelId> ids = new HashSet<>();
            for (var channel : channels) if (!ids.add(channel.id())) throw new IllegalArgumentException("duplicate channel");
            Set<Key> keys = new HashSet<>();
            for (var member : members) {
                if (!ids.contains(member.key().channel()) || member.version() > revision || !keys.add(member.key()))
                    throw new IllegalArgumentException("invalid membership snapshot");
            }
            if (!ids.containsAll(joinable)) throw new IllegalArgumentException("unknown join policy channel");
        }
        public long version(Key key) {
            return members.stream().filter(m -> m.key().equals(key)).mapToLong(Member::version).findFirst().orElse(0);
        }
    }
    public record Change(Key key, boolean joined, long expectedVersion) {
        public Change { if (expectedVersion < 0) throw new IllegalArgumentException("invalid expected version"); }
    }
    private final ChannelStateCodec channels = new ChannelStateCodec();
    public byte[] encode(Snapshot snapshot) {
        if (snapshot.members().size() > 1500 || snapshot.joinable().size() > 1500)
            throw new IllegalArgumentException("state count exceeds bound");
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(0x4c434d31); out.writeLong(snapshot.revision());
            byte[] catalog = channels.encode(snapshot.channels()); out.writeInt(catalog.length); out.write(catalog);
            out.writeInt(snapshot.members().size());
            for (var member : snapshot.members()) {
                writeKey(out, member.key()); out.writeBoolean(member.joined()); out.writeLong(member.version());
            }
            out.writeInt(snapshot.joinable().size());
            for (var id : snapshot.joinable()) writeUuid(out, UUID.fromString(id.value()));
            if (bytes.size() > 60_000) throw new IllegalArgumentException("authority snapshot exceeds frame budget");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public Snapshot decode(byte[] bytes) throws IOException {
        if (bytes.length > 60_000) throw new IOException("snapshot too large");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0x4c434d31) throw new IOException("membership-capable authority snapshot required");
        long revision = in.readLong(); int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("invalid catalog length");
        var catalog = channels.decode(in.readNBytes(length));
        int count = bounded(in.readInt()); var members = new ArrayList<Member>();
        try {
            for (int i = 0; i < count; i++) members.add(new Member(readKey(in), in.readBoolean(), in.readLong()));
            count = bounded(in.readInt()); var joinable = new HashSet<ChannelId>();
            for (int i = 0; i < count; i++) if (!joinable.add(new ChannelId(readUuid(in).toString()))) throw new IOException("duplicate policy");
            if (in.available() != 0) throw new IOException("trailing state bytes");
            return new Snapshot(revision, catalog, members, joinable);
        } catch (IllegalArgumentException invalid) { throw new IOException("invalid authority snapshot", invalid); }
    }
    public byte[] encodeChange(Change change) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            writeKey(out, change.key()); out.writeBoolean(change.joined()); out.writeLong(change.expectedVersion());
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public Change decodeChange(byte[] bytes) throws IOException {
        if (bytes.length != 41) throw new IOException("invalid change size");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        try { return new Change(readKey(in), in.readBoolean(), in.readLong()); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid membership change", invalid); }
    }
    private static int bounded(int count) throws IOException {
        if (count < 0 || count > 1500) throw new IOException("state count exceeds bound"); return count;
    }
    private static void writeKey(DataOutputStream out, Key key) throws IOException {
        writeUuid(out, UUID.fromString(key.channel().value())); writeUuid(out, key.player());
    }
    private static Key readKey(DataInputStream in) throws IOException { return new Key(new ChannelId(readUuid(in).toString()), readUuid(in)); }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
