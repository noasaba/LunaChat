package com.github.ucchyocean.lc3.integration;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class MembershipImportToolTest {
    @Test public void exportUsesCanonicalIdentityAndPreservesSourceAndExistingOutput() throws Exception {
        var dir = Files.createTempDirectory("membership-export");
        String id = UUID.randomUUID().toString(), player = UUID.randomUUID().toString();
        var catalog = dir.resolve("channels.properties");
        Files.writeString(catalog, "schema=1\nchannel." + id + ".name=global\n");
        var source = dir.resolve("global.yml");
        String yaml = "channel_id: " + id + "\nvisible: true\npassword: ''\nworld: false\nbanned: []\nmembers:\n- '$" + player + "'\n";
        Files.writeString(source, yaml);
        var output = dir.resolve("import.properties");
        MembershipImportTool.export(dir, catalog, output);
        Properties seed = new Properties();
        try (var stream = Files.newInputStream(output)) { seed.load(stream); }
        assertEquals(player, seed.getProperty("channel." + id + ".members"));
        assertEquals("true", seed.getProperty("channel." + id + ".joinable"));
        assertEquals(yaml, Files.readString(source));
        assertThrows(FileAlreadyExistsException.class, () -> MembershipImportTool.export(dir, catalog, output));
        Files.writeString(source, yaml.replace(id, UUID.randomUUID().toString()));
        assertThrows(java.io.IOException.class, () -> MembershipImportTool.export(dir, catalog, dir.resolve("bad.properties")));
        assertFalse(Files.exists(dir.resolve("bad.properties")));
        Files.writeString(source, yaml.replace("password: ''", "password: 'secret'"));
        assertThrows(java.io.IOException.class, () -> MembershipImportTool.export(dir, catalog, dir.resolve("private.properties")));
        assertFalse(Files.exists(dir.resolve("private.properties")));
    }
}
