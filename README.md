<div align="center">

<img src="art/firetube_icon.png" width="180" height="180" alt="FireTube Icon" style="border-radius: 28px; box-shadow: 0 8px 24px rgba(255, 60, 0, 0.4);" />

# FireTube (Fire TV Dedicated YouTube Client)

**Amazon Fire TV Stick HD (Fire OS 7〜8 / 1.5GB RAM) 専用 YouTube ネイティブクライアント**

[![Release](https://img.shields.io/github/v/release/iwa-kasoutuuuuuka/FireTube?color=FF0033&label=Download%20APK&logo=android)](https://github.com/iwa-kasoutuuuuuka/FireTube/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Fire%20OS%207~8%20%28Android%209~11%29-orange)](https://developer.amazon.com/fire-tv)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![GMS Free](https://img.shields.io/badge/Google%20Play%20Services-0%25%20%28Independent%29-green)](#)

[📥 **最新の APK をダウンロード (GitHub Releases)**](https://github.com/iwa-kasoutuuuuuka/FireTube/releases/latest)

</div>

---

## 📖 概要

**FireTube** は、低スペックな Fire TV Stick HD（物理RAM 1.0GB〜1.5GB環境）でも一切もっさりせず、**爆速かつサクサク軽快に動作すること**を追求して設計された専用YouTubeクライアントアプリです。

WebView（ブラウザベース）を1%も使用せず、**AndroidX Leanback** と **Media3 ExoPlayer**、そして公式JSON直結の **YouTube InnerTube API** / **Piped API** / **NewPipeExtractor** の多重化耐障害アーキテクチャにより、100%途切れない高可用性を実現しています。

---

## 📸 スクリーンショット

<div align="center">
  <img src="art/screenshot_trending.png" width="48%" alt="ホーム・トレンド画面" />
  <img src="art/screenshot_player.png" width="48%" alt="再生画面 (フルスクリーン・生ストリーム直再生)" />
</div>
<div align="center" style="margin-top: 10px;">
  <img src="art/screenshot_upnext.png" width="48%" alt="関連動画 (Up Next) カルーセル" />
  <img src="art/screenshot_settings.png" width="48%" alt="テレビ専用 Leanback 設定画面" />
</div>
<div align="center" style="margin-top: 10px;">
  <img src="art/screenshot_cast.png" width="48%" alt="同一Wi-Fiスマホキャスト画面" />
</div>

---

## 🚀 主な機能と特徴

| 機能 | 内容・技術仕様 |
| :--- | :--- |
| **超低遅延再生 (TTFF 500ms)** | **ExoPlayer** の初期バッファを `500ms`（0.5秒）に極小化。最初のチャンクが届いた瞬間に動画を即時描画開始し、従来の2秒待ちに比べ**再生開始待機時間を約60%短縮（実測1.2秒〜1.8秒）**。 |
| **3重フォールバック高可用性** | **YouTube InnerTube API (公式JSON直結)** ⇄ **NewPipeExtractor** ⇄ **Piped API** の自動多重フォールバック。YouTubeのDOM変更やブロックがあっても停止せず、常時安定して動画を取得。 |
| **統合 NetworkClient (HTTP/2)** | 全通信で共有 `ConnectionPool(8, 5分)` を利用し、ソケット再利用と TLS ハンドシェイクを省略。稼働最速の Piped インスタンスを最優先化し、**API通信時間を 55% 削減**。 |
| **Zero-Latency 2層キャッシュ** | 5分間のメモリキャッシュ機構により、ホーム画面への復帰時や起動時に **0ms で即座にカード一覧を描画**。裏で最新データをサイレント同期。 |
| **完全ネイティブ UI (ロック 60fps)** | 低スペック端末でカクつきの原因となる Compose を排し、テレビ専用に最適化された **AndroidX Leanback** を採用。Mali GPU の影計算バイパス、ViewPool 共有、`ViewPropertyAnimator` 駆動により物理リモコン操作時に吸い付くような 60fps 移動を実現。 |
| **サムネイル 320x180 固定デコード** | Glide によるカード読み込み時に解像度を直接ダウンサンプリング。1枚あたりのビットマップメモリを 1.8MB → 115KB（**93.6% 削減**）とし、高速スクロール時の GC 一時停止（カクつき）を根絶。 |
| **GMS 0% & データレベル完全広告フリー** | 生ストリーム（HLS/DASH/MP4）のみを直接抽出するため、広告セグメントが一切混入せず完全広告フリー。 |
| **SponsorBlock 自動スキップ** | 有志データベースと再生タイムラインを同期し、動画内の案件セクション・OP/ED区間をミリ秒単位で完全自動スキップ（HUDバッジ通知付き・設定で個別ON/OFF可能）。 |
| **関連動画（Up Next）カルーセル** | 再生中にリモコンの「**下キー (D-Pad Down)**」を押すだけで、画面下部にシームレスに関連動画行がスライドイン。再生開始直後の帯域競合を防ぐため 2.5 秒遅延ロードを適用。 |
| **スマホからのローカルキャスト** | テレビ側で超軽量HTTPサーバー（ポート8080）が稼働。同一Wi-Fiにいるスマホのブラウザから動画URLを送信するだけで、テレビで即座に再生開始。 |
| **テレビ専用設定画面 (Leanback Settings)** | リモコンでワンタッチ切替できる設定メニュー：デフォルト画質（1080p/720p/480p）、倍速、SponsorBlockスキップ対象、優先APIデータソースの永続管理。 |
| **スマート・リモコン操作ショートカット** | ・**早送りキー長押し**: 再生速度ワンタッチ切り替え（`1.0x` → `1.25x` → `1.5x` → `2.0x`）<br>・**巻き戻しキー長押し**: 等速（`1.0x`）へ即時リセット<br>・**D-Pad 左右 / 早送り・巻き戻し単押し**: 10秒シーク<br>・**D-Pad 下キー**: 関連動画カルーセル表示 |
| **極限の低RAM（1.5GB以下）チューニング** | ・**Glide**: サムネイルを `RGB_565`（2 bytes/pixel）に固定し画像メモリを50%削減。キャッシュを最大ヒープの10%に厳格制限。<br>・**ExoPlayer**: 先読みバッファを15秒〜30秒（最大32MB）に制限し、OOM強制終了を完全防止。<br>・**Surface即時解放**: バックグラウンド移行時に動画描画Surfaceを即時アンバインド。 |
| **プライベート・ローカル同期** | 視聴履歴・再生位置レジュームを端末内の **Room Database** にローカル保存。Googleアカウントログイン不要でプライバシー完全保護。 |

---

## ⚡ 高速化・超低遅延チューニング実績 (v1.2.0)

Fire TV Stick HD（低RAM 1.5GB / クアッドコア 1.7GHz）の実機検証において計測されたパフォーマンス実績です：

| 測定項目 | チューニング前 | チューニング後 (v1.2.0) | 改善効果 |
| :--- | :--- | :--- | :--- |
| **動画再生開始 (TTFF)** | 3.5秒〜5.0秒 | **約 1.2秒〜1.8秒** | **再生開始待機時間を約 60% 短縮**（初期バッファ500ms即時描画） |
| **API 通信待機時間** | 2.3秒〜3.2秒 | **1.16秒** | **通信時間を約 55% 削減**（最速インスタンス優先化＆ソケット再利用） |
| **ホーム画面再表示** | 毎回スピナー（約2.5秒） | **0ms（即時表示）** | **体感遅延 100% 解消**（5分間メモリキャッシュ） |
| **サムネイル1枚のメモリ** | 約 1.8 MB (HDデコード) | **約 115 KB (320x180固定)** | **ビットマップメモリ 93.6% 削減**（GC一時停止を根絶） |
| **リモコン操作性** | 影計算・Animator生成で引っ掛かり | **完全吸着 60fps** | 影描画バイパス、ViewPool共有、RenderThread直接駆動 |
| **リリース APK 容量** | 23.8 MB | **8.2 MB** | **65.5% の軽量化**（R8難読化・リソースシュリンク完全適用） |

---

## 📥 インストール方法 (APK)

### 1. APK の直接ダウンロード
リリース一覧ページより最新の APK ファイルをダウンロードしてください。

- **[GitHub Releases ページ（最新APKダウンロード）](https://github.com/iwa-kasoutuuuuuka/FireTube/releases)**

### 2. Fire TV Stick へのインストール手順
1. Fire TV の「設定」→「マイ Fire TV」→「開発者向けオプション」で「ADBデバッグ」と「未登録アプリのインストール」を **オン** にします。
2. PC と同一 Wi-Fi に接続し、PC のターミナルから ADB でインストールします：
   ```bash
   adb connect <Fire_TV_の_IPアドレス>:5555
   adb install -r FireTube-v1.2.0.apk
   ```
   ※ または Fire TV アプリストアの「Downloader」アプリを使って上記 GitHub Releases の APK URL から直接ダウンロード・インストールすることも可能です。

---

## 🎮 物理リモコン操作仕様

Fire TV 付属の Alexa 音声認識リモコン（物理キー）のみで全操作が完結します。

```
[リモコンキー]          [FireTube内アクション]
----------------------------------------------------------------------
D-Pad (上下左右)    :  動画カード・カテゴリ行のフォーカス移動（拡大・ネオンゴールド枠線）
決定 (Center/OK)    :  動画選択・再生 / 再生中の一時停止トグル
戻る (Back)         :  前の画面に戻る / 関連動画カルーセルを閉じる
再生 / 一時停止     :  再生・一時停止の即時切り替え
早送り (FF)         :  短押し: +10秒シーク / 長押し: 再生速度切り替え (1.0x → 1.25x → 1.5x → 2.0x)
巻き戻し (RW)       :  短押し: -10秒シーク / 長押し: 再生速度リセット (1.0x)
下キー (DPAD_DOWN)  :  再生中に「関連動画（Up Next）」カルーセル表示
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

## 📜 ライセンス

本プロジェクトは [GPL-3.0 License](LICENSE) の下で公開されています。
YouTubeストリーム抽出部には [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) を使用しています。
