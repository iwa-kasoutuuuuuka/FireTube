---
name: vega-development
description: >-
  Development guide for the Vega OS (React Native, TypeScript) edition of FireTube under vega/.
  Use when setting up the Vega SDK, generating/building/installing a .vpkg, enabling developer mode
  on a Vega Fire TV Stick, debugging on device or Virtual Device, implementing D-pad focus,
  video playback with the W3C Media API (MSE), or porting features from the Android edition (app/).
---

# FireTube for Vega OS 開発スキル

Vega OS は **Android ではない**。Android 版 (`app/`, Kotlin/Leanback/Media3) のコードは流用できず、
`vega/` に React Native (TypeScript) で再実装する。共有できるのは仕様・API の知識のみ。

## 絶対に守ること
- 既存の Android 版 (`app/`, `build.gradle.kts`, `.github/workflows/release.yml`) を壊さない。Vega 作業は `vega/` 内で完結させる。
- タグは `vega-v*`。`v*` は Android リリース用 (release.yml が起動する)。
- **このリポジトリの開発 PC は Windows。Vega SDK は Ubuntu / macOS 専用 (Windows・WSL 非対応)**。`vega` コマンドは Windows では実行できない。環境を確認してから作業すること (`vega --version`)。
- Windows からのビルドは GitHub Actions (`.github/workflows/vega-build.yml`, ブランチ `vega-build` / タグ `vega-v*`) で行う。雛形は `helloWorld-rn72` (SDK 0.24.12112 / CLI 1.4.4 で確認)。CI のログは未ログインでは読めないため、失敗内容は `vega/scripts/ci-run.sh` がアノテーション (公開 API `check-runs/{job}/annotations`) に出す。
- 下の「検証済み / 未検証」を区別する。未検証の API 名や CLI オプションを断定して書かない。公式ドキュメントはバージョン別 URL (`/docs/vega/0.2x/`)。`vega --version` の SDK に合わせる。

## 検証済みの CLI (公式ドキュメント由来)
| 目的 | コマンド |
| :--- | :--- |
| SDK 確認/更新 | `vega --version` / `vega update` |
| テンプレ一覧 | `vega project list-templates` |
| 雛形生成 | `vega project generate --template helloWorld --name <n> --packageId <id> --outputDir <dir>` |
| ビルド | `npm install` → `npm run build:app` (出力 `build/<arch>-release/*.vpkg`) |
| 開発者モード | `vega devmode login` → `vega devmode enable-device --code <6桁>` |
| 実機一覧/シェル | `vega device list` / `vega device shell` (複数台は `-d <serial>`) |
| 実機へ導入/起動 | `vega device install-app --packagePath <vpkg>` / `vega device launch-app --appName <component-id>` |
| Virtual Device | `vega virtual-device start` / `vega run-app <vpkg> <app-id> -d VirtualDevice` / `vega virtual-device stop` |

アーキ: Fire TV Stick 実機 = `armv7-release`、VVD = `x86_64-release` (x86 PC) / `aarch64-release` (M シリーズ Mac)。
手順の詳細: [`vega/SETUP.md`](../../../vega/SETUP.md) / [references/setup_and_device.md](references/setup_and_device.md)

## 基本ワークフロー
1. `vega device list` で実機が見えるか確認 (見えなければ開発者モード・同一ネットワークを確認。有効化のたびに再起動が必要)
2. 変更 → `npm run build:app` → `install-app` → `launch-app`
3. 症状が出たら `vega device shell` でログ・プロセスを確認
4. 動作確認はまず実機。VVD は KVM 必須で、実機の代わりにはならない (公式も実機での検証を推奨)

## 再実装の指針 (Android 版 → Vega 版)
| Android 版 | Vega 版での扱い |
| :--- | :--- |
| NewPipeExtractor (Java) | 使用不可。InnerTube / Piped を TS + `fetch` で実装 |
| Media3 ExoPlayer + MergingMediaSource | W3C Media API (HTMLMediaElement + MSE)。映像/音声を別 SourceBuffer で追加 |
| Leanback (Row/Card) | React Native の FlatList 等 + フォーカス管理 (下記) |
| Room / SharedPreferences | 未検証。まず AsyncStorage 系で足りるか実機で確認 |
| SponsorBlock / RYD | HTTP のみなので TS で移植しやすい。最初に移植する候補 |

詳細: [references/media_and_porting.md](references/media_and_porting.md)

## TV 向け実装の注意 (一般的な React Native TV の知識。Vega での挙動は実機で確認)
- リモコンは十字キー+決定+戻る+メディアキーのみ。タッチ前提の UI を作らない。
- すべての操作可能要素にフォーカス移動先を用意し、フォーカス枠を明示する。戻るキーで必ず 1 階層戻れること。
- 一覧は仮想化 (FlatList)、サムネイルは縮小して読み込む。Fire TV Stick は低メモリ。

## 試作のゴール (順に)
1. helloWorld が実機で起動 → 2. リモコン操作でフォーカス移動 → 3. 固定の DASH/MP4 を MSE で再生 → 4. InnerTube で一覧取得 → 5. 再生まで一気通貫
