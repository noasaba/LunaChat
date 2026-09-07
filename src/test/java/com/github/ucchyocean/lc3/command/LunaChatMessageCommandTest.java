package com.github.ucchyocean.lc3.command;

import com.github.ucchyocean.lc3.member.ChannelMemberDummy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class LunaChatMessageCommandTest {

    @Test
    public void backendOutageIsNotReportedAsMissingPlayer() {
        RecordingMember sender = new RecordingMember();

        LunaChatMessageCommand.sendNetworkTellFailure(sender, "target", "BACKEND_UNAVAILABLE");

        assertEquals(List.of("LunaChat could not reach the player's server; please try again."), sender.messages);
    }

    private static final class RecordingMember extends ChannelMemberDummy {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }
    }
}
