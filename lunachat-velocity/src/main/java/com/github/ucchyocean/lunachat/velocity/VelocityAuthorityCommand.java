package com.github.ucchyocean.lunachat.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import java.util.Arrays;
import java.util.LinkedHashSet;

/** Console-safe Velocity authority administration; Paper never owns catalog state. */
final class VelocityAuthorityCommand implements SimpleCommand {
    private final VelocityNetworkAuthority authority;
    VelocityAuthorityCommand(VelocityNetworkAuthority authority) { this.authority = authority; }
    @Override public void execute(Invocation invocation) {
        var source = invocation.source(); String[] args = invocation.arguments();
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                source.sendPlainMessage("LunaChat canonical channels: " + authority.channels().stream()
                        .map(c -> c.name() + "=" + c.id().value()).toList()); return;
            }
            switch (args[0].toLowerCase()) {
                case "create" -> { require(args, 2); authority.createChannel(args[1], args.length >= 3 && Boolean.parseBoolean(args[2])); source.sendPlainMessage("Created canonical LunaChat channel: " + args[1]); }
                case "delete", "disable" -> { require(args, 2); authority.deleteChannel(args[1]); source.sendPlainMessage("Deleted canonical LunaChat channel: " + args[1]); }
                case "alias" -> { require(args, 3); authority.setAlias(args[1], args[2].equals("-") ? "" : args[2]); source.sendPlainMessage("Updated LunaChat alias: " + args[1]); }
                case "default" -> { require(args, 2); authority.setSettings(args[1].equals("-") ? "" : args[1], authoritySettingsForce()); source.sendPlainMessage("Updated LunaChat default channel."); }
                case "force" -> { require(args, 2); authority.setSettings(authoritySettingsDefault(), new LinkedHashSet<>(Arrays.asList(args[1].equals("-") ? new String[0] : args[1].split(",")))); source.sendPlainMessage("Updated LunaChat force-join channels."); }
                default -> source.sendPlainMessage("Usage: /lunachat <list|create <name> [external]|delete <name>|alias <name> <alias|->|default <name|->|force <name,...|->>");
            }
        } catch (Exception error) { source.sendPlainMessage("LunaChat authority change rejected: " + error.getMessage()); }
    }
    private String authoritySettingsDefault() { return authority.snapshotSettings().defaultChannel(); }
    private java.util.Set<String> authoritySettingsForce() { return authority.snapshotSettings().forceJoinChannels(); }
    private static void require(String[] args, int count) { if (args.length < count) throw new IllegalArgumentException("missing argument"); }
}
