package com.github.ucchyocean.lunachat.velocity;

/** Prevents authority resources from being created before a listener has bound successfully. */
final class VelocityStartupGate {
    private enum State { WAITING_FOR_BIND, STARTED, CLOSED }

    private State state = State.WAITING_FOR_BIND;

    synchronized boolean onListenerBound() {
        if (state != State.WAITING_FOR_BIND) return false;
        state = State.STARTED;
        return true;
    }

    synchronized void close() {
        state = State.CLOSED;
    }
}
