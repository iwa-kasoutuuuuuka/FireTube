# Vega 開発環境の構築

## 0. 前提: Windows / WSL では動かない
公式要件は **Ubuntu 20.04+ または macOS 10.15+** (空き 20GB)。Windows と WSL は非対応・未検証。
この PC (Windows 11) では直接構築できないため、次のいずれかを用意する。

| 選択肢 | 備考 |
| :--- | :--- |
| Mac (Apple Silicon 可) | 最も簡単。Homebrew + Rosetta が必要 |
| Ubuntu 実機 / デュアルブート | 実機接続 (USB/ネットワーク) も素直。Virtual Device には KVM が必要 |
| Windows 上の Ubuntu VM | 非公式。USB パススルー・ブリッジ接続・KVM のネスト仮想化が壁になる。実機を同一LANに置きネットワーク接続なら動く可能性あり (要検証) |
| クラウド Ubuntu | 実機に届かないため、ビルド専用になる |

## 0.5 Windows 上に Ubuntu VM を作る (この PC の方針)
Hyper-V (Windows 11 Pro) + Ubuntu 22.04 Server。Python 3.8 (deadsnakes) を使うため 22.04 を選ぶ。
ネットワークは Default Switch (NAT)。Wi-Fi 接続の PC ではブリッジ接続が不安定なため。
VM から実機への接続は PC 側から出る通信なので NAT でも通る想定 (未検証。駄目なら外部スイッチを作成)。

管理者 PowerShell で実行 (Hyper-V 未有効なら有効化して再起動 → もう一度実行):
```powershell
powershell -ExecutionPolicy Bypass -File E:\ZFireTube\vega\scripts\windows\create-ubuntu-vm.ps1
```
既定: 8 vCPU / 16GB RAM / 120GB (`E:\VM`)。ISO (約2.1GB) を取得し、入れ子の仮想化を有効にして起動する。
Ubuntu インストール時に OpenSSH server を選ぶ。完了後は `ssh <user>@<VMのIP>` で入り、リポジトリを VM に clone して次節へ。
Virtual Device (KVM) は AMD CPU での入れ子仮想化が必要で、動くかは未検証。**実機のみで開発するなら不要**。

## 1. SDK 導入 (Ubuntu / macOS)
```bash
bash vega/scripts/setup-dev-env.sh
```
中身は公式手順 (依存パッケージ → `get_vvm.sh` → `source ~/vega/env` → `vega --version`)。
Node.js 18 以上、Python 3.8、lz4、Watchman が前提。

## 2. 実機を開発者モードにする
Fire TV 側: 設定 > My Fire TV > バージョン情報 > 端末名を選び、リモコンの決定ボタンを **7回** 押す
→ My Fire TV に「開発者向けオプション」が出る → 「開発者モード」→ 続行 → **6桁コード** を控える。

開発機側 (Amazon 開発者アカウントが必要):
```bash
vega devmode login
vega devmode enable-device --code <6桁コード>   # 端末が再起動する
vega device list                                  # 認識確認
```
開発者モードは **有効化のたびに再起動** が必要。実機は開発機と同一ネットワークに置く。

## 3. 雛形の生成とビルド
```bash
vega project list-templates
vega project generate --template helloWorld --name firetube --packageId com.firetube.tv.vega --outputDir vega/app
cd vega/app && npm install && npm run build:app
```
出力: `build/armv7-release/*.vpkg` (Fire TV Stick 実機)、`x86_64-release` / `aarch64-release` (Virtual Device)。

## 4. 実機で動かす
```bash
vega device install-app --packagePath build/armv7-release/<name>_armv7.vpkg
vega device launch-app --appName <component-id>
vega device shell
```
Virtual Device: `vega virtual-device start` → `vega run-app <vpkg> <app-id> -d VirtualDevice` → `vega virtual-device stop`。

## 5. 最初の検証項目 (試作のゴール)
1. helloWorld が実機で起動する
2. リモコン (十字・決定・戻る) のフォーカス移動
3. W3C Media API (MSE) で DASH の映像+音声を再生できるか (InnerTube の取得は `fetch` で別途)
4. 取得経路: NewPipeExtractor は使えないため、InnerTube / Piped を TS で実装

出典: [VDT 導入](https://developer.amazon.com/docs/vega/0.23/install-vega-sdk.html) /
[開発者モード](https://developer.amazon.com/docs/vega/0.24/developer-mode.html) /
[アプリ実行](https://developer.amazon.com/docs/vega/0.22/run-apps.html) /
[アプリのビルド](https://developer.amazon.com/docs/vega/0.22/build-an-app)
(バージョン別 URL のため、`vega --version` の SDK に合わせて版を読み替えること)
