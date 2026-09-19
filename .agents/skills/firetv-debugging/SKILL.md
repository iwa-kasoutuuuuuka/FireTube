---
name: firetv-debugging
description: >-
  Comprehensive debugging and diagnostics guide for Amazon Fire TV Stick, Fire OS,
  and Android TV applications. Use when diagnosing crashes, ANRs, remote control / D-Pad
  focus issues, ExoPlayer video playback errors, memory leaks (OOM), and 60fps rendering
  performance on Fire TV devices.
---

# Fire TV Stick & Fire OS アプリ デバッグ実践スキル

Amazon Fire TV Stick (Fire OS 7〜8 / 1.0GB〜2.0GB RAM) 特有の制約とハードウェア特性に基づいた、**体系的なデバッグ・診断プロトコル**です。

---

## ⚡ クイック診断ツール（自動化スクリプト）

本スキルには、即座にデバイス状態を診断・操作できるスクリプトが同梱されています：

1. **ワンストップ自動診断スクリプト**:
   - [`scripts/diagnose_firetv.ps1`](./scripts/diagnose_firetv.ps1)
   - 接続デバイス情報、OSバージョン、現在のフォーカス中Window/Activity、PSSメモリ消費量、60fpsドロップフレーム率、直近クラッシュログを一括出力します。
   ```powershell
   .\.agents\skills\firetv-debugging\scripts\diagnose_firetv.ps1 -PackageName "com.firetube.tv" -SaveScreenshot
   ```

2. **リモコンキー自動送信スクリプト**:
   - [`scripts/send_remote_key.ps1`](./scripts/send_remote_key.ps1)
   - PCから十字キー・決定・戻る・メニュー・メディア操作キーを送信・シーケンス実行します。
   ```powershell
   .\.agents\skills\firetv-debugging\scripts\send_remote_key.ps1 -Sequence "DOWN,DOWN,CENTER"
   ```

---

## 🧭 トラブル別クイック診断フロー（決定木）

```
[トラブル発生]
  │
  ├─► ① 画面の操作が効かない / フォーカス枠が消えた？
  │      └─► 【フォーカス消失 (Black Hole)】
  │            - dumpsys window windows で mCurrentFocus を確認
  │            - references/remote_keycodes.md を参照
  │
  ├─► ② 動画が「取得に失敗しました」または画面真っ暗で音が出ない？
  │      └─► 【メディア・コーデック異常】
  │            - dumpsys media.player でデコーダーとストリームを確認
  │            - YouTube DASH分離配信 (MergingMediaSource) を適用
  │            - references/codec_and_exoplayer.md を参照
  │
  ├─► ③ アプリが前触れなく強制終了した？
  │      └─► 【メモリ枯渇 (OOM) or Glideライフサイクル】
  │            - dumpsys meminfo で Native/Dalvik Heap を確認
  │            - Activity 破棄時の Glide.with() ガードを点検
  │
  └─► ④ カーソル移動やスクロールがカクつく？
         └─► 【描画 Jank (16.6ms超過)】
               - dumpsys gfxinfo で Janky frames を測定
               - System X-Ray または debug.hwui.profile を有効化
               - references/system_xray_and_profiling.md を参照
```

---

## 🛠️ 5段階 デバッグ実践手順

### Step 1: 環境の特定（実機 vs エミュレータ / OS世代）
```bash
adb shell getprop ro.product.model          # 機種名 (AFTMM, AFTKA等)
adb shell getprop ro.build.version.fireos   # Fire OS バージョン (7 or 8)
adb shell getprop ro.build.version.sdk      # API レベル (28 or 30)
```
- **Fire OS 7 (API 28)**: 32-bit、H.264 ハードウェア最優先、RAM 1〜1.5GB。
- **Fire OS 8 (API 30)**: 64-bit、VP9/AV1 ハードウェア対応、Scoped Storage 例外考慮。

### Step 2: リモコンフォーカスの追跡
```bash
adb shell "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"
```
- フォーカスが親コンテナや非表示 View に吸着していないかを特定。
- 詳細は [`references/remote_keycodes.md`](./references/remote_keycodes.md) を確認。

### Step 3: メモリ & OOM (Out Of Memory) 診断
```bash
adb shell dumpsys meminfo <package_name>
```
- `TOTAL PSS` が 200MB を超過している場合、低RAM端末（Fire TV Stick Lite / HD）で LowMemoryKiller により強制終了されるリスク大。
- ビットマップは必ず `RGB_565`（2 bytes/pixel）に固定し、サムネイルは表示サイズ（320x180等）にリサイズしてデコードすること。

### Step 4: 動画再生 & コーデック診断
```bash
adb logcat -s ExoPlayerImpl:* MediaCodecRenderer:* PlaybackActivity:*
```
- YouTube の VOD は映像と音声が分離配信（DASH）されているため、`MergingMediaSource` で映像と音声を合成再生しているか確認。
- 4K Max 以外の HD 端末では H.264 (AVC) を最優先し、VP9 のソフトウェアデコード負荷を避ける。
- 詳細は [`references/codec_and_exoplayer.md`](./references/codec_and_exoplayer.md) を確認。

### Step 5: 描画パフォーマンス (60fps ロック) 診断
```bash
adb shell dumpsys gfxinfo <package_name>
```
- `Janky frames` 率が 5% を超える場合は、レイアウト階層の削減、ViewPool の共有、または非同期画像デコードを点検。
- 詳細は [`references/system_xray_and_profiling.md`](./references/system_xray_and_profiling.md) を確認。
