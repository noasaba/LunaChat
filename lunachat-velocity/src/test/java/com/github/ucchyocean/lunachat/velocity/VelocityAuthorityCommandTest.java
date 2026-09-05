package com.github.ucchyocean.lunachat.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VelocityAuthorityCommandTest {
    @TempDir Path directory;
    @Test void mutationsRequireAdminPermissionButConsoleIsAllowed() throws Exception {
        AuthorityChannelStore store = new AuthorityChannelStore(directory);
        ProxyServer proxy = proxy(ProxyServer.class, (method, args) -> method.getName().equals("getAllServers") ? List.of() : value(method.getReturnType()));
        ChannelIdentifier channel = proxy(ChannelIdentifier.class, (method, args) -> method.getName().equals("getId") ? "lunachat:test" : value(method.getReturnType()));
        try (var authority = new VelocityNetworkAuthority(proxy, LoggerFactory.getLogger("command-test"), channel, store,
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 8, 8)) {
            var command = new VelocityAuthorityCommand(authority);
            List<String> messages = new ArrayList<>(); CommandSource denied = source(CommandSource.class, false, messages);
            assertFalse(command.hasPermission(invocation(denied, "create", "global")));
            command.execute(invocation(denied, "create", "global"));
            assertTrue(authority.channels().isEmpty()); assertTrue(messages.getFirst().contains(VelocityAuthorityCommand.ADMIN_PERMISSION));
            CommandSource admin = source(CommandSource.class, true, new ArrayList<>());
            assertTrue(command.hasPermission(invocation(admin, "create", "global"))); command.execute(invocation(admin, "create", "global"));
            assertEquals("global", authority.channels().getFirst().name());
            CommandSource console = source(ConsoleCommandSource.class, false, new ArrayList<>());
            assertTrue(command.hasPermission(invocation(console, "list"))); command.execute(invocation(console, "default", "global"));
            assertEquals("global", authority.snapshotSettings().defaultChannel());
        }
    }
    private static SimpleCommand.Invocation invocation(CommandSource source, String... args) { return proxy(SimpleCommand.Invocation.class, (m, a) -> switch (m.getName()) { case "source" -> source; case "arguments" -> args; case "alias" -> "lunachat"; default -> value(m.getReturnType()); }); }
    private static <T> T source(Class<T> type, boolean permission, List<String> messages) { return proxy(type, (m, a) -> switch (m.getName()) { case "hasPermission" -> permission; case "sendPlainMessage" -> { messages.add((String) a[0]); yield null; } default -> value(m.getReturnType()); }); }
    private interface Handler { Object call(java.lang.reflect.Method method, Object[] args); }
    private static <T> T proxy(Class<T> type, Handler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (o, m, a) -> handler.call(m, a == null ? new Object[0] : a))); }
    private static Object value(Class<?> type) { if (!type.isPrimitive()) return null; if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return null; }
}
