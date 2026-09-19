# Fire TV 物理リモコン キーコード & イベントハンドリング完全リファレンス

## 1. Alexa 音声認識リモコン 物理キーコード一覧

| リモコンボタン | Android KeyEvent 定数 | キーコード (Int) | adb 入力コマンド | 主な役割・推奨ハンドリング |
| :--- | :--- | :---: | :--- | :--- |
| **D-Pad 上** | `KEYCODE_DPAD_UP` | `19` | `adb shell input keyevent 19` | 上部カード/メニューへフォーカス移動 |
| **D-Pad 下** | `KEYCODE_DPAD_DOWN` | `20` | `adb shell input keyevent 20` | 下部カード/関連動画カルーセル表示 |
| **D-Pad 左** | `KEYCODE_DPAD_LEFT` | `21` | `adb shell input keyevent 21` | 左側移動 / 再生中 10秒巻き戻し |
| **D-Pad 右** | `KEYCODE_DPAD_RIGHT` | `22` | `adb shell input keyevent 22` | 右側移動 / 再生中 10秒早送り |
| **決定 / 選択** | `KEYCODE_DPAD_CENTER` | `23` | `adb shell input keyevent 23` | 動画再生 / 一時停止トグル / チェック変更 |
| **戻る (Back)** | `KEYCODE_BACK` | `4` | `adb shell input keyevent 4` | 前画面復帰 / HUD非表示 / アプリ終了 |
| **ホーム (Home)** | `KEYCODE_HOME` | `3` | `adb shell input keyevent 3` | Fire OS ホーム画面へ戻る (OS占有) |
| **メニュー (三本線)** | `KEYCODE_MENU` | `82` | `adb shell input keyevent 82` | コンテキストメニュー / チャンネル登録トグル |
| **再生 / 一時停止** | `KEYCODE_MEDIA_PLAY_PAUSE` | `85` | `adb shell input keyevent 85` | 即時再生・停止の切り替え |
| **再生専用** | `KEYCODE_MEDIA_PLAY` | `126` | `adb shell input keyevent 126` | 再生開始 |
| **一時停止専用** | `KEYCODE_MEDIA_PAUSE` | `127` | `adb shell input keyevent 127` | 一時停止 |
| **早送り (FF)** | `KEYCODE_MEDIA_FAST_FORWARD` | `90` | `adb shell input keyevent 90` | 10秒シーク / 長押しで倍速切り替え |
| **巻き戻し (RW)** | `KEYCODE_MEDIA_REWIND` | `89` | `adb shell input keyevent 89` | 10秒シーク / 長押しで等速リセット |
| **音声検索 (マイク)**| `KEYCODE_SEARCH` | `84` | `adb shell input keyevent 84` | Alexa 検索インテント発火 |

---

## 2. Fire TV 固有の「フォーカス消失（ブラックホール）」現象と対策

### 現象
テレビ画面上でリモコンの十字キーを押しても、どこにもフォーカス枠が表示されず、どのボタンを押しても反応しなくなる現象。
タッチスクリーンがない Fire TV においては**致命的なアプリ停止（ソフトロック）**となります。

### 根本原因パターン
1. **Focusable の欠落**: 動的に追加された View やカスタムレイアウトに `android:focusable="true"` および `android:focusableInTouchMode="true"` が設定されていない。
2. **非表示 View へのフォーカス残存**: フォーカスを持っていた View を `visibility = View.GONE` や `View.INVISIBLE` にした際、次のフォーカス先を指定していない。
3. **RecyclerView / GridView のリサイクル**: アダプターの `notifyDataSetChanged()` 時に選択状態がリセットされ、フォーカスが親コンテナに奪われる。

### デバッグコマンド
```bash
# 現在フォーカスを持っている Window と View をリアルタイム特定
adb shell "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"

# フォーカス移動イベントを Logcat で監視
adb logcat -s ViewRootImpl:* FocusHandling:*
```

### 再発防止コード設計
```kotlin
// View を非表示にする前に、親または次の要素にフォーカスを安全に移動
targetView.clearFocus()
nextView.requestFocus()
targetView.visibility = View.GONE

// レイアウト XML での必須指定
android:focusable="true"
android:focusableInTouchMode="true"
```
