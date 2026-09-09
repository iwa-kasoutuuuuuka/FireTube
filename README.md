<div align="center">

<img src="art/firetube_icon.png" width="180" height="180" alt="FireTube Icon" style="border-radius: 28px; box-shadow: 0 8px 24px rgba(255, 60, 0, 0.4);" />

# FireTube (Fire TV Dedicated YouTube Client)

**Amazon Fire TV Stick HD (Fire OS 7〜8 / 1.5GB RAM) 専用 YouTube ネイティブクライアント**

[![Release](https://img.shields.io/github/v/release/iwa-kasoutuuuuuka/FireTube?color=FF0033&label=Download%20APK&logo=android)](https://github.com/iwa-kasoutuuuuuka/FireTube/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Fire%20OS%207~8%20%28Android%209~11%29-orange)](https://developer.amazon.com/fire-tv)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![GMS Free](https://img.shields.io/badge/Google%20Play%20Services-0%25%20%28Independent%29-green)](#)

[📥 **最新の APK をダウンロード (Direct Download)**](https://github.com/iwa-kasoutuuuuuka/FireTube/releases/latest/download/app-debug.apk)

</div>

---

## 📖 概要

**FireTube** は、低スペックな Fire TV Stick HD（物理RAM 1.0GB〜1.5GB環境）でも一切もっさりせず、**爆速かつサクサク軽快に動作すること**を追求して設計された専用YouTubeクライアントアプリです。

WebView（ブラウザベース）を1%も使用せず、**AndroidX Leanback** と **Media3 ExoPlayer**、**NewPipeExtractor** による「完全ネイティブUI」で実装されています。

---

## 🚀 主な機能と特徴

| 機能 | 内容・技術仕様 |
| :--- | :--- |
| **完全ネイティブ UI (60fps)** | 低スペック端末でカクつきの原因となる Compose を排し、テレビ専用に最適化された **AndroidX Leanback** を採用。物理リモコン操作時にフォーカスが吸い付くように移動し、1.06倍の滑らかな拡大アニメーション＋ネオンゴールド枠線で現在位置を明確にハイライト。 |
| **GMS 0% & データレベル完全広告フリー** | **NewPipeExtractor** を内蔵し、Google Play Servicesへの依存を完全排除。動画本編の生ストリーム（HLS/DASH/MP4）のみを直接抽出するため、広告セグメントが混入せず100%広告フリー。 |
| **SponsorBlock 自動スキップ** | 有志データベースと再生タイムラインを同期し、動画内の案件セクション・OP/ED・チャンネル登録呼びかけ区間をミリ秒単位で完全自動スキップ（HUDバッジ通知付き）。 |
| **スマート・リモコン操作ショートカット** | ・**早送りキー長押し**: 再生速度ワンタッチ切り替え（`1.0x` → `1.25x` → `1.5x` → `2.0x`）<br>・**巻き戻しキー長押し**: 等速（`1.0x`）へ即時リセット<br>・**D-Pad 左右 / 早送り・巻き戻し単押し**: 10秒シーク |
| **極限の低RAM（1.5GB以下）チューニング** | ・**Glide**: サムネイルを `RGB_565`（2 bytes/pixel）に固定し画像メモリを50%削減。メモリキャッシュを最大ヒープの10%（約10〜15MB）に厳格制限。<br>・**ExoPlayer**: `DefaultLoadControl` の先読みバッファを15秒〜30秒（最大32MB）に制限し、OOM（強制終了）を完全防止。<br>・**Lifecycle連動**: ホーム画面移行時に動画描画Surfaceを即時アンバインドし、UIリソースを100%解放。 |
| **バックグラウンド音声再生** | **MediaSessionService** によるフォアグラウンドサービス化。ホーム画面に戻っても音楽やラジオ音声を途切れず再生。 |
| **プライベート・ローカル同期** | お気に入り・視聴履歴・再生位置レジューム・登録チャンネルを端末内の **Room Database** にローカル保存。アカウント不要でプライバシー完全保護。 |

---

## 📥 インストール方法 (APK)

### 1. APK の直接ダウンロード
以下のリンクより最新の APK ファイルをダウンロードしてください。

- **[FireTube 最新版 APK (app-debug.apk)](https://github.com/iwa-kasoutuuuuuka/FireTube/releases/latest/download/app-debug.apk)**
- **[GitHub Releases 一覧](https://github.com/iwa-kasoutuuuuuka/FireTube/releases)**

### 2. Fire TV Stick へのインストール手順
1. Fire TV の「設定」→「マイ Fire TV」→「開発者向けオプション」で「ADBデバッグ」と「未登録アプリのインストール」を **オン** にします。
2. PC と同一 Wi-Fi に接続し、PC のターミナルから ADB でインストールします：
   ```bash
   adb connect <Fire_TV_の_IPアドレス>:5555
   adb install -r app-debug.apk
   ```
   ※ または Fire TV アプリストアの「Downloader」アプリを使って上記 APK の URL から直接インストールすることも可能です。

---

## 🎮 物理リモコン操作仕様

Fire TV 付属の Alexa 音声認識リモコン（物理キー）のみで全操作が完結します。

```
[リモコンキー]          [FireTube内アクション]
----------------------------------------------------------------------
D-Pad (上下左右)    :  動画カード・カテゴリ行のフォーカス移動（拡大・光彩エフェクト）
決定 (Center/OK)    :  動画選択・再生 / 再生中の一時停止トグル
戻る (Back)         :  前の画面に戻る
再生 / 一時停止     :  再生・一時停止の即時切り替え
早送り (FF)         :  短押し: +10秒シーク / 長押し: 再生速度切り替え (1.0x → 1.25x → 1.5x → 2.0x)
巻き戻し (RW)       :  短押し: -10秒シーク / 長押し: 再生速度リセット (1.0x)
メニュー (Menu)     :  画質切り替え・字幕・チャンネル登録メニュー
```

---

## 🖥️ 開発 & エミュレーター環境構築

PC 上の Android Studio エミュレーターで、Fire TV Stick 実機環境（解像度 1080p、RAM 1.5GB制限、D-Padナビゲーション）を完全再現するスクリプトが用意されています。

```powershell
# Fire TV Stick HD 最適化 AVD 自動構築スクリプトの実行
.\setup_firetv_emulator.ps1
```

### ソースコードからのビルド
```bash
# Debug APK のビルド
./gradlew assembleDebug

# 生成場所: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 プロジェクト構成

```
FireTube/
├── app/
│   ├── build.gradle.kts          # minSdk 28, Leanback, Media3, NewPipe, Glide, Room
│   ├── proguard-rules.pro        # 低RAM向け難読化・リフレクション保持ルール
│   └── src/main/
│       ├── AndroidManifest.xml   # leanback=true, touchscreen=false
│       ├── java/com/firetube/tv/
│       │   ├── FireTubeApp.kt    # アプリケーション基底 & メモリ監視登録
│       │   ├── data/
│       │   │   ├── extractor/    # NewPipeExtractor & SponsorBlock 連携
│       │   │   ├── local/        # Room Database (履歴・登録チャンネル)
│       │   │   └── model/        # データモデル (VideoItem, StreamInfo)
│       │   ├── ui/
│       │   │   ├── main/         # BrowseSupportFragment & VideoCardPresenter
│       │   │   ├── player/       # PlaybackActivity & PlaybackService (Media3)
│       │   │   └── search/       # SearchSupportFragment (検索画面)
│       │   └── util/             # FireTubeGlideModule & MemoryManager
│       └── res/                  # バナー、フォーカス枠線、レイアウト、多言語リソース
├── art/                          # アプリアイコン高解像度アセット
├── setup_firetv_emulator.ps1     # AVD 自動構築スクリプト
├── build.gradle.kts              # ルート Gradle 設定
├── settings.gradle.kts           # リポジトリ設定 (JitPack, Google, MavenCentral)
└── gradlew / gradlew.bat         # Gradle Wrapper (8.7)
```

---

## 📜 ライセンス

本プロジェクトは [GPL-3.0 License](LICENSE) の下で公開されています。
YouTubeストリーム抽出部には [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) を使用しています。
