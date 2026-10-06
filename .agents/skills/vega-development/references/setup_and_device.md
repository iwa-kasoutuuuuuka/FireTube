# セットアップ・実機接続リファレンス

## 環境要件 (公式)
- Ubuntu 20.04+ / macOS 10.15+、空き 20GB。**Windows / WSL は非対応**。
- 依存: Node.js (docs により 16+ または 18+ と記載差あり。18+ を使う)、Python 3.8、lz4、Watchman。Ubuntu は KVM (Virtual Device 用)。
- インストール: `curl -fsSL https://sdk-installer.vega.labcollab.net/get_vvm.sh | bash && source ~/vega/env`
- 補助スクリプト: `vega/scripts/setup-dev-env.sh`

## 実機 (Fire TV Stick 4K Select 等) を開発者モードに
1. 設定 > My Fire TV > バージョン情報 > 端末名 > 決定ボタン 7 回
2. 開発者向けオプション > 開発者モード > 続行 > 6桁コードを控える
3. `vega devmode login` (ブラウザで Amazon 開発者アカウント認証) → `vega devmode enable-device --code <コード>`
4. 端末が再起動 → `vega device list`

## トラブルシュート
| 症状 | 確認 |
| :--- | :--- |
| `vega: command not found` | `source ~/vega/env` をシェル起動時に実行しているか |
| `vega device list` が空 | 同一ネットワークか / 開発者モードが「有効」か / 再起動後か / USB ケーブル |
| VVD が起動しない | `/dev/kvm` の有無、VM 上ならネスト仮想化 |
| ビルド失敗 | Node のバージョン、Watchman、`npm install` のやり直し |

開発機が Windows しかない場合: Mac / Ubuntu 実機 / (非公式) Ubuntu VM。VM は USB パススルーとブリッジ接続が必要で、動作は未検証。
