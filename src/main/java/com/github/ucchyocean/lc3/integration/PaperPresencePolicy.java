package com.github.ucchyocean.lc3.integration;

/** Chooses network rendering only when it can actually replace Bukkit presence. */
public final class PaperPresencePolicy {
    private PaperPresencePolicy() {}

    public static boolean suppressLocalMessage(boolean networkEdge, boolean authorityReady) {
        return networkEdge && authorityReady;
    }
}
