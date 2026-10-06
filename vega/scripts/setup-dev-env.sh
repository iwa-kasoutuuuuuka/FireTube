#!/usr/bin/env bash
# Vega Developer Tools (VDT) のセットアップ補助スクリプト。
# 対象: Ubuntu 20.04+ / macOS 10.15+ (Windows / WSL は公式非対応)。
# 手順の出典: https://developer.amazon.com/docs/vega/0.23/install-vega-sdk.html
set -euo pipefail

os="$(uname -s)"
case "$os" in
  Linux)
    if ! grep -qi ubuntu /etc/os-release; then
      echo "Ubuntu 以外は公式サポート外です。" >&2; exit 1
    fi
    command -v node >/dev/null || { echo "Node.js 18 以上を先に導入してください。" >&2; exit 1; }
    if [ ! -e /dev/kvm ]; then
      echo "警告: /dev/kvm がありません。Vega Virtual Device は動きません (実機のみ可)。" >&2
    fi
    (dpkg -l | grep -q lz4 || sudo apt install -y lz4)
    sudo add-apt-repository -y ppa:deadsnakes/ppa
    sudo apt update
    (dpkg -l | grep -q libpython3.8-dev || sudo apt install -y libpython3.8-dev)
    # watchman は公式手順の前提。未導入なら https://facebook.github.io/watchman/ を参照
    command -v watchman >/dev/null || echo "警告: watchman が未導入です。" >&2
    ;;
  Darwin)
    command -v brew >/dev/null || { echo "Homebrew を先に導入してください。" >&2; exit 1; }
    [[ "$(arch)" == "arm64" ]] && softwareupdate --install-rosetta --agree-to-license || true
    brew update
    brew install binutils coreutils gawk findutils grep jq lz4 gnu-sed watchman
    ;;
  *)
    echo "未対応のOS: $os" >&2; exit 1 ;;
esac

# VDT 本体 (Vega CLI / SDK / Virtual Device / VS Code 拡張)
curl -fsSL https://sdk-installer.vega.labcollab.net/get_vvm.sh | bash
# shellcheck disable=SC1090
source "$HOME/vega/env"
vega --version
echo "完了。次は vega/SETUP.md の「実機を開発モードにする」へ。"
