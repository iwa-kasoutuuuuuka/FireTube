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
- 開発環境: **未構築** (Windows PC のみのため。`SETUP.md` 参照)
- プロジェクト雛形: **未生成** (`vega project generate` は SDK 導入後に実行)

## ドキュメント
- [INSTALL_VEGA.txt](INSTALL_VEGA.txt) — Fire TV (Vega OS) へのインストール手順 (開発者モード + Vega CLI)
- [SETUP.md](SETUP.md) — 開発環境の構築と実機接続
- [docs/porting-spec.md](docs/porting-spec.md) — Android 版からの移植仕様 (取得経路・再生・補助 API・型)
- [`.agents/skills/vega-development`](../.agents/skills/vega-development/SKILL.md) — Vega 開発スキル
