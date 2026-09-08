package com.github.ucchyocean.lc3.integration;

/** Presence messages are intentionally local to each Paper backend. */
public final class PaperPresencePolicy {
    private PaperPresencePolicy() {}

    public static boolean useLocalPresence(boolean networkEdge) {
        return networkEdge;
    }

    public static boolean canReceiveLocalPresence(boolean self, boolean canSeeSubject) {
        return self || canSeeSubject;
    }
}
