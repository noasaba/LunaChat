/*
 * @author     ucchy
 * @license    LGPLv3
 * @copyright  Copyright ucchy 2020
 */
package com.github.ucchyocean.lc3.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Multiverse-Core 4/5 linkage without forcing either API package on servers. */
public class MultiverseCoreBridge {

    private final Plugin plugin;
    private final boolean version5;

    private MultiverseCoreBridge(Plugin plugin, boolean version5) {
        this.plugin = plugin;
        this.version5 = version5;
    }

    /**
     * Multiverse-Core APIをロードする。
     * @param plugin Multiverse-Coreのプラグインインスタンス
     * @return ロードできた場合はブリッジ、未対応の場合はnull
     */
    public static MultiverseCoreBridge load(Plugin plugin) {
        if ( plugin == null || !plugin.getName().equalsIgnoreCase("Multiverse-Core") ) {
            return null;
        }
        try {
            plugin.getClass().getMethod("getApi");
            return new MultiverseCoreBridge(plugin, true);
        } catch (NoSuchMethodException ignored) {
            try {
                plugin.getClass().getMethod("getMVWorldManager");
                return new MultiverseCoreBridge(plugin, false);
            } catch (NoSuchMethodException unsupported) {
                plugin.getLogger().warning("Unsupported Multiverse-Core API: "
                        + plugin.getDescription().getVersion());
                return null;
            }
        }
    }

    /**
     * 指定されたワールドのエイリアス名を取得する。
     * @param worldName ワールド名
     * @return エイリアス名、取得できない場合はnull
     */
    public String getWorldAlias(String worldName) {
        if ( worldName == null ) return null;
        try {
            Object multiverseWorld = version5
                    ? getVersion5World(worldName) : getVersion4World(worldName);
            return getAliasOrName(multiverseWorld);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to read Multiverse world '"
                    + worldName + "': " + rootMessage(e));
            return null;
        }
    }

    /**
     * 指定されたワールドのエイリアス名を取得する。
     * @param world ワールド
     * @return エイリアス名、取得できない場合はnull
     */
    public String getWorldAlias(World world) {
        return world == null ? null : getWorldAlias(world.getName());
    }

    private Object getVersion4World(String worldName) throws ReflectiveOperationException {
        Object manager = invoke(plugin, "getMVWorldManager");
        return invoke(manager, "getMVWorld", new Class<?>[] { String.class }, worldName);
    }

    private Object getVersion5World(String worldName) throws ReflectiveOperationException {
        Object api = invoke(plugin, "getApi");
        Object manager = invoke(api, "getWorldManager");
        Object option = invoke(manager, "getWorld", new Class<?>[] { String.class }, worldName);
        if ( option == null || (Boolean)invoke(option, "isEmpty") ) return null;
        return invoke(option, "get");
    }

    private static String getAliasOrName(Object multiverseWorld)
            throws ReflectiveOperationException {
        if ( multiverseWorld == null ) return null;
        try {
            return (String)invoke(multiverseWorld, "getAliasOrName");
        } catch (NoSuchMethodException ignored) {
            String alias = (String)invoke(multiverseWorld, "getAlias");
            return alias == null || alias.isEmpty()
                    ? (String)invoke(multiverseWorld, "getName") : alias;
        }
    }

    private static Object invoke(Object target, String method)
            throws ReflectiveOperationException {
        return invoke(target, method, new Class<?>[0]);
    }

    private static Object invoke(Object target, String method,
            Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Method reflected = target.getClass().getMethod(method, parameterTypes);
        try {
            return reflected.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if ( cause instanceof ReflectiveOperationException ) {
                throw (ReflectiveOperationException)cause;
            }
            throw e;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while ( current.getCause() != null ) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
