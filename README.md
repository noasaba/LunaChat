LunaChat
========

Build Status : [![Build Status](https://travis-ci.org/ucchyocean/LunaChat.svg?branch=master)](https://travis-ci.org/ucchyocean/LunaChat)

チャンネルチャットプラグイン<br />
<br />
ダウンロードはこちらから<br />
https://github.com/ucchyocean/LunaChat/releases<br />
<br />
コマンドリファレンスや、設定リファレンスは、こちらから<br />
https://github.com/ucchyocean/LunaChat/wiki/Commands<br />
https://github.com/ucchyocean/LunaChat/wiki/Config<br />
<br />
本プラグインのライセンスは、LGPLv3に従います。ライセンス条文は下記を参照。<br />
http://sourceforge.jp/magazine/07/09/05/017211<br />

対応環境
--------

- LunaChat 4.0: Paper / Minecraft API 26.2、Velocity API 4.1.x、Java 25
- 単体 Paper は standalone authority、Velocity 配下の Paper は network edge として動作
- 従来の `com.github.ucchyocean.lc` / `lc3` API、コマンド、日本語変換、外部連携 API は削除・置換していません
- Multiverse-Core 4.x / 5.x および mcMMO の旧・新チャット API を自動判別します

ビルド成果物は `lunachat-paper/target/LunaChat.jar` と
`lunachat-velocity/target/LunaChat-Velocity.jar` です。公開 Integration API は
`lunachat-api`、実装非依存 TCK は `lunachat-api-testkit` に分離されています。
配布用のZIP（Paper、Velocity、API本体・Javadoc、`lunachat-api-testkit.jar`、
`LICENSE`、`NOTICE`を収録）は `./scripts/build-distribution.sh` で生成できます。
各JARにも `LICENSE` と `NOTICE` を同梱しています。

設計、導入、upgrade 手順は [Integration API design](docs/integration-api.md) を参照してください。
