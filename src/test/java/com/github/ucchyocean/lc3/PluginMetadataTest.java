package com.github.ucchyocean.lc3;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/** Guards Paper's registered command and permission metadata against drift. */
public class PluginMetadataTest {
    @Test public void topLevelCommandsAliasesAndPermissionsAreDeclared() throws IOException {
        try (var stream = LunaChatBukkit.class.getResourceAsStream("/plugin.yml")) {
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String expected : new String[] {
                    "lunachat:", "aliases: [ch, lc]", "tell:", "aliases: [msg, message, m, t]",
                    "reply:", "aliases: [r]", "japanize:", "aliases: [jp]",
                    "lunachat.command:", "lunachat.message:", "lunachat.reply:",
                    "lunachat.japanize:", "lunachat-admin:" }) {
                assertTrue("missing plugin.yml declaration: " + expected, metadata.contains(expected));
            }
        }
    }

    @Test public void documentedSubcommandsHavePermissionDeclarations() throws IOException {
        try (var stream = LunaChatBukkit.class.getResourceAsStream("/plugin.yml")) {
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String command : new String[] {
                    "join", "leave", "list", "invite", "accept", "deny", "kick", "ban", "pardon",
                    "mute", "unmute", "hide", "unhide", "info", "log", "create", "remove", "format",
                    "moderator", "option", "template", "set", "dictionary", "reload", "help" }) {
                String permission = switch (command) {
                    case "template", "set", "dictionary", "reload" -> "lunachat-admin." + command + ":";
                    default -> "lunachat." + command + ":";
                };
                assertTrue("missing permission declaration: " + permission, metadata.contains(permission));
            }
        }
    }
}
