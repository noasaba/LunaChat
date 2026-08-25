package com.github.ucchyocean.lunachat.api;

import java.util.Objects;
import java.util.Set;

/** Integration-safe channel metadata without membership or permission internals. */
public record ChannelDescriptor(ChannelId id, String name, Set<String> aliases, boolean acceptsExternalMessages) {
    public ChannelDescriptor {
        Objects.requireNonNull(id, "id");
        name = ApiConstraints.text(name, "name", 128);
        aliases = Set.copyOf(Objects.requireNonNull(aliases, "aliases"));
        if (aliases.size() > 32) throw new IllegalArgumentException("too many aliases");
        aliases.forEach(alias -> ApiConstraints.text(alias, "alias", 128));
    }
}
