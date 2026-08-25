package com.github.ucchyocean.lunachat.api;

import java.util.Objects;

/** Preserved source metadata used for audit and structural loop prevention. */
public record MessageOrigin(OriginKind kind, String namespace, String sourceMessageId) {
    public MessageOrigin {
        Objects.requireNonNull(kind, "kind");
        namespace = ApiConstraints.namespace(namespace);
        sourceMessageId = ApiConstraints.text(sourceMessageId, "sourceMessageId", 128);
    }
}
