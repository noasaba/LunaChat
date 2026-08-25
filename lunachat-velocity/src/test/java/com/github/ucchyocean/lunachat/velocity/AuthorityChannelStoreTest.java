package com.github.ucchyocean.lunachat.velocity;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthorityChannelStoreTest {
    @TempDir Path directory;

    @Test void stateIsDurableAndRenameKeepsStableId() throws Exception {
        ChannelId id = ChannelId.random();
        AuthorityChannelStore first = new AuthorityChannelStore(directory);
        first.applyProposal(List.of(new ChannelDescriptor(id, "global", Set.of("g"), true)));
        first.applyProposal(List.of(new ChannelDescriptor(id, "renamed", Set.of("g"), true)));
        AuthorityChannelStore reloaded = new AuthorityChannelStore(directory);
        assertEquals(1, reloaded.snapshot().size());
        assertEquals(id, reloaded.snapshot().getFirst().id());
        assertEquals("renamed", reloaded.snapshot().getFirst().name());
    }

    @Test void futureSchemaFailsClosed() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("channels.properties"), "schema=999\n");
        assertThrows(IOException.class, () -> new AuthorityChannelStore(directory));
    }
}
