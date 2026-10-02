package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Current public network players as last synchronized by Velocity. */
final class NetworkPlayerDirectory {
    private final Map<UUID, String> players = new LinkedHashMap<>();

    synchronized void reset() {
        players.clear();
    }

    synchronized void apply(PresenceCodec.Event event) {
        if (event.visibility() == PresenceCodec.Visibility.PUBLIC
                && event.kind() != PresenceCodec.Kind.QUIT) {
            players.put(event.player(), event.name());
        } else {
            players.remove(event.player());
        }
    }

    synchronized List<String> namesStartingWith(String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return players.values().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(needle))
                .toList();
    }
}
