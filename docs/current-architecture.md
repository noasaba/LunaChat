# LunaChat 現状アーキテクチャ調査

調査日: 2026-08-25  
対象: `c/26.2-compat-issue-fixes` / `3ddeead`   
対象バージョン: LunaChat 3.0.17

この文書は、LunaChat fork の現状を記録するための Task 1 成果物です。ここでは設計変更や実装方針の決定は行わず、現行コードの責務と制約を整理します。

## 1. リポジトリとビルド

- ビルドシステム: Maven の単一 `jar` プロジェクト (`pom.xml`)
- Java: Java 8 bytecode (`maven-compiler-plugin` の `release=8`)
- コンパイル対象: Spigot API `26.2-R0.1-SNAPSHOT`
- Bungee API: `1.21-R0.4`
- 配布: `maven-shade-plugin` で Gson、Commons Lang、Annotations、bstats 等を relocation して単一 JAR 化
- プロジェクトバージョン: `3.0.17`
- ライセンス: LGPLv3 (`LICENSE` および `pom.xml`)
- Java パッケージは旧 API 系 `com.github.ucchyocean.lc` と現行実装系 `com.github.ucchyocean.lc3` の二系統

Maven の module 構成はなく、Paper/Bukkit、BungeeCord、Standalone の実装を同じ成果物へコンパイルします。`lunachat-api`、`lunachat-core`、`lunachat-paper`、`lunachat-velocity` の分離はまだありません。

## 2. 実行モードと主要クラス

`LunaChatMode` は `BUKKIT`、`BUNGEE`、`STANDALONE` を持ちます。起動実装は次のとおりです。

| 実行形態 | エントリポイント | 主な責務 |
| --- | --- | --- |
| Paper/Spigot | `com.github.ucchyocean.lc3.LunaChatBukkit` | JavaPlugin lifecycle、Bukkit event、コマンド、外部プラグイン連携、plugin messaging送信 |
| BungeeCord | `com.github.ucchyocean.lc3.LunaChatBungee` | Proxy plugin lifecycle、Bungee event、コマンド、plugin messaging受信、全接続プレイヤーへの配信 |
| Standalone | `com.github.ucchyocean.lc3.LunaChatStandalone` | 非プラットフォーム実行用の初期化。現在の送信処理は `StandaloneChannel` の標準出力/TODO実装に留まる |

共通状態は `com.github.ucchyocean.lc3.LunaChat` の static facade が保持します。`PluginInterface` 経由で config、API、UUID cache、logger、非同期実行を各 runtime から供給します。

チャンネル意味論の中心は `com.github.ucchyocean.lc3.channel.Channel`、作成・検索・保存の窓口は `ChannelManager`、Bukkit/Bungee/Standalone 固有の表示・ログ処理は各 Channel subclass が担当します。

## 3. 公開 API とイベント

### 現行 API

- `com.github.ucchyocean.lc.LunaChatAPI`: 旧公開 API の facade。チャンネル一覧、作成/削除、default channel、hidelist、dictionary、Japanize 等を提供します。
- `com.github.ucchyocean.lc3.LunaChatAPI`: 現行 API。`ChannelManager` が実装します。
- `com.github.ucchyocean.lc.LunaChatAPIImpl`: 現行実装を旧 `Channel`/`ChannelPlayer` 型へ変換する互換層です。
- `com.github.ucchyocean.lc3.LunaChatBukkit#getLunaChatAPI`、`LunaChatBungee#getLunaChatAPI`、`LunaChatStandalone#getLunaChatAPI`: runtime ごとの API 取得口です。

### 現状の境界

現行 API は完全な platform-independent API ではありません。旧 API とイベントには Bukkit の `CommandSender`/`Player`、Bungee の sender/player、Bungee chat component、`ChannelMember` 等が現れます。また `LunaChatBukkit`/`LunaChatBungee` の getter から platform-specific bridge と config が取得できます。

イベントは `BukkitEventSender` / `BungeeEventSender` が runtime 固有 event へ変換します。主なイベント系列は pre-chat、channel chat/message、channel create/remove、member change、option change、post-Japanize です。イベント listener が受理メッセージを一元的に観測する API はまだありません。

## 4. コマンドと入力経路

### Paper/Bukkit (`plugin.yml`)

