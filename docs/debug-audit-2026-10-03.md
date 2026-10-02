# 総合デバッグ記録（2026-10-03）

対象は master のマージ済み状態 `f543014`。製品バージョンは
`4.0.22-SNAPSHOT`、Integration API は `1.0.0`、wire は `8` を維持する。

## 確認・修正した6件

| 不具合 | 修正 | 回帰テスト |
| --- | --- | --- |
| authority のリース失効後も tell 候補に古いネットワーク名を表示 | 未同期時はネットワーク候補を破棄。ローカルのオンライン候補は維持。終了時もキャッシュを消去 | `NetworkPlayerDirectoryTest` |
| ログアウト・接続・可視性変更が周期同期まで候補へ反映されない | 同期済み wire 8 ノードへプレイヤー辞書の差分を即時送信。JOIN/QUIT チャットは従来どおり Paper-local | `PlayerDirectoryDeliveryTest`、`NetworkPlayerDirectoryTest` |
| outbox の最終送信直後に ACK 検証用 payload が消える | 最終送信にもバックオフ期間分の ACK 待機時間を設ける。有効期限は延長しない | `ReliableOutboxTest` |
| Velocity の起動メタデータが POM と異なる `4.0.21` | `4.0.22-SNAPSHOT` に整合し、生成 JSON と POM の一致を検証 | `VelocityPluginMetadataTest` |
| プレイヤー発言の URL のクエリ・フラグメントを色コード処理が改変 | 色コード許可時の変換・不許可時の除去とも URL 内を保護。通常チャット経路も修正 | `UtilityUrlTest`、既存 `ClickableFormatTest` |
| gateway 終了時、キュー上の非同期受付 future が未完了のまま残る | shutdown 時に破棄された受付を false で完了。終了後の受付も拒否 | `BoundedMessageGatewayTest` |

outbox の最終 ACK と gateway 終了時の未完了については、修正前のコードで
新規テストの失敗を確認してから修正した。

## 検証結果と限界

- `mvn -o clean verify`: 成功。144 テスト、失敗・エラー・スキップ各0。
- Paper と Velocity の配布 JAR のメタデータはともに `4.0.22-SNAPSHOT`。
- API 成果物: `lunachat-api/target/lunachat-api-1.0.0.jar`。
- セッション・カタログ、外部受付・ACK、権限・メンバーシップ、可視性、
  フレーム検証、URL、起動メタデータの既存テストもクリーンビルドで実行。
- 実 Minecraft サーバー、実 Discord、本番ログによる検証は未実施。
  144 テストの成功は、すべての実機挙動や不具合不存在を保証しない。
- 辞書差分は best-effort。通信失敗時は次の成功した全体同期で修復する。
  古い差分を再接続後に再生しないため、差分を再送 outbox には登録しない。
- メモリ内 outbox・重複排除状態の再起動時消失は今回変更していない。

## 実機で追加確認する項目

`mc_server_test` で検証する場合は、wire 8 の Velocity と2台以上の Paper に
今回の JAR を配置し、次の操作と時刻を両側のログとともに記録する。
この記録作成時点では、同名の既存チャットが見つからず実機依頼は未完了。

1. 別 Paper の利用者のログイン・移動・ログアウト直後の tell 候補。
   ログアウトした名前が消え、他 Paper で JOIN/QUIT が余計に表示されないこと。
2. SVSync の HIDDEN/PUBLIC 変更と tell 候補の反映。
3. authority 通信を止め、リース失効後に遠隔候補が消えること。
   復旧・再同期で現在の候補だけが戻ること。
4. 色コード権限の有無それぞれで、チャンネル・通常チャットに
   `https://example.org/?x=1&a=2&b=3#abc` を投稿し、表示とクリック先を比較。
5. ACK を遅らせて最終送信後の待機猶予内に返し、受付結果を確認。
   TTL を超えた投稿が誤って成功しないことも確認する。
6. Paper のプレイヤーが0人の状態と入場後を比較し、plugin messaging の
   キャリア不足をセッション・カタログ同期失敗と区別する。

## 10月2日の同期障害について

最終送信の ACK 消失は `DELIVERY_UNAVAILABLE` の原因になり得るが、
今回の本番障害原因と断定する証拠はない。`partial backend coverage` は
全バックエンドの表示完了を意味しないため、未配信の有無も確定できない。

再発時は同じ Discord 投稿 ID ごとに、Velocity の受付結果・logicalMessageId・
バックエンド別 outbox 登録／送信／ACK と、各 Paper の受信・処理・ACK を
同じ時刻軸で突き合わせる。対象投稿の前後5分と起動からのセッション同期ログ、
各バックエンドのオンライン人数・バージョン・wire・時計差を保存する。
設定の共有時は `sharePass`、Discord トークン等を伏せる。

LunaBridge 担当チャットには outbox の発見を共有し、API 成果物の利用と
beta.28 の再試行・重複排除仕様の確認を依頼している。
