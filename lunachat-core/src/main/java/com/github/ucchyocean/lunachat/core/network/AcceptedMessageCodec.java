package com.github.ucchyocean.lunachat.core.network;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.MessageAuthor;
import com.github.ucchyocean.lunachat.api.MessageOrigin;
import com.github.ucchyocean.lunachat.api.OriginKind;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal, bounded wire representation of the public immutable message model. */
public final class AcceptedMessageCodec {
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 32767;

    /**
     * Returns the exact AcceptedMessage representation carried by wire v1.
     * Wire timestamps are epoch milliseconds, so truncating before admission
     * keeps the authority's pending identity equal to every decoded ACK.
     */
    public AcceptedMessage canonicalize(AcceptedMessage message) {
        Objects.requireNonNull(message, "message");
        return new AcceptedMessage(message.messageId(), message.channelId(), message.channelName(),
                message.origin(), message.author(), message.sourceServerId(), message.content(),
                Instant.ofEpochMilli(message.createdAt().toEpochMilli()),
                Instant.ofEpochMilli(message.expiresAt().toEpochMilli()));
    }

    public byte[] encode(AcceptedMessage message) {
        message = canonicalize(message);
        try {
            if (!originAuthorMatches(message.origin().kind(), message.author())) {
                throw new IllegalArgumentException("origin and author kinds do not match");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            writeUuid(out, message.messageId());
            writeString(out, message.channelId().value());
            writeString(out, message.channelName());
            out.writeByte(message.origin().kind().ordinal());
            writeString(out, message.origin().namespace());
            writeString(out, message.origin().sourceMessageId());
            switch (message.author()) {
                case MessageAuthor.Player player -> {
                    out.writeByte(0);
                    writeUuid(out, player.uuid());
                    writeString(out, player.accountName());
                    writeString(out, player.displayName());
                }
                case MessageAuthor.External external -> {
                    out.writeByte(1);
                    writeString(out, external.namespace());
                    writeString(out, external.stableId());
                    writeString(out, external.displayName());
                }
                case MessageAuthor.System system -> {
                    out.writeByte(2);
                    writeString(out, system.name());
                }
            }
            writeString(out, message.sourceServerId());
            writeString(out, message.content());
            out.writeLong(message.createdAt().toEpochMilli());
            out.writeLong(message.expiresAt().toEpochMilli());
            out.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > 65535) throw new IllegalArgumentException("encoded message exceeds frame limit");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public AcceptedMessage decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > 65535) {
            throw new IOException("invalid message payload length");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readUnsignedByte() != VERSION) throw new IOException("unknown message payload version");
        UUID messageId = readUuid(in);
        ChannelId channelId = new ChannelId(readString(in));
        String channelName = readString(in);
        int originOrdinal = in.readUnsignedByte();
        if (originOrdinal >= OriginKind.values().length) throw new IOException("unknown origin kind");
        MessageOrigin origin = new MessageOrigin(OriginKind.values()[originOrdinal], readString(in), readString(in));
        MessageAuthor author = switch (in.readUnsignedByte()) {
            case 0 -> new MessageAuthor.Player(readUuid(in), readString(in), readString(in));
            case 1 -> new MessageAuthor.External(readString(in), readString(in), readString(in));
            case 2 -> new MessageAuthor.System(readString(in));
            default -> throw new IOException("unknown author kind");
        };
        String sourceServerId = readString(in);
        String content = readString(in);
        Instant createdAt = Instant.ofEpochMilli(in.readLong());
        Instant expiresAt = Instant.ofEpochMilli(in.readLong());
        if (in.available() != 0) throw new IOException("trailing message payload");
        try {
            if (!originAuthorMatches(origin.kind(), author)) {
                throw new IOException("origin and author kinds do not match");
            }
            return new AcceptedMessage(messageId, channelId, channelName, origin, author, sourceServerId,
                    content, createdAt, expiresAt);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid message model", invalid);
        }
    }

    private static boolean originAuthorMatches(OriginKind origin, MessageAuthor author) {
        return switch (origin) {
            case MINECRAFT -> author instanceof MessageAuthor.Player;
            case EXTERNAL -> author instanceof MessageAuthor.External;
            case SYSTEM -> author instanceof MessageAuthor.System;
        };
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("wire string too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_BYTES || length > in.available()) throw new IOException("invalid wire string");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
