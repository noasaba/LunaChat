/*
 * @author     ucchy
 * @license    LGPLv3
 * @copyright  Copyright ucchy 2020
 */
package com.github.ucchyocean.lc3.command;

import com.github.ucchyocean.lc3.Messages;
import com.github.ucchyocean.lc3.member.ChannelMember;
import com.github.ucchyocean.lc3.util.PlayerVisibility;
import com.github.ucchyocean.lc3.member.ChannelMemberBukkit;

/**
 * 1:1チャット受信コマンド
 * @author ucchy
 */
public class LunaChatReplyCommand extends LunaChatMessageCommand {

    /**
     * コマンドを実行したときに呼び出されるメソッド
     * @param sender 実行者
     * @param label 実行されたコマンドのラベル
     * @param args 実行されたコマンドの引数
     * @return 実行したかどうか（falseを返した場合、サーバーがUsageを表示する）
     * @see com.github.ucchyocean.lc3.command.LunaChatMessageCommand#execute(com.github.ucchyocean.lc3.member.ChannelMember, java.lang.String, java.lang.String[])
     */
    @Override
    public boolean execute(ChannelMember sender, String label, String[] args) {

        // senderからChannelMemberを作成する
        ChannelMember inviter = ChannelMember.getChannelMember(sender);

        if ("network_edge".equals(com.github.ucchyocean.lc3.LunaChat.getConfig().getIntegrationRole())
                && inviter instanceof ChannelMemberBukkit bukkit && bukkit.getPlayer() != null) {
            java.util.UUID target = DataMaps.privateReplyMap.get(bukkit.getPlayer().getUniqueId());
            String targetName = DataMaps.privateReplyNames.get(bukkit.getPlayer().getUniqueId());
            if (target == null || targetName == null) { sender.sendMessage(Messages.errmsgNotfoundPM()); return true; }
            if (args.length == 0) { sender.sendMessage(Messages.cmdmsgReplyInviter(inviter.getName(), targetName)); return true; }
            String body = String.join(" ", args);
            sendTellMessage(inviter, targetName, body);
            return true;
        }

        // 会話相手を履歴から取得する
        String invitedName = DataMaps.privateMessageMap.get(inviter.getName());

        // 引数が無ければ、現在の会話相手を表示して終了する
        if (args.length == 0) {
            ChannelMember target = invitedName == null ? null
                    : ChannelMember.getChannelMember(invitedName);
            if ( invitedName == null || target == null || !target.isOnline()
                    || !PlayerVisibility.isVisibleTo(inviter, target) ) {
                sender.sendMessage(Messages.cmdmsgReplyInviterNone(inviter.getName()));
            } else {
                sender.sendMessage(Messages.cmdmsgReplyInviter(inviter.getName(), invitedName));
            }
            return true;
        }

        // 会話相手がからっぽなら、コマンドを終了する。
        if ( invitedName == null ) {
            sender.sendMessage(Messages.errmsgNotfoundPM());
            return true;
        }

        // メッセージを取得する
        StringBuilder message = new StringBuilder();
        for ( int i=0; i<args.length; i++ ) {
            message.append(args[i] + " ");
        }

        sendTellMessage(inviter, invitedName, message.toString().trim());

        return true;
    }
}
