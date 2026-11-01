# 操作の参照と検証

Pocket Casts の公開 Android 実装の [SwipeRowActions](https://github.com/Automattic/pocket-casts-android/blob/main/modules/services/views/src/main/java/au/com/shiftyjelly/pocketcasts/views/swipe/SwipeRowActions.kt) と [SwipeRow](https://github.com/Automattic/pocket-casts-android/blob/main/modules/services/compose/src/main/java/au/com/shiftyjelly/pocketcasts/compose/swipe/SwipeRow.kt) を参照しました。ソースコードの転載はせず、本アプリ用に実装しています。

- 右スワイプでキュー操作、左スワイプで整理用の操作を引き出します。複数キューがあるため、追加先は選択式です。
- 本アプリの左スワイプの操作は「再生済み・お気に入り・保存」。キュー内では「再生済み」の代わりに、そのキューから外す操作を表示します。横スワイプはボタンを表示し、タップで確定します。
- ミニプレーヤーは上スワイプで開き、プレーヤーのヘッダーは下スワイプで閉じます。
- 番組個別の速度は全体の既定値を上書きし、キューの自動遷移でも適用します。個別設定を解除すると全体の速度に戻ります。

添付OPMLの123番組（36,486エピソード、最大約32MiBのRSSを含む）を取得し、キャッシュした実レスポンスで取り込み・全番組更新を検証しました。256MiBのテストヒープ制限で完走しています。入力ファイルと取得データはリポジトリに含めていません。

画面用の初期一覧は最新1,000件、追加表示は500件ずつ、番組ページは150件ずつ読み込みます。全履歴はDBに保持し、キュー・お気に入り・ダウンロード・再生中の回は初期一覧の範囲外でも利用できます。RSSは文字列として一括取得せずストリーム解析し、更新は最大3接続です。
