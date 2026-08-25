package com.github.ucchyocean.lunachat.core.network;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded bootstrap/state proposal sent by an authenticated Paper edge. */
public final class ChannelStateCodec {
    private static final int VERSION = 1;
    private static final int MAX_CHANNELS = 1000;
    private static final int MAX_ALIASES = 32;

    public byte[] encode(List<ChannelDescriptor> channels) {
        if (channels.size() > MAX_CHANNELS) throw new IllegalArgumentException("too many channels");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeShort(channels.size());
            for (ChannelDescriptor channel : channels) {
                write(out, channel.id().value());
                write(out, channel.name());
                if (channel.aliases().size() > MAX_ALIASES) throw new IllegalArgumentException("too many aliases");
                out.writeByte(channel.aliases().size());
                for (String alias : channel.aliases()) write(out, alias);
                out.writeBoolean(channel.acceptsExternalMessages());
            }
            byte[] result = bytes.toByteArray();
            if (result.length > 65535) throw new IllegalArgumentException("channel state exceeds frame limit");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public List<ChannelDescriptor> decode(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readUnsignedByte() != VERSION) throw new IOException("unknown channel state version");
        int count = in.readUnsignedShort();
        if (count > MAX_CHANNELS) throw new IOException("too many channels");
        List<ChannelDescriptor> result = new ArrayList<>(count);
        Set<ChannelId> ids = new HashSet<>();
        for (int index = 0; index < count; index++) {
            ChannelId id = new ChannelId(read(in));
            String name = read(in);
            int aliasCount = in.readUnsignedByte();
            if (aliasCount > MAX_ALIASES) throw new IOException("too many aliases");
            Set<String> aliases = new HashSet<>();
            for (int alias = 0; alias < aliasCount; alias++) aliases.add(read(in));
            ChannelDescriptor descriptor = new ChannelDescriptor(id, name, aliases, in.readBoolean());
            if (!ids.add(id)) throw new IOException("duplicate channel id");
            result.add(descriptor);
        }
        if (in.available() != 0) throw new IOException("trailing channel state");
        return List.copyOf(result);
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) throw new IllegalArgumentException("state string too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String read(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > 1024 || length > in.available()) throw new IOException("invalid state string");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
