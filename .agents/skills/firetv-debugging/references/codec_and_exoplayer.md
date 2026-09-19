# Fire TV コーデック & ExoPlayer 再生デバッグ実践ガイド

## 1. Fire TV 世代別 SoC とハードウェアデコーダー対応表

| 機種 | SoC | OS | 4K対応 | 推奨ハードウェアコーデック | 注意事項 |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **Fire TV Stick HD** (第3世代等) | MediaTek MT8695/MT8696D | Fire OS 7 (API 28) | ❌ | **H.264 (AVC) 最優先**<br>VP9 は 1080p 以下のみ | VP9 は CPU 負荷・発熱が高くコマ落ちの原因になるため H.264 を強制推奨 |
| **Fire TV Stick 4K** (第2世代) | MediaTek MT8696 | Fire OS 8 (API 30) | ⭕ | **H.264 / VP9 / AV1** | 4K は YouTube 上で VP9/AV1 のみ配信されるため AVC 固定を解除すること |
| **Fire TV Stick 4K Max** (第1/2世代) | MT8696 / MT8696T | Fire OS 7/8 (API 28/30) | ⭕ | **H.264 / VP9 / AV1 (4K 60fps)** | ハードウェア AV1 デコーダー搭載。Wi-Fi 6 対応で帯域余裕あり |
| **Fire TV Cube** (第3世代) | Amlogic POP1-G | Fire OS 7 (API 28) | ⭕ | **H.264 / VP9 / AV1** | 最強スペック。超解像アップスケーリング対応 |

---

## 2. ExoPlayer 再生障害の診断コマンド

### メディアパイプライン・デコーダーのリアルタイム状態確認
```bash
# ExoPlayer および MediaCodec の内部ステータスをダンプ
adb shell dumpsys media.player

# システムに登録されている全コーデック一覧と機能を確認
adb shell dumpsys media.extractor
```

### 再生エラー時の Logcat フィルタリング
```bash
adb logcat -s ExoPlayerImpl:* MediaCodecRenderer:* PlaybackActivity:* AudioTrack:*
```

---

## 3. YouTube ストリーム固有の障害パターンと対策

### パターンA: 音声が出ない / 403 Forbidden で動画がロードできない
- **根本原因**: YouTube の VOD は映像と音声が分離配信（DASH）されており、単一の Muxed URL では再生できない。
- **解決策**:
  ```kotlin
  val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(videoUrl))
  val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(audioUrl))
  val mergedSource = MergingMediaSource(videoSource, audioSource)
  player.setMediaSource(mergedSource)
  ```

### パターンB: 4K 動画で画面が真っ暗・再生不能
- **根本原因**: `trackSelector` で H.264 のみを許可していると、YouTube 側の 4K ストリーム（VP9/AV1）がすべて弾かれてしまう。
- **解決策**: 4K Max 等の 4K 再生時は `trackSelector` の MIME タイプ制限を解除し、VP9 / AV1 のハードウェアデコードを許可する。

### パターンC: 再生中に OOM (Out Of Memory) クラッシュ
- **根本原因**: ExoPlayer のデフォルトバッファ設定（50MB以上先読み）により、1.0GB〜1.5GB RAM の Fire TV Stick HD でメモリ枯渇。
- **解決策**:
  ```kotlin
  DefaultLoadControl.Builder()
      .setBufferDurationsMs(
          15_000,  // minBufferMs (15秒)
          30_000,  // maxBufferMs (30秒)
          1_500,   // bufferForPlaybackMs
          3_000    // bufferForPlaybackAfterRebufferMs
      )
      .setTargetBufferBytes(24 * 1024 * 1024) // 最大 24MB に厳格制限
      .build()
  ```
