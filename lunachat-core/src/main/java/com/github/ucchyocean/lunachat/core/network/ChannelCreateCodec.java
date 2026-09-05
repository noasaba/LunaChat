package com.github.ucchyocean.lunachat.core.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Bounded internal request for creating one canonical channel at Velocity. */
public final class ChannelCreateCodec {
    public record Request(String name, boolean acceptsExternalMessages) {
        public Request {
            if (name == null || !name.matches("[0-9A-Za-z_-]{1,20}")) {
                throw new IllegalArgumentException("invalid canonical channel name");
            }
        }
    }

    public byte[] encode(Request request) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeUTF(request.name());
            output.writeBoolean(request.acceptsExternalMessages());
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public Request decode(byte[] payload) throws IOException {
        if (payload.length > 128) throw new IOException("channel create request too large");
        var input = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            Request request = new Request(input.readUTF(), input.readBoolean());
            if (input.available() != 0) throw new IOException("trailing channel create data");
            return request;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid channel create request", invalid);
        }
    }
}
