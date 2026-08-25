package com.github.ucchyocean.lunachat.core.network;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.MessageAuthor;
import com.github.ucchyocean.lunachat.api.MessageOrigin;
import com.github.ucchyocean.lunachat.api.OriginKind;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecureFrameCodecTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test void authenticatedFrameRejectsTamperReplayAndWrongSecret() throws Exception {
        SecureFrameCodec sender = codec(SECRET);
        SecureFrameCodec receiver = codec(SECRET);
        UUID logical = UUID.randomUUID();
        SecureFrame frame = new SecureFrame(1, UUID.randomUUID(), 1, 7, UUID.randomUUID(), logical,
                FrameType.MESSAGE, NOW, NOW.plusSeconds(30), "payload".getBytes(StandardCharsets.UTF_8));
        byte[] encoded = sender.encode(frame);
        assertEquals(logical, receiver.decode(encoded).logicalMessageId());
        assertThrows(ReplayFrameException.class, () -> receiver.decode(encoded));
        byte[] tampered = encoded.clone(); tampered[tampered.length - 1] ^= 1;
        assertThrows(FrameAuthenticationException.class, () -> codec(SECRET).decode(tampered));
        byte[] wrong = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".getBytes(StandardCharsets.UTF_8);
        assertThrows(FrameAuthenticationException.class, () -> codec(wrong).decode(encoded));
    }

    @Test void messageWireRoundTripPreservesUuidOriginAndFinalContent() throws Exception {
        com.github.ucchyocean.lunachat.api.ChannelId channel = com.github.ucchyocean.lunachat.api.ChannelId.random();
        com.github.ucchyocean.lunachat.api.AcceptedMessage original = new com.github.ucchyocean.lunachat.api.AcceptedMessage(
                UUID.randomUUID(), channel, "renamed-global",
                new com.github.ucchyocean.lunachat.api.MessageOrigin(
                        com.github.ucchyocean.lunachat.api.OriginKind.EXTERNAL, "lunabridge:discord", "discord-42"),
                new com.github.ucchyocean.lunachat.api.MessageAuthor.External(
                        "lunabridge:discord", "user-7", "Discord User"),
                "paper-a", "final transformed content", NOW, NOW.plusSeconds(60));
        AcceptedMessageCodec codec = new AcceptedMessageCodec();
        assertEquals(original, codec.decode(codec.encode(original)));
        assertEquals(channel, codec.decode(codec.encode(original)).channelId());
    }

    @Test void messageWireRoundTripPreservesMinecraftPlayerAndSystemAuthors() throws Exception {
        ChannelId channel = ChannelId.random();
        AcceptedMessage player = new AcceptedMessage(UUID.randomUUID(), channel, "global",
                new MessageOrigin(OriginKind.MINECRAFT, "lunachat.minecraft", "minecraft-42"),
                new MessageAuthor.Player(UUID.randomUUID(), "player", "Player Display"),
                "paper-a", "hello", NOW, NOW.plusSeconds(60));
        AcceptedMessage system = new AcceptedMessage(UUID.randomUUID(), channel, "global",
                new MessageOrigin(OriginKind.SYSTEM, "lunachat.system", "system-42"),
                new MessageAuthor.System("LunaChat"), "velocity", "notice", NOW, NOW.plusSeconds(60));
        AcceptedMessageCodec codec = new AcceptedMessageCodec();
        assertEquals(player, codec.decode(codec.encode(player)));
        assertEquals(system, codec.decode(codec.encode(system)));
    }

    @Test void messageWireRejectsOriginAuthorMismatch() {
        ChannelId channel = ChannelId.random();
        AcceptedMessage invalid = new AcceptedMessage(UUID.randomUUID(), channel, "global",
                new MessageOrigin(OriginKind.MINECRAFT, "lunachat.minecraft", "minecraft-42"),
                new MessageAuthor.External("discord", "user-1", "User"),
                "paper-a", "hello", NOW, NOW.plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedMessageCodec().encode(invalid));
    }

    @Test void boundedOutboxRecoversCapacityAfterExpiry() {
        ReliableOutbox outbox = new ReliableOutbox(1, 2, Duration.ofMillis(10));
        assertTrue(outbox.offer(UUID.randomUUID(), new byte[]{1}, NOW.plusMillis(5), NOW));
        assertFalse(outbox.offer(UUID.randomUUID(), new byte[]{2}, NOW.plusSeconds(1), NOW));
        assertTrue(outbox.offer(UUID.randomUUID(), new byte[]{3}, NOW.plusSeconds(1), NOW.plusMillis(6)));
    }

    @Test void retryChangesFrameIdentityWhileKeepingLogicalIdentity() {
        ReliableOutbox outbox = new ReliableOutbox(4, 4, Duration.ofMillis(10));
        UUID logical = UUID.randomUUID();
        assertTrue(outbox.offer(logical, new byte[]{1}, NOW.plusSeconds(10), NOW));
        ReliableOutbox.Attempt first = outbox.pollDue(NOW, 1).getFirst();
        ReliableOutbox.Attempt retry = outbox.pollDue(NOW.plusMillis(20), 1).getFirst();
        assertEquals(first.logicalMessageId(), retry.logicalMessageId());
        assertNotEquals(first.frameId(), retry.frameId());
        assertNotEquals(first.sequence(), retry.sequence());
        assertTrue(outbox.acknowledge(logical));
        assertEquals(0, outbox.size());
    }

    @Test void handshakeFailsClosedOnSecretMismatch() {
        SecureFrameCodec authority = codec(SECRET);
        byte[] wrong = "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy".getBytes(StandardCharsets.UTF_8);
        SecureFrameCodec edge = codec(wrong);
        SessionStateMachine session = new SessionStateMachine(); UUID id = UUID.randomUUID();
        byte[] challenge = session.begin(id, 1);
        assertFalse(session.authenticate(authority, "paper-1", edge.handshakeProof(id, challenge, "paper-1")));
        assertEquals(SessionStateMachine.State.DISCONNECTED, session.state());
    }

    private static SecureFrameCodec codec(byte[] secret) {
        return new SecureFrameCodec(1, secret, new ReplayWindow(32), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
