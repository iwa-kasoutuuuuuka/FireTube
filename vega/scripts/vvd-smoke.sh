#!/usr/bin/env bash
# CI 用: Vega Virtual Device を起動して FireTube を入れ、起動・ログ・画面を記録する。
# 各手順は失敗しても続ける (どこで止まったかを記録に残すため)。結果は $OUT に書く。
set -uo pipefail
source "$HOME/vega/env"
OUT="${OUT:-/tmp/vvd-out}"
mkdir -p "$OUT"
APP_ID="com.firetube.tv.vega.main"
VPKG="$(ls vega/app/build/x86_64-release/*.vpkg 2>/dev/null | head -n1)"

run() { # run <記録名> <コマンド...>
  local name="$1"; shift
  echo "\$ $*" > "$OUT/$name.txt"
  timeout 300 bash -c '"$@"' _ "$@" >> "$OUT/$name.txt" 2>&1
  echo "[exit $?]" >> "$OUT/$name.txt"
}
dev() { vega device "$@" -d VirtualDevice; }
sh_dev() { vega device shell -d VirtualDevice "$@"; }
export -f dev sh_dev

run 00-version vega --version
run 01-help-vega vega --help
run 02-help-device vega device --help
run 03-help-vvd vega virtual-device --help
run 04-help-shell vega device shell --help
echo "vpkg: $VPKG" > "$OUT/05-vpkg.txt"
ls -la vega/app/build/*/ >> "$OUT/05-vpkg.txt" 2>&1

# 仮想端末の起動 (終わらない場合に備えて裏で動かす)
( vega virtual-device start > "$OUT/10-vvd-start.txt" 2>&1 ) &
for i in $(seq 1 60); do
  if vega device list 2>/dev/null | grep -qi virtual; then break; fi
  sleep 10
done
run 11-device-list vega device list
run 12-vvd-status vega virtual-device status

if [ -n "$VPKG" ]; then
  run 20-install dev install-app --packagePath "$VPKG"
  run 21-launch dev launch-app --appName "$APP_ID"
fi
sleep 45

run 30-ps sh_dev "ps -ef"
run 31-journal sh_dev "journalctl --no-pager -n 3000"
run 32-journal-app sh_dev "journalctl --no-pager -n 3000 | grep -iE 'firetube|ReactNative|KeplerScript|JS|error|exception' | tail -n 600"
run 33-loggingctl sh_dev "loggingctl log --help"
run 34-ls-logs sh_dev "ls -la /var/log /tmp"

# 画面の取得 (コマンド名が版によって違う可能性があるため候補を試す)
run 40-screenshot dev screenshot --output "$OUT/screen.png"
run 41-capture dev capture-screenshot --output "$OUT/screen2.png"

# リモコン操作: 下 → 決定 (最初の動画を開く) → 待つ → 画面とログ
run 50-help-input sh_dev "inputd-cli --help"
run 51-key-down sh_dev "inputd-cli send_key KEY_DOWN"
run 52-key-select sh_dev "inputd-cli send_key KEY_ENTER"
sleep 40
run 53-screenshot-play dev screenshot --output "$OUT/screen-play.png"
run 54-journal-after sh_dev "journalctl --no-pager -n 1500 | grep -iE 'firetube|ReactNative|KeplerScript|JS|media|error|exception' | tail -n 600"

run 90-vvd-stop vega virtual-device stop
ls -la "$OUT" > "$OUT/99-files.txt"
exit 0
