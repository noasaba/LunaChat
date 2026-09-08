package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.core.network.PresenceCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity-owned connection history used to reconstruct a privacy-safe reveal. */
final class PresenceHistory {
    private static final Duration HIDDEN_PATH_TTL = Duration.ofHours(6);
    private static final Duration DISCONNECT_EVENT_GRACE = Duration.ofSeconds(30);
    private record State(String name, String loginServer, String currentServer, String lastPublicServer,
            PresenceCodec.Visibility visibility, boolean joinedWhileHidden, Instant hiddenSince, Instant lastSeen) {}
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    synchronized List<PresenceCodec.Event> connected(UUID player, String name, String current,
            PresenceCodec.Visibility visibility, Instant now) {
        return connected(player, name, current, visibility, false, now);
    }

    synchronized List<PresenceCodec.Event> connected(UUID player, String name, String current,
            PresenceCodec.Visibility visibility, boolean initialLogin, Instant now) {
        State before = states.get(player);
        if (before == null || initialLogin) {
            boolean visible = visibility == PresenceCodec.Visibility.PUBLIC;
            states.put(player, new State(name, current, current, visible ? current : null,
                    visibility, visibility == PresenceCodec.Visibility.HIDDEN, visible ? null : now, now));
            return List.of(event(player, name, PresenceCodec.Kind.JOIN, null, current, visibility));
        }
        State moved = new State(name, before.loginServer(), current, before.lastPublicServer(), before.visibility(),
                before.joinedWhileHidden(), before.hiddenSince(), now);
        states.put(player, moved);
        return applyVisibility(player, moved, visibility, now, true);
    }

    synchronized List<PresenceCodec.Event> visibilityChanged(UUID player, String name, String current,
            PresenceCodec.Visibility visibility, Instant now) {
        State before = states.get(player);
        if (before == null) return List.of(); // plugin/provider attached mid-session: never invent a login
        State refreshed = new State(name, before.loginServer(), current, before.lastPublicServer(), before.visibility(),
                before.joinedWhileHidden(), before.hiddenSince(), now);
        states.put(player, refreshed);
        return applyVisibility(player, refreshed, visibility, now, false,
                before.visibility() == PresenceCodec.Visibility.HIDDEN);
    }

    synchronized List<PresenceCodec.Event> visibilityChanged(UUID player, String name, String current,
            PresenceCodec.Visibility visibility, boolean explicitReappear, Instant now) {
        State before = states.get(player);
        if (before == null) return List.of();
        State refreshed = new State(name, before.loginServer(), current, before.lastPublicServer(), before.visibility(),
                before.joinedWhileHidden(), before.hiddenSince(), now);
        states.put(player, refreshed);
        return applyVisibility(player, refreshed, visibility, now, false, explicitReappear);
    }

    synchronized PresenceCodec.Event disconnected(UUID player, String name, Instant now) {
        State before = states.remove(player);
        if (before == null) return null;
        return event(player, name, PresenceCodec.Kind.QUIT, before.currentServer(), null, before.visibility());
    }

    synchronized void discardStale(Instant now, Set<UUID> activePlayers) {
        // Velocity can remove a player from getAllPlayers()/mark it inactive before
        // delivering DisconnectEvent. Keep a short tombstone window so that event
        // remains authoritative for producing the final QUIT notification.
        states.replaceAll((id, state) -> activePlayers.contains(id)
                ? new State(state.name(), state.loginServer(), state.currentServer(), state.lastPublicServer(),
                        state.visibility(), state.joinedWhileHidden(), state.hiddenSince(), now)
                : state);
        states.entrySet().removeIf(entry -> !activePlayers.contains(entry.getKey())
                && entry.getValue().lastSeen().plus(DISCONNECT_EVENT_GRACE).isBefore(now));
        states.replaceAll((id, state) -> state.hiddenSince() != null
                && state.hiddenSince().plus(HIDDEN_PATH_TTL).isBefore(now)
                ? new State(state.name(), state.currentServer(), state.currentServer(), state.lastPublicServer(),
                        state.visibility(), false, now, state.lastSeen())
                : state);
    }

    private List<PresenceCodec.Event> applyVisibility(UUID player, State state,
            PresenceCodec.Visibility visibility, Instant now, boolean movement) {
        return applyVisibility(player, state, visibility, now, movement,
                state.visibility() == PresenceCodec.Visibility.HIDDEN);
    }

    private List<PresenceCodec.Event> applyVisibility(UUID player, State state,
            PresenceCodec.Visibility visibility, Instant now, boolean movement, boolean explicitReappear) {
        if (visibility != PresenceCodec.Visibility.PUBLIC) {
            State hidden = new State(state.name(), state.loginServer(), state.currentServer(), state.lastPublicServer(),
                    visibility, state.joinedWhileHidden(),
                    state.hiddenSince() == null ? now : state.hiddenSince(), now);
            states.put(player, hidden);
            return movement ? List.of(event(player, state.name(), PresenceCodec.Kind.MOVE,
                    state.lastPublicServer(), state.currentServer(), visibility)) : List.of();
        }
        if (state.visibility() == PresenceCodec.Visibility.PUBLIC) {
            if (!movement || state.currentServer().equals(state.lastPublicServer())) return List.of();
            states.put(player, publicState(state, now));
            return List.of(event(player, state.name(), PresenceCodec.Kind.MOVE,
                    state.lastPublicServer(), state.currentServer(), visibility));
        }
        if (state.visibility() != PresenceCodec.Visibility.HIDDEN || !explicitReappear) {
            states.put(player, publicState(state, now));
            return List.of();
        }
        List<PresenceCodec.Event> reveal = new ArrayList<>(1);
        if (state.joinedWhileHidden()) {
            reveal.add(event(player, state.name(), PresenceCodec.Kind.LOGIN, null, state.currentServer(), visibility));
        } else if (state.lastPublicServer() != null && !state.lastPublicServer().equals(state.currentServer())) {
            reveal.add(event(player, state.name(), PresenceCodec.Kind.MOVE,
                    state.lastPublicServer(), state.currentServer(), visibility));
        }
        states.put(player, publicState(state, now));
        return List.copyOf(reveal);
    }

    private static State publicState(State state, Instant now) {
        return new State(state.name(), state.loginServer(), state.currentServer(), state.currentServer(),
                PresenceCodec.Visibility.PUBLIC, false, null, now);
    }

    private static PresenceCodec.Event event(UUID player, String name, PresenceCodec.Kind kind,
            String from, String to, PresenceCodec.Visibility visibility) {
        return new PresenceCodec.Event(UUID.randomUUID(), player, name, kind, from, to, visibility);
    }
}
