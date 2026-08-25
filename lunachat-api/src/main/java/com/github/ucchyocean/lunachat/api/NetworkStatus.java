package com.github.ucchyocean.lunachat.api;

import java.time.Instant;
import java.util.Objects;

/** Point-in-time immutable network and lifecycle status. */
public record NetworkStatus(NetworkState state, String diagnosticCode, Instant observedAt) {
    public NetworkStatus {
        Objects.requireNonNull(state, "state");
        diagnosticCode = ApiConstraints.text(diagnosticCode, "diagnosticCode", 64);
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
