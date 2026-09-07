package com.github.ucchyocean.lc3.command;

import com.github.ucchyocean.lc3.Messages;
import com.github.ucchyocean.lc3.member.ChannelMemberDummy;
import com.github.ucchyocean.lc3.util.Utility;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LunaChatMessageCommandTest {

    @Test
    public void backendOutageIsNotReportedAsMissingPlayer() {
        RecordingMember sender = new RecordingMember();

        LunaChatMessageCommand.sendNetworkTellFailure(sender, "target", "BACKEND_UNAVAILABLE");

        assertEquals(List.of("LunaChat could not reach the player's server; please try again."), sender.messages);
    }

    @Test
    public void networkTellEchoMakesTargetClickable() {
        Messages.initialize(new File("target/classes"), null, "ja");

        BaseComponent[] echo = LunaChatMessageCommand.makeNetworkTellEcho(
                "sender", "target", "hello");

        assertEquals("[sender -> target] hello", Utility.stripColorCode(legacyText(echo)));
        assertTrue(hasSuggestedCommand(echo, "/tell target "));
    }

    private static boolean hasSuggestedCommand(BaseComponent[] components, String command) {
        for (BaseComponent component : components) {
            ClickEvent click = component.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.SUGGEST_COMMAND
                    && command.equals(click.getValue())) return true;
        }
        return false;
    }

    private static String legacyText(BaseComponent[] components) {
        StringBuilder result = new StringBuilder();
        for (BaseComponent component : components) result.append(component.toLegacyText());
        return result.toString();
    }

    private static final class RecordingMember extends ChannelMemberDummy {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }
    }
}
