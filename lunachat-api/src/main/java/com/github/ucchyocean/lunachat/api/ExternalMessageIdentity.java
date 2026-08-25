package com.github.ucchyocean.lunachat.api;

/** Provider-scoped idempotency key, for example a Discord message ID. */
public record ExternalMessageIdentity(String namespace, String value) {
    public ExternalMessageIdentity {
        namespace = ApiConstraints.namespace(namespace);
        value = ApiConstraints.text(value, "external identity", 128);
    }
}