- `/lunachat` (`/lc`, `/ch`): join、leave、list、invite/accept/deny、kick、ban/pardon、mute/unmute、hide/unhide、info、log、create/remove、format、moderator、dictionary、option、template、set default、reload、help
- `/tell` (`/msg`, `/message`, `/m`, `/t`): private message
- `/reply` (`/r`): private message reply
- `/japanize` (`/jp`): Japanize on/off

`LunaChatBukkit#onCommand` が `LunaChatMessageCommand`、`LunaChatReplyCommand`、`LunaChatCommand` 等へ振り分けます。

### BungeeCord

`LunaChatBungee` は同等の command 群を Bungee command API へ登録します。`LunaChatCommandBungee`、`MessageCommandBungee`、`ReplyCommandBungee`、`JapanizeCommandBungee` が実装します。

### 通常チャット

Bukkit は `BukkitEventListener` が `AsyncPlayerChatEvent` を複数 priority で監視します。Bungee は `BungeeEventListener` が `ChatEvent` を監視します。両者とも channel 解決、permission/mute、NG word mask、color code、Japanize、フォーマット、配信、ログの順に処理します。

## 5. チャンネルと保存形式

### チャンネル本体

`ChannelManager` は data folder 下の `channels/*.yml` を `Channel.serialize`/`deserialize` で読み書きします。ファイル名は channel name で、ロード時の map key は小文字 channel name です。保存される主な属性は次のとおりです。

- `name`, `alias`, `desc`, `format`, `password`, `visible`, `color`
- `members`, `moderator`, `banned`, `muted`, `hided`
- `broadcast`, `world`, `range`, `allowcc`, `japanize`
- `ban_expires`, `mute_expires`

1:1 channel (`name` に `>` を含むもの) は保存されません。channel の rename はファイル名変更ではなく、現状 API の `setName`/command の範囲では stable ID を持たない name-based model です。

### 付随ファイル

`ChannelManager` は次の YAML を data folder 直下へ保存します。

| ファイル | 内容 | 現在の識別子 |
| --- | --- | --- |
| `defaults.yml` | player の default channel | player name key |
| `templates.yml` | message format template | template key |
| `japanize.yml` | player の Japanize on/off | player name key |
| `dictionary.yml` | Japanize dictionary | dictionary key/value |
| `hidelist.yml` | 誰が誰を hide するか | hide 対象の `ChannelMember.toString()` |
| `uuidcache.yml` | UUID と最後に見た name の cache | UUID key → name value |

保存は atomic migration/backup ではなく、各変更時または reload/save のタイミングで `YamlConfig` が直接ファイルを書き換えます。migration schema、version marker、future schema rejection はありません。

## 6. プレイヤー、membership、mute/ban

`ChannelMember` が player、console、block、external-like member の抽象です。Bukkit の現行 player member は UUID (`ChannelMemberPlayer#toString()` は `$` + UUID) を内部 ID として使い、表示名解決には UUID cache/OfflinePlayer を利用します。Bungee 側も UUID member を基本にしますが、古い name-based `ChannelPlayer`/互換 facade が残っています。

channel の members/moderator/banned/muted/hided は `List<ChannelMember>`、期限は `Map<ChannelMember, Long>` でメモリ上に保持し、channel YAML には文字列表現として保存します。期限確認は `ExpireCheckTask` が非同期 timer で行います。

招待と private message/reply の状態は永続化されません。

- `DataMaps.inviteMap` / `inviterMap`: name → channel/inviter
- `DataMaps.privateMessageMap`: sender name → recipient name
- Bungee の reply history: `LunaChatBungee#history`（name key）

これらは reload/restart で失われ、上限・TTL・UUID key はありません。

## 7. Bukkit ↔ Bungee 配送

network pass-through は plugin messaging channel `lunachat:message` を使用します。

1. Bukkit の `BukkitEventListener` が `BungeePassThroughMode` 時に `BukkitChatMessage` を serialize。
2. `LunaChatBukkit#sendPluginMessage` が plugin message を送信。
3. Bungee の `BungeeEventListener#onPluginMessageReceived` が deserialize。
4. 受信 receiver と message member の name を比較し、Bungee 内の通常 channel 処理へ渡します。
5. Bungee が各 `ProxiedPlayer` へ Bungee chat component を送信します。

