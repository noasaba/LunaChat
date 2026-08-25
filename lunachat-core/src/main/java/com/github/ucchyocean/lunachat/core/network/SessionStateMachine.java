package com.github.ucchyocean.lunachat.core.network;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public final class SessionStateMachine {
    public enum State { DISCONNECTED, HANDSHAKING, READY, CLOSED }
    private State state = State.DISCONNECTED;
    private UUID sessionId;
    private byte[] challenge;
    private long epoch;
    public synchronized byte[] begin(UUID sessionId, long epoch) {
        if (state == State.CLOSED) throw new IllegalStateException("session is closed");
        this.sessionId=sessionId; this.epoch=epoch; this.challenge=new byte[32]; new SecureRandom().nextBytes(challenge);
        this.state=State.HANDSHAKING; return Arrays.copyOf(challenge, challenge.length);
    }
    public synchronized boolean authenticate(SecureFrameCodec codec, String nodeId, byte[] proof) {
        if (state != State.HANDSHAKING || !codec.verifyHandshakeProof(sessionId, challenge, nodeId, proof)) {
            state=State.DISCONNECTED; challenge=null; return false;
        }
        challenge=null; state=State.READY; return true;
    }
    public synchronized void disconnect() { if (state != State.CLOSED) state=State.DISCONNECTED; }
    public synchronized void close() { state=State.CLOSED; challenge=null; }
    public synchronized State state() { return state; }
    public synchronized UUID sessionId() { return sessionId; }
    public synchronized long epoch() { return epoch; }
}
