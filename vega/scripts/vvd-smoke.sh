#!/usr/bin/env bash
# CI 用: Vega Virtual Device を起動して FireTube を入れ、起動・ログを記録する。
# 各手順は失敗しても続ける (どこで止まったかを記録に残すため)。結果は $OUT に書く。
set -uo pipefail
source "$HOME/vega/env"
OUT="${OUT:-/tmp/vvd-out}"
mkdir -p "$OUT"
APP_ID="com.firetube.tv.vega.main"
VPKG="$(ls vega/app/build/x86_64-release/*.vpkg 2>/dev/null | head -n1)"
VVD_DIR="$(ls -d "$HOME"/vega/sdk/vega-sdk/main/*/vvd 2>/dev/null | head -n1)"
SDK_LOGS="$(ls -d "$HOME"/vega/sdk/vega-sdk/main/*/logs 2>/dev/null | head -n1)"

run() { # run <記録名> <コマンド...>
  local name="$1"; shift
  echo "\$ $*" > "$OUT/$name.txt"
  timeout 300 bash -c '"$@"' _ "$@" >> "$OUT/$name.txt" 2>&1
  echo "[exit $?]" >> "$OUT/$name.txt"
}
dev() { vega device "$@" -d VirtualDevice; }
export -f dev

booted() { vega device list 2>/dev/null | grep -qiv 'no devices'; }

# 起動を待つ。仮想端末の CLI は 60 秒で待つのをやめるが、qemu は動き続けることがあるので自分でも待つ
wait_boot() {
  for i in $(seq 1 36); do
    booted && return 0
    sleep 10
  done
  return 1
}

collect_vvd_logs() { # collect_vvd_logs <接尾辞>
  local s="$1"
  {
    echo "== vvd dir: $VVD_DIR"
    find "$VVD_DIR" -maxdepth 4 -type f \( -name '*.log' -o -name '*.txt' -o -name '*.json' \) -newer "$OUT/00-env.txt" 2>/dev/null |
      while read -r f; do echo "===== $f"; tail -n 120 "$f"; done
    echo "== sdk logs: $SDK_LOGS"
    ls -lt "$SDK_LOGS" 2>/dev/null | head -n 20
    for f in $(ls -t "$SDK_LOGS"/* 2>/dev/null | head -n 3); do echo "===== $f"; tail -n 120 "$f"; done
    echo "== processes"
    ps -ef | grep -iE 'qemu|vvd|kepler|vega' | grep -v grep
  } > "$OUT/vvd-logs-$s.txt" 2>&1
}

{
  echo "vpkg: $VPKG"; echo "vvd: $VVD_DIR"
  ls -l /dev/kvm; grep -cE 'vmx|svm' /proc/cpuinfo; nproc; free -g
} > "$OUT/00-env.txt" 2>&1

run 01-help-start vega virtual-device start --help
run 02-help-run-cmd vega device run-cmd --help
run 03-help-copy-logs vega device copy-logs --help
run 04-help-log-stream vega device start-log-stream --help
run 05-help-launch vega device launch-app --help

# 1 回目: そのまま起動
( vega virtual-device start > "$OUT/10-start-plain.txt" 2>&1 ) &
if wait_boot; then
  echo booted > "$OUT/11-boot.txt"
else
  echo "not booted (plain)" > "$OUT/11-boot.txt"
  collect_vvd_logs plain
  vega virtual-device stop > /dev/null 2>&1
  # 2 回目: 画面 (X) が要る可能性があるので仮想ディスプレイで起動
  sudo apt-get install -y xvfb > /dev/null 2>&1
  ( xvfb-run -a vega virtual-device start > "$OUT/12-start-xvfb.txt" 2>&1 ) &
  if wait_boot; then echo "booted (xvfb)" >> "$OUT/11-boot.txt"; else echo "not booted (xvfb)" >> "$OUT/11-boot.txt"; collect_vvd_logs xvfb; fi
fi
run 13-device-list vega device list
run 14-vvd-status vega virtual-device status

if booted && [ -n "$VPKG" ]; then
  run 20-info dev info
  ( vega device start-log-stream -d VirtualDevice > "$OUT/25-log-stream.txt" 2>&1 ) &
  run 21-install dev install-app --packagePath "$VPKG"
  run 22-launch dev launch-app --appName "$APP_ID"
  sleep 60
  run 23-running dev running-apps
  run 24-is-running dev is-app-running --appName "$APP_ID"
  run 30-journal dev run-cmd "journalctl --no-pager -n 4000"
  run 31-copy-logs dev copy-logs --outputDir "$OUT/device-logs"
  run 32-doctor dev doctor --outputDir "$OUT/doctor"
  vega device stop-log-stream -d VirtualDevice > /dev/null 2>&1
fi

collect_vvd_logs final
run 90-vvd-stop vega virtual-device stop
find "$OUT" -type f -size +5M -delete
ls -laR "$OUT" > "$OUT/99-files.txt"
exit 0
