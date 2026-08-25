package com.github.ucchyocean.lunachat.api;

import java.util.Optional;

/** Explicit lifecycle-aware discovery contract used by proxy integrations. */
public interface LunaChatApiProvider {
    Optional<LunaChatIntegrationApi> current();
}
