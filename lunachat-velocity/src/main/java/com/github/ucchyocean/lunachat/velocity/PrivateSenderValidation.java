package com.github.ucchyocean.lunachat.velocity;

import java.util.UUID;

/** Validates the logical sender; the plugin-message carrier is intentionally not an input. */
final class PrivateSenderValidation {
    record PlayerState(UUID uuid, String username, String server, boolean active) {}
    record Result(boolean accepted, String reason, String resultStatus) {}

    private PrivateSenderValidation() {}

    static Result validate(PlayerState player, UUID requestedUuid, String requestedName, String sourceNode) {
        if (player == null || !player.active()) return new Result(false, "SENDER_OFFLINE", "NOT_FOUND");
        if (!player.uuid().equals(requestedUuid)) return new Result(false, "SENDER_UUID_MISMATCH", "NOT_FOUND");
        if (player.server() == null || !player.server().equals(sourceNode)) {
            return new Result(false, "SENDER_SERVER_MISMATCH", "NOT_FOUND");
        }
        if (requestedName == null || !player.username().equalsIgnoreCase(requestedName)) {
            return new Result(false, "SENDER_NAME_MISMATCH", "NOT_FOUND");
        }
        return new Result(true, "OK", "DELIVERED");
    }
}
