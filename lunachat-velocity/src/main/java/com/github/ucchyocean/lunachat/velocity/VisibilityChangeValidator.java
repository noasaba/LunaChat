package com.github.ucchyocean.lunachat.velocity;

/** Guards SVSync callbacks against replay, reordered delivery, and stale connections. */
final class VisibilityChangeValidator {
    private long lastSequence = -1;

    synchronized boolean reserve(long sequence) {
        if (sequence <= lastSequence) return false;
        lastSequence = sequence;
        return true;
    }

    boolean validAtApply(SVSyncVisibilityIntegration.Change change, long queuedGeneration,
            long currentGeneration, boolean active, String currentServer) {
        return change.connected() && active && queuedGeneration == currentGeneration
                && currentServer != null && currentServer.equals(change.server());
    }
}
