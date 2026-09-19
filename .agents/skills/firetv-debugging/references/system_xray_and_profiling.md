# Fire TV System X-Ray & パフォーマンスプロファイリング実践ガイド

## 1. Amazon Developer Tools Menu & System X-Ray とは

Amazon が公式に提供している開発者向け診断オーバーレイです。アプリの実行画面の上にリアルタイムで常駐し、以下の重要指標を画面上に直接表示します：
- **CPU 使用率**: コアごとの負荷状況
- **メモリ使用量 (PSS)**: App、Free、Total のリアルタイム変動
- **GPU 使用率 & 描画 FPS**: 60fps 追従状況とコマ落ち
- **メディアコーデック情報**: 現在再生中の動画のデコーダー名（OMX.MTK... 等）、解像度、フレームレート、ドロップフレーム数

---

## 2. 開発者メニュー & System X-Ray の起動手順

### コマンドライン (ADB) から直接起動
```bash
# Developer Tools Menu (設定ダイアログ) を画面上に表示
adb shell am start -n com.amazon.ssm/com.amazon.ssm.ControlPanel

# またはインテントから直接 System X-Ray オーバーレイを ON
adb shell am broadcast -a com.amazon.ssm.ENABLE_SYS_XRAY
```

### リモコンの隠しコマンドで起動（実機操作）
1. リモコンの「**決定ボタン (Center)**」と「**下ボタン (Down)**」を同時に **5秒間長押し** する。
2. 両方を離し、すぐに「**メニューボタン (三本線)**」を押す。
3. 画面上に「Developer Tools Menu」がポップアップする。
4. 「System X-Ray」のトグルを ON にする。

---

## 3. レンダリング・パフォーマンス診断 (60fps Jank 検出)

Fire TV Stick HD (MediaTek Quad-Core) では、1フレームあたりの描画時間（16.6ms）を超過するとカクつきが発生します。

### dumpsys gfxinfo によるドロップフレーム解析
```bash
# 直近のフレーム描画統計を出力
adb shell dumpsys gfxinfo com.firetube.tv

# フレームごとの詳細ヒストグラムを出力
adb shell dumpsys gfxinfo com.firetube.tv framestats
```

**注目すべき重要指標:**
- `Janky frames`: 16.6ms を超過したフレームの割合（**目標: 5% 未満**）
- `Number Missed Vsync`: VSYNC に間に合わなかった回数

### 画面上のビジュアルバー表示 (Profile GPU Rendering)
```bash
# 画面下部に各フレームの描画時間をリアルタイム棒グラフで表示
adb shell setprop debug.hwui.profile visual_bars

# 非表示に戻す
adb shell setprop debug.hwui.profile false
```
緑のライン（16ms）を棒グラフが突き抜けている場合、レイアウト階層のネストが深すぎるか、UIスレッドで重い処理（ビットマップデコード等）が走っています。

---

## 4. StrictMode による UI スレッドブロック検知

Fire TV では、メインスレッドでのわずか 100ms のディスク I/O や JSON パースが致命的な UI カクつきを引き起こします。

```kotlin
// Application または MainActivity の onCreate に配置
if (BuildConfig.DEBUG) {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .penaltyFlashScreen() // 画面が一瞬赤く点滅して警告
            .build()
    )
}
```
