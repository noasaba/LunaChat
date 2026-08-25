package com.github.ucchyocean.lunachat.core.network;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** AES-GCM authenticated wire codec. Header fields are authenticated as AAD. */
public final class SecureFrameCodec {
    private static final int MAGIC = 0x4c434e31; // LCN1, distinct from LunaBridge protocol v2
    private static final int NONCE_LENGTH = 12;
    private final int protocolVersion;
    private final SecretKeySpec key;
    private final ReplayWindow replayWindow;
    private final Clock clock;
    private final SecureRandom random;

    public SecureFrameCodec(int protocolVersion, byte[] sharedSecret, ReplayWindow replayWindow, Clock clock) {
        if (sharedSecret == null || sharedSecret.length < 32) throw new IllegalArgumentException("shared secret must be at least 32 bytes");
        this.protocolVersion = protocolVersion;
        this.replayWindow = replayWindow;
        this.clock = clock;
        this.random = new SecureRandom();
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(sharedSecret);
            this.key = new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    public byte[] encode(SecureFrame frame) {
        if (frame.protocolVersion() != protocolVersion) throw new IllegalArgumentException("protocol mismatch");
        try {
            byte[] header = header(frame);
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(frame.payload());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(header.length + nonce.length + ciphertext.length + 8);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(header.length); out.write(header); out.write(nonce); out.writeInt(ciphertext.length); out.write(ciphertext);
            return bytes.toByteArray();
        } catch (GeneralSecurityException | IOException error) { throw new IllegalStateException("cannot encode secure frame", error); }
    }

    public SecureFrame decode(byte[] encoded) throws FrameAuthenticationException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int headerLength = in.readInt();
            if (headerLength < 64 || headerLength > 512) throw new FrameAuthenticationException("invalid header length");
            byte[] header = in.readNBytes(headerLength);
            if (header.length != headerLength) throw new FrameAuthenticationException("truncated header");
            byte[] nonce = in.readNBytes(NONCE_LENGTH);
            int cipherLength = in.readInt();
            if (nonce.length != NONCE_LENGTH || cipherLength < 16 || cipherLength > 65551) throw new FrameAuthenticationException("invalid encrypted payload");
            byte[] ciphertext = in.readNBytes(cipherLength);
            if (ciphertext.length != cipherLength || in.available() != 0) throw new FrameAuthenticationException("truncated or trailing frame");
            FrameHeader parsed = parseHeader(header);
            if (parsed.protocolVersion != protocolVersion) throw new FrameAuthenticationException("unknown protocol");
            Instant now = clock.instant();
            if (!parsed.expiresAt.isAfter(now) || parsed.sentAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
                throw new FrameAuthenticationException("frame outside time window");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            byte[] payload = cipher.doFinal(ciphertext);
            if (!replayWindow.accept(parsed.frameId, parsed.expiresAt, now)) throw new ReplayFrameException("frame replay or replay window full");
            return parsed.toFrame(payload);
        } catch (AEADBadTagException tampered) {
            throw new FrameAuthenticationException("frame authentication failed", tampered);
        } catch (FrameAuthenticationException expected) {
            throw expected;
        } catch (GeneralSecurityException | IOException | RuntimeException malformed) {
            throw new FrameAuthenticationException("malformed secure frame", malformed);
        }
    }

    public byte[] handshakeProof(UUID sessionId, byte[] challenge, String nodeId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            mac.update(uuid(sessionId)); mac.update(challenge); mac.update(nodeId.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    public boolean verifyHandshakeProof(UUID sessionId, byte[] challenge, String nodeId, byte[] proof) {
        return MessageDigest.isEqual(handshakeProof(sessionId, challenge, nodeId), proof);
    }

    private byte[] header(SecureFrame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(frame.protocolVersion()); out.write(uuid(frame.sessionId()));
        out.writeLong(frame.epoch()); out.writeLong(frame.sequence()); out.write(uuid(frame.frameId()));
        out.writeBoolean(frame.logicalMessageId() != null);
        if (frame.logicalMessageId() != null) out.write(uuid(frame.logicalMessageId()));
        out.writeByte(frame.type().ordinal()); out.writeLong(frame.sentAt().toEpochMilli()); out.writeLong(frame.expiresAt().toEpochMilli());
        return bytes.toByteArray();
    }

    private FrameHeader parseHeader(byte[] header) throws IOException, FrameAuthenticationException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(header));
        if (in.readInt() != MAGIC) throw new FrameAuthenticationException("wrong protocol family");
        int protocol = in.readInt(); UUID session = readUuid(in); long epoch = in.readLong(); long sequence = in.readLong();
        UUID frameId = readUuid(in); UUID logical = in.readBoolean() ? readUuid(in) : null;
        int ordinal = in.readUnsignedByte();
        if (ordinal >= FrameType.values().length) throw new FrameAuthenticationException("unknown frame type");
        Instant sent = Instant.ofEpochMilli(in.readLong()); Instant expires = Instant.ofEpochMilli(in.readLong());
        if (in.available() != 0 || epoch < 0 || sequence < 0) throw new FrameAuthenticationException("invalid header");
        return new FrameHeader(protocol, session, epoch, sequence, frameId, logical, FrameType.values()[ordinal], sent, expires);
    }

    private static byte[] uuid(UUID value) {
        byte[] result = new byte[16];
        java.nio.ByteBuffer.wrap(result).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
        return result;
    }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private record FrameHeader(int protocolVersion, UUID sessionId, long epoch, long sequence, UUID frameId,
            UUID logicalMessageId, FrameType type, Instant sentAt, Instant expiresAt) {
        SecureFrame toFrame(byte[] payload) { return new SecureFrame(protocolVersion, sessionId, epoch, sequence, frameId, logicalMessageId, type, sentAt, expiresAt, payload); }
    }
}
