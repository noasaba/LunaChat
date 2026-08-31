package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ChannelQueryTest {
    @Test void stableIdSurvivesRenameAndPaginationIsBounded() {
        InMemoryChannelDirectory directory = new InMemoryChannelDirectory();
        ChannelId stable = ChannelId.random();
        directory.put(new ChannelDescriptor(stable, "old-name", Set.of("old"), true));
        BoundedChannelQueryService query = new BoundedChannelQueryService(directory);
        assertEquals(stable, query.findByNameOrAlias("old").orElseThrow().id());
        directory.put(new ChannelDescriptor(stable, "new-name", Set.of("old-name"), true));
        assertEquals("new-name", query.find(stable).orElseThrow().name());
        assertEquals(stable, query.findByNameOrAlias("old-name").orElseThrow().id());
        directory.put(new ChannelDescriptor(ChannelId.random(), "second", Set.of(), true));
        ChannelPage first = query.listVisibleToIntegration(new ChannelPageRequest(1, null));
        assertEquals(1, first.channels().size()); assertNotNull(first.nextCursor());
        ChannelPage second = query.listVisibleToIntegration(new ChannelPageRequest(1, first.nextCursor()));
        assertEquals(1, second.channels().size()); assertNull(second.nextCursor());
    }

    @Test void negativeOpaqueCursorIsRejectedAsInvalidInput() {
        BoundedChannelQueryService query = new BoundedChannelQueryService(new InMemoryChannelDirectory());
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1:-1".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class,
                () -> query.listVisibleToIntegration(new ChannelPageRequest(10, cursor)));
    }
}
