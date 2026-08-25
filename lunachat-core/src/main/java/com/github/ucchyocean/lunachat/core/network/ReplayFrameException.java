package com.github.ucchyocean.lunachat.core.network;

/** Authenticated frame already seen (or replay admission is full); discard without dropping the session. */
public final class ReplayFrameException extends FrameAuthenticationException {
    public ReplayFrameException(String message) { super(message); }
}