現行 wire payload は `DataInputStream.writeUTF` で name、displayName、prefix、suffix、location、id、message を並べた `BukkitChatMessage` です。protocol version、session、authentication、sequence、nonce、frame ID、logical message ID、ACK、retry、replay protection、dedup はありません。送信元の正当性検証は receiver/name 比較が中心で、client-origin spoofing 対策として設計された secure frame ではありません。

ネットワーク構成ではVelocityがchannel authorityとPaper間routingを担当し、Paperは認証済みSTATEの読み取り専用replicaとして動作します。Paper単体構成では従来どおりPaper内で完結します。

## 8. メッセージ処理と Japanize

`Channel#chat` が現在の中心処理です。

1. 発言 permission と mute を確認
2. marker、NG word mask、color code を処理
3. `LunaChatChannelChatEvent` を送出
4. UTF-8/半角カナ判定で Japanize の要否を決定
5. `JapanizeType` と player/channel 設定に応じて同期または遅延変換
6. Channel subclass が受信者へ表示し、`LunaChatLogger` へ記録
7. NG word action が BAN/KICK/MUTE の場合に状態変更

通常チャットと Bungee 側の global chat は `BukkitEventListener`/`BungeeEventListener` に別実装があります。`Japanizer` は dictionary key を置換用 marker へ変換し、`GoogleIME` は外部 Google endpoint を使用します。

受理された論理メッセージを表す `messageId`、origin metadata、accepted observer、external publish result はまだ存在しません。

## 9. plugin lifecycle

### Bukkit

`LunaChatBukkit#onEnable` で mode/config/messages/UUID cache/ChannelManager/logger を初期化し、Vault、dynmap、Multiverse-Core、mcMMO を検出して bridge/listener を登録します。Bukkit events、commands、非同期 expire checker、outgoing plugin channel を登録します。`onDisable` は expire checker を停止しますが、plugin channel、static facade、observer相当の cleanup 機構はありません。

### BungeeCord

`LunaChatBungee#onEnable` で config/messages/history/UUID cache/ChannelManager/logger を初期化し、permission bridge、commands、Bungee listener、outgoing/incoming channel を登録します。明示的な `onDisable` cleanup はありません。

### Standalone

`LunaChatStandalone#onEnable` は共通 config/manager を初期化しますが、`ChannelMember` の standalone 実装と実配送は TODO です。

reload は command から config/data を再読込します。旧 instance の API provider や listener の lifecycle contract はありません。

## 10. 現状の責務・制約まとめ

### 既にあるもの

- Paper/Bungee/Standalone を単一 JAR で共有する channel/chat domain
- channel の membership、moderator、mute、ban、期限、hide
- Bukkit/Bungee events、commands、Japanize、NG word、ログ
- UUID を利用する現行 player member と UUID cache
- Bungee pass-through の最小 plugin messaging

### Task 2 以降で検討が必要な不足

- platform-independent な `lunachat-api` と内部実装の分離
- stable `ChannelId` と channel migration/backup/schema version
- accepted message の immutable public model と origin
- external publish、idempotency、bounded observer dispatch
- Velocity authority/edge、player presence、cross-backend routing
- authenticated secure frame、logical/frame ID 分離、ACK/retry/dedup/replay protection
- bounded queue/dedup storage と failure isolation
- UUID-only の tell/reply/invite/ban/mute state
- API TCK、binary compatibility test、process integration test

## 11. 参照した主要ファイル

- `pom.xml`, `LICENSE`, `src/main/resources/plugin.yml`, `src/main/resources/bungee.yml`
- `src/main/java/com/github/ucchyocean/lc3/LunaChatBukkit.java`
- `src/main/java/com/github/ucchyocean/lc3/LunaChatBungee.java`
- `src/main/java/com/github/ucchyocean/lc3/LunaChatStandalone.java`
- `src/main/java/com/github/ucchyocean/lc3/channel/Channel.java`
- `src/main/java/com/github/ucchyocean/lc3/channel/ChannelManager.java`
- `src/main/java/com/github/ucchyocean/lc3/member/ChannelMember.java`
- `src/main/java/com/github/ucchyocean/lc3/bukkit/BukkitEventListener.java`
- `src/main/java/com/github/ucchyocean/lc3/bungee/BungeeEventListener.java`
- `src/main/java/com/github/ucchyocean/lc3/messaging/BukkitChatMessage.java`
- `src/main/java/com/github/ucchyocean/lc3/UUIDCacheData.java`
- `src/main/java/com/github/ucchyocean/lc3/command/DataMaps.java`
