package com.github.ucchyocean.lc3.command;

import com.github.ucchyocean.lc3.integration.PaperIntegrationService;
import com.github.ucchyocean.lc3.member.ChannelMember;
import com.github.ucchyocean.lunachat.api.NetworkStatus;

/** Player-visible network diagnostics without requiring console log access. */
public final class StatusCommand extends LunaChatSubCommand {
    @Override public String getCommandName() { return "status"; }
    @Override public String getPermissionNode() { return "lunachat.status"; }
    @Override public CommandType getCommandType() { return CommandType.USER; }
    @Override public void sendUsageMessage(ChannelMember sender, String label) { sender.sendMessage("/" + label + " status"); }
    @Override public boolean runCommand(ChannelMember sender, String label, String[] args) {
        PaperIntegrationService service = PaperIntegrationService.current();
        if (service == null) {
            sender.sendMessage("LunaChat integration: UNAVAILABLE (NOT_INITIALIZED)");
            return true;
        }
        NetworkStatus status = service.api().networkStatus().current();
        sender.sendMessage("LunaChat integration: " + status.state() + " (" + status.diagnosticCode() + ")");
        return true;
    }
}
