/*
 * @author     ucchy
 * @license    LGPLv3
 * @copyright  Copyright ucchy 2020
 */
package com.github.ucchyocean.lc3.command;

import java.util.HashMap;
import java.util.UUID;

/**
 * データマップ
 * @author ucchy
 */
public class DataMaps {

    /** 招待された人→招待されたチャンネル名 のマップ */
    protected static HashMap<String, String> inviteMap;

    /** 招待された人→招待した人 のマップ */
    protected static HashMap<String, String> inviterMap;

    /** tell/rコマンドの送信者→受信者 のマップ */
    protected static HashMap<String, String> privateMessageMap;
    /** UUID-only reply state for network mode; names are display/cache hints only. */
    protected static HashMap<UUID, UUID> privateReplyMap;
    protected static HashMap<UUID, String> privateReplyNames;

    static {
        inviteMap = new HashMap<String, String>();
        inviterMap = new HashMap<String, String>();
        privateMessageMap = new HashMap<String, String>();
        privateReplyMap = new HashMap<UUID, UUID>();
        privateReplyNames = new HashMap<UUID, String>();
    }

    public static synchronized void rememberPrivate(UUID sender, String senderName, UUID target, String targetName) {
        privateReplyMap.put(sender, target); privateReplyNames.put(sender, targetName);
        privateReplyMap.put(target, sender); privateReplyNames.put(target, senderName);
    }
}
