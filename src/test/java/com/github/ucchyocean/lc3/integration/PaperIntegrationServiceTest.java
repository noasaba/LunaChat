package com.github.ucchyocean.lc3.integration;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.MessageAuthor;
import com.github.ucchyocean.lunachat.api.MessageOrigin;
import com.github.ucchyocean.lunachat.api.OriginKind;
import org.junit.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PaperIntegrationServiceTest {
    private static final Instant CREATED = Instant.parse("2026-08-25T00:00:00Z");

    @Test public void externalNamespaceIsNotAChatDisplaySuffix() {
        AcceptedMessage external = new AcceptedMessage(UUID.randomUUID(), ChannelId.random(), "global",
                new MessageOrigin(OriginKind.EXTERNAL, "lunabridge:discord", "discord-1"),
                new MessageAuthor.External("lunabridge:discord", "user-1", "Noa"),
                "backend", "aaaa", CREATED, CREATED.plusSeconds(300));
        assertNull(PaperIntegrationService.displaySourceFor(external));
    }

    @Test public void playerAndSystemNamespacesRemainAvailableToLegacyRendering() {
        AcceptedMessage player = new AcceptedMessage(UUID.randomUUID(), ChannelId.random(), "global",
                new MessageOrigin(OriginKind.MINECRAFT, "lunachat.minecraft", "minecraft-1"),
                new MessageAuthor.Player(UUID.randomUUID(), "player", "Player"),
                "backend", "hello", CREATED, CREATED.plusSeconds(300));
        AcceptedMessage system = new AcceptedMessage(UUID.randomUUID(), ChannelId.random(), "global",
                new MessageOrigin(OriginKind.SYSTEM, "lunachat.system", "system-1"),
                new MessageAuthor.System("LunaChat"), "backend", "notice", CREATED, CREATED.plusSeconds(300));
        assertEquals("lunachat.minecraft", PaperIntegrationService.displaySourceFor(player));
        assertEquals("lunachat.system", PaperIntegrationService.displaySourceFor(system));
    }
}
