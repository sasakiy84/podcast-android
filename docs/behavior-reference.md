# 操作と保存データ

## 番組の登録・検索・更新

検索タブはApple Podcastsの番組検索と、主要プラットフォームの番組URL・公開RSS / Atomからの登録に使う。エピソード画面の検索は、登録済みの全エピソードのタイトル・説明が対象。

新着は設定した間隔（1〜24時間、OSの制御で遅れる場合がある）で確認する。起動時は保存済みのデータを表示し、手動更新や番組・エピソード一覧の引っ張り更新も使える。

## キューと保存音声

同じ回を複数のキューへ追加でき、再生済みになると全キューから外れる。新着の自動追加は番組側から設定し、含むキーワード・除外語と自動保存を指定できる。過去の未再生分はルール設定時に選んだ場合だけ追加する。

音声ファイルは全キューで共有する。キューから外しても音声は残り、実際に再生が完了すると保存音声を自動削除する。末尾スキップも完了に含み、手動で再生済みにしただけでは音声を削除しない。履歴とお気に入りは残る。

設定画面ではWi-Fi限定保存、更新間隔、全体の速度、既定キュー、保存容量・更新結果を変更・確認できる。OPMLの入出力も設定画面から行う。OPMLに含まれるのは購読番組の一覧で、キュー・進捗・履歴・音声は含まれない。

## 操作と再生

- 右スワイプで表示されるキューボタンは、設定の既定キューへ追加する。追加先を指定する場合は、長押しメニューや詳細・再生画面のキュー選択を使う。
- 左スワイプで再生済み・お気に入り・保存の操作を表示する。キュー内では再生済みの代わりに、そのキューから外す操作になる。操作はボタンのタップで確定する。
- ミニプレーヤーは上スワイプで開き、ヘッダー・アートワークの下スワイプで閉じる。説明やチャプターをスクロール中は、先頭まで戻ってから下に引くと閉じる。再生画面のタブは左右スワイプでも切り替えられる。
- 番組個別の速度は全体の速度を上書きし、キューの自動遷移でも適用する。番組個別の設定を解除すると全体の速度へ戻る。
- Podlove / Podcasting 2.0 JSONチャプターに対応する。現在の回のチャプターへ移動すると一時停止状態を保ち、別の回を指定した場合はその位置から再生する。

取り消し・全文検索・共有と再生位置の扱いは [library-operations.md](library-operations.md)、実装上の制約は [コードの構成](architecture.md) にまとめる。

## 参考にした実装と検証

Pocket Castsの公開Android実装の [SwipeRowActions](https://github.com/Automattic/pocket-casts-android/blob/main/modules/services/views/src/main/java/au/com/shiftyjelly/pocketcasts/views/swipe/SwipeRowActions.kt) と [SwipeRow](https://github.com/Automattic/pocket-casts-android/blob/main/modules/services/compose/src/main/java/au/com/shiftyjelly/pocketcasts/compose/swipe/SwipeRow.kt) を操作の参考にした。本アプリ用に実装しており、ソースコードは転載していない。

rebuild.fmの公開RSS（`https://feeds.rebuild.fm/rebuildfm`）でチャプターと説明文のリンクを確認した。提供されたOPMLと実RSSの大量取り込みの検証結果は [library-operations.md](library-operations.md#保存形式と検証) に記録している。実データはリポジトリに含めない。検証の再実行は [開発・テスト](testing.md) を参照する。
