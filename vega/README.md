# FireTube for Vega OS (試作)

Vega OS 搭載 Fire TV Stick (4K Select など) 向けの FireTube。
Android 版 (`../app`) とは別実装で、**Vega SDK の React Native (TypeScript)** で作る。並行開発する。

| | Android 版 | Vega 版 |
| :--- | :--- | :--- |
| 場所 | `app/` | `vega/` |
| 言語 | Kotlin / Leanback / Media3 | TypeScript / React Native (Vega) / W3C Media API (MSE) |
| ビルド | Gradle | `npm run build:app` → `.vpkg` |
| 配布 | APK (GitHub Releases) | VPKG (Amazon Appstore 前提。開発中は開発者モードで sideload) |
| タグ | `v1.x.x` | `vega-v0.x.x` |
| パッケージ ID | `com.firetube.tv` | `com.firetube.tv.vega` (予定) |

## 状態
- **試作版 v0.1.0** を Releases (タグ `vega-v0.1.0`) で配布。実機での動作は未検証
- 機能: 人気の動画 (ジャンル横断検索) / 検索 / 再生 / SponsorBlock 自動スキップ / リモート設定 (`remote-config.json`)
- 再生経路: 分離 DASH (H.264 + AAC) を MSE で自前再生 (`src/player/dash.ts`) → 失敗時は HLS を URL モードで再生 → 別の InnerTube クライアントで取り直し
- ビルド: Windows では Vega SDK が動かないため、GitHub Actions ([`vega-build.yml`](../.github/workflows/vega-build.yml)) の Ubuntu で `.vpkg` を作る
- 雛形: `vega project generate --template helloWorld-rn72` (SDK 0.24 / RN 0.72。w3cmedia の安定版が RN 0.72 向けのため)

## 構成 (`app/src`)
| パス | 役割 |
| :--- | :--- |
| `api/innertube.ts` | InnerTube (検索・人気・player)。Android 版 `InnerTubeClient.kt` の移植 |
| `api/config.ts` | リモート設定の取得 |
| `api/sponsorblock.ts` | SponsorBlock |
| `player/dash.ts`, `player/sidx.ts` | MSE による分離 DASH 再生 (sidx でセグメント境界を得て有界 Range で取得) |
| `screens/` | ホーム / 検索 / 再生 |

## 実機で最初に確かめること
1. 起動・十字キーのフォーカス移動・戻るキー
2. 再生経路 (画面下の「DASH 1080p / VISIONOS」等の表示) と、DASH が失敗して HLS に落ちていないか
3. シーク (左右キー) 後に再生が続くか

## ドキュメント
- [INSTALL_VEGA.txt](INSTALL_VEGA.txt) — Fire TV (Vega OS) へのインストール手順 (開発者モード + Vega CLI)
- [SETUP.md](SETUP.md) — 開発環境の構築と実機接続
- [docs/porting-spec.md](docs/porting-spec.md) — Android 版からの移植仕様 (取得経路・再生・補助 API・型)
- [`.agents/skills/vega-development`](../.agents/skills/vega-development/SKILL.md) — Vega 開発スキル
