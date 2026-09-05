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
    /** Velocity-owned access policy.  It is internal to wire 3, not public API. */
    public record Policy(ChannelId channel, Set<UUID> moderators, Set<UUID> banned, Set<UUID> muted,
                         Map<UUID, Long> banExpires, Map<UUID, Long> muteExpires,
                         String password, boolean visible, boolean worldRange) {
        public Policy {
            moderators = Set.copyOf(moderators); banned = Set.copyOf(banned); muted = Set.copyOf(muted);
            banExpires = Map.copyOf(banExpires); muteExpires = Map.copyOf(muteExpires);
            if (password == null || password.length() > 64 || !banned.containsAll(banExpires.keySet())
                    || !muted.containsAll(muteExpires.keySet())) throw new IllegalArgumentException("invalid policy");
        }
        public static Policy open(ChannelId channel) {
            return new Policy(channel, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), "", true, false);
        }
    }
    /** Velocity-owned default and force-join policy. */
    public record Settings(String defaultChannel, Set<String> forceJoinChannels) {
        public Settings { defaultChannel = defaultChannel == null ? "" : defaultChannel; forceJoinChannels = Set.copyOf(forceJoinChannels); }
        public static Settings empty() { return new Settings("", Set.of()); }
    }
    public record Snapshot(long revision, List<ChannelDescriptor> channels, List<Member> members,
                           Set<ChannelId> joinable, List<Policy> policies, Settings settings) {
        public Snapshot(long revision, List<ChannelDescriptor> channels, List<Member> members, Set<ChannelId> joinable) {
            this(revision, channels, members, joinable, List.of(), Settings.empty());
        }
        public Snapshot(long revision, List<ChannelDescriptor> channels, List<Member> members, Set<ChannelId> joinable, List<Policy> policies) {
            this(revision, channels, members, joinable, policies, Settings.empty());
        }
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("invalid revision");
            channels = List.copyOf(channels); members = List.copyOf(members); joinable = Set.copyOf(joinable); policies = List.copyOf(policies);
            Set<ChannelId> ids = new HashSet<>();
            for (var channel : channels) if (!ids.add(channel.id())) throw new IllegalArgumentException("duplicate channel");
            Set<Key> keys = new HashSet<>();
            for (var member : members) {
                if (!ids.contains(member.key().channel()) || member.version() > revision || !keys.add(member.key()))
                    throw new IllegalArgumentException("invalid membership snapshot");
            }
            if (!ids.containsAll(joinable)) throw new IllegalArgumentException("unknown join policy channel");
            Set<ChannelId> policyIds = new HashSet<>();
            for (Policy policy : policies) if (!ids.contains(policy.channel()) || !policyIds.add(policy.channel()))
                throw new IllegalArgumentException("invalid channel policy");
            Set<String> names = new HashSet<>(); for (var c : channels) { names.add(c.name()); names.addAll(c.aliases()); }
            if (!settings.defaultChannel().isEmpty() && !names.contains(settings.defaultChannel())
                    || !names.containsAll(settings.forceJoinChannels())) throw new IllegalArgumentException("unknown authority setting channel");
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
        if (snapshot.members().size() > 1500 || snapshot.joinable().size() > 1500 || snapshot.policies().size() > 1500)
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
            out.writeInt(snapshot.policies().size());
            for (Policy policy : snapshot.policies()) writePolicy(out, policy);
            out.writeUTF(snapshot.settings().defaultChannel()); out.writeInt(snapshot.settings().forceJoinChannels().size());
            for (String name : snapshot.settings().forceJoinChannels()) out.writeUTF(name);
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
            count = bounded(in.readInt()); var policies = new ArrayList<Policy>();
            for (int i = 0; i < count; i++) policies.add(readPolicy(in));
            // v3 persisted snapshots predate authority defaults.  Keep their
            // membership/policy state and add empty settings on upgrade.
            if (in.available() == 0) return new Snapshot(revision, catalog, members, joinable, policies, Settings.empty());
            String defaultChannel = in.readUTF(); count = bounded(in.readInt()); Set<String> force = new HashSet<>();
            for (int i=0;i<count;i++) if(!force.add(in.readUTF())) throw new IOException("duplicate force channel");
            if (in.available() != 0) throw new IOException("trailing state bytes");
            return new Snapshot(revision, catalog, members, joinable, policies, new Settings(defaultChannel, force));
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
    private static void writePolicy(DataOutputStream out, Policy policy) throws IOException {
        writeUuid(out, UUID.fromString(policy.channel().value())); out.writeBoolean(policy.visible()); out.writeBoolean(policy.worldRange());
        out.writeUTF(policy.password()); writeSet(out, policy.moderators()); writeSet(out, policy.banned()); writeSet(out, policy.muted());
        writeTimes(out, policy.banExpires()); writeTimes(out, policy.muteExpires());
    }
    private static Policy readPolicy(DataInputStream in) throws IOException {
        ChannelId channel = new ChannelId(readUuid(in).toString()); boolean visible = in.readBoolean(); boolean world = in.readBoolean();
        String password = in.readUTF(); return new Policy(channel, readSet(in), readSet(in), readSet(in), readTimes(in), readTimes(in), password, visible, world);
    }
    private static void writeSet(DataOutputStream out, Set<UUID> set) throws IOException { out.writeInt(set.size()); for (UUID id : set) writeUuid(out, id); }
    private static Set<UUID> readSet(DataInputStream in) throws IOException { int n = bounded(in.readInt()); Set<UUID> result = new HashSet<>(); for(int i=0;i<n;i++) if(!result.add(readUuid(in))) throw new IOException("duplicate policy player"); return result; }
    private static void writeTimes(DataOutputStream out, Map<UUID, Long> values) throws IOException { out.writeInt(values.size()); for (var e:values.entrySet()) { writeUuid(out,e.getKey()); out.writeLong(e.getValue()); } }
    private static Map<UUID, Long> readTimes(DataInputStream in) throws IOException { int n=bounded(in.readInt()); Map<UUID,Long> result=new HashMap<>(); for(int i=0;i<n;i++) if(result.put(readUuid(in),in.readLong())!=null) throw new IOException("duplicate policy expiry"); return result; }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
