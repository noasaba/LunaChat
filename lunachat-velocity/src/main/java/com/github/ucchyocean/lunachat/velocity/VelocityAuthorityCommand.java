package com.github.ucchyocean.lunachat.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;

/** Console-safe Velocity authority administration; Paper never owns catalog state. */
final class VelocityAuthorityCommand implements SimpleCommand {
    static final String ADMIN_PERMISSION = "lunachat.admin";
    private final VelocityNetworkAuthority authority;
    VelocityAuthorityCommand(VelocityNetworkAuthority authority) { this.authority = authority; }
    @Override public boolean hasPermission(Invocation invocation) { return allowed(invocation.source()); }
    @Override public void execute(Invocation invocation) {
        var source = invocation.source(); String[] args = invocation.arguments();
        if (!allowed(source)) {
            source.sendPlainMessage("You do not have permission: " + ADMIN_PERMISSION);
            return;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                source.sendPlainMessage("LunaChat canonical channels (" + authority.channels().size() + "):");
                authority.channels().forEach(channel -> source.sendPlainMessage("- " + channel.name() + "  id=" + channel.id().value()
                        + (channel.aliases().isEmpty() ? "" : "  alias=" + String.join(",", channel.aliases()))
                        + "  external=" + channel.acceptsExternalMessages()));
                return;
            }
            switch (args[0].toLowerCase()) {
                case "status" -> source.sendPlainMessage(authority.statusLine());
                case "create" -> { require(args, 2); authority.createChannel(args[1], args.length >= 3 && bool(args[2])); source.sendPlainMessage("Created canonical LunaChat channel: " + args[1]); }
                case "delete", "disable" -> { require(args, 2); authority.deleteChannel(args[1]); source.sendPlainMessage("Deleted canonical LunaChat channel: " + args[1]); }
                case "alias" -> { require(args, 3); authority.setAlias(args[1], args[2].equals("-") ? "" : args[2]); source.sendPlainMessage("Updated LunaChat alias: " + args[1]); }
                case "external" -> { require(args, 3); authority.setExternal(args[1], bool(args[2])); source.sendPlainMessage("Updated external-message policy: " + args[1]); }
                case "global" -> { require(args, 2); authority.setSettings(value(args[1]), authoritySettingsDefault(), authoritySettingsForce()); source.sendPlainMessage("Updated LunaChat global channel."); }
                case "default" -> { require(args, 2); authority.setSettings(authoritySettingsGlobal(), value(args[1]), authoritySettingsForce()); source.sendPlainMessage("Updated LunaChat login-default channel."); }
                case "force" -> { require(args, 2); authority.setSettings(authoritySettingsGlobal(), authoritySettingsDefault(), names(args[1])); source.sendPlainMessage("Updated LunaChat force-join channels."); }
                case "joinable" -> { require(args, 3); authority.setJoinable(args[1], bool(args[2])); source.sendPlainMessage("Updated join policy: " + args[1]); }
                case "password" -> { require(args, 3); authority.setPassword(args[1], value(args[2])); source.sendPlainMessage("Updated password policy: " + args[1]); }
                case "visible" -> { require(args, 3); authority.setVisible(args[1], bool(args[2])); source.sendPlainMessage("Updated visibility policy: " + args[1]); }
                case "world" -> { require(args, 3); authority.setWorld(args[1], bool(args[2])); source.sendPlainMessage("Updated world policy: " + args[1]); }
                case "moderator", "ban", "mute" -> {
                    require(args, 4); boolean enabled = bool(args[3]); Long expiry = args.length >= 5 && !args[4].equals("-") ? Long.parseLong(args[4]) : null;
                    if (expiry != null && expiry <= System.currentTimeMillis()) throw new IllegalArgumentException("expiry must be a future epoch-millisecond value");
                    authority.setPlayerPolicy(args[1], args[0].toLowerCase(Locale.ROOT), UUID.fromString(args[2]), enabled, expiry);
                    source.sendPlainMessage("Updated " + args[0].toLowerCase(Locale.ROOT) + " policy: " + args[1]);
                }
                default -> usage(source);
            }
        } catch (Exception error) { source.sendPlainMessage("LunaChat authority change rejected: " + error.getMessage()); }
    }
    @Override public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (!allowed(invocation.source())) return List.of();
        if (args.length <= 1) return matching(List.of("status", "list", "create", "delete", "alias", "external", "global", "default", "force", "joinable", "password", "visible", "world", "moderator", "ban", "mute"), args.length == 0 ? "" : args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && !action.equals("create") && !action.equals("force")) return matching(authority.channels().stream().map(c -> c.name()).toList(), args[1]);
        if (args.length == 3 && Set.of("external", "joinable", "visible", "world").contains(action)) return matching(List.of("true", "false"), args[2]);
        if (args.length == 4 && Set.of("moderator", "ban", "mute").contains(action)) return matching(List.of("true", "false"), args[3]);
        return List.of();
    }
    private static void usage(CommandSource source) {
        source.sendPlainMessage("Usage: /lunachat <status|list|create|delete|alias|external|global|default|force|joinable|password|visible|world|moderator|ban|mute>");
    }
    private static List<String> matching(List<String> values, String prefix) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList(); }
    private static boolean bool(String value) { if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("expected true or false"); return Boolean.parseBoolean(value); }
    private static String value(String value) { return value.equals("-") ? "" : value; }
    private static LinkedHashSet<String> names(String value) { return new LinkedHashSet<>(Arrays.asList(value.equals("-") ? new String[0] : value.split(","))); }
    private String authoritySettingsGlobal() { return authority.snapshotSettings().globalChannel(); }
    private String authoritySettingsDefault() { return authority.snapshotSettings().defaultChannel(); }
    private java.util.Set<String> authoritySettingsForce() { return authority.snapshotSettings().forceJoinChannels(); }
    private static boolean allowed(CommandSource source) {
        return source instanceof ConsoleCommandSource || source.hasPermission(ADMIN_PERMISSION);
    }
    private static void require(String[] args, int count) { if (args.length < count) throw new IllegalArgumentException("missing argument"); }
}
