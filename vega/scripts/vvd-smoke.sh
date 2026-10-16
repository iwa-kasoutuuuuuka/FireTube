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

booted() { vega device list 2>&1 | grep -q 'VirtualDevice'; }
# 起動直後は一覧の取得が一時的に失敗することがあるため、何度か確かめる
connected() {
  for i in $(seq 1 12); do
    booted && return 0
    sleep 5
  done
  return 1
}

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

# 仮想端末の起動。CI は入れ子の仮想化で遅く、既定の 60 秒では起動前に止められてしまう
# (virtual_device.log: "failed to access shell")。画面なし・ソフトウェア描画・長い待ち時間で起動する
( vega virtual-device start --no-gui --no-gl-accel --timeout 900 > "$OUT/10-start.txt" 2>&1 ) &
START_PID=$!
for i in $(seq 1 100); do
  booted && break
  kill -0 "$START_PID" 2>/dev/null || break
  sleep 10
done
if booted; then echo booted > "$OUT/11-boot.txt"; else echo "not booted" > "$OUT/11-boot.txt"; collect_vvd_logs boot; fi
run 13-device-list vega device list
run 14-vvd-status vega virtual-device status

if [ -n "$VPKG" ] && connected; then
  # 起動直後は VDA の接続がまだ offline のことがあるため、入るまで繰り返す
  for i in $(seq 1 15); do
    run 21-install dev install-app --packagePath "$VPKG"
    grep -q '\[exit 0\]' "$OUT/21-install.txt" && break
    sleep 20
  done
  run 21b-installed dev is-app-installed --appName "$APP_ID"
  # 起動から終わりまで、端末のログを流し続けて記録する (アプリ分と全体)
  ( timeout 420 vega device run-cmd -d VirtualDevice --command 'loggingctl log -f -o short_precise -v com.firetube.tv.vega' > "$OUT/60-stream-app.txt" 2>&1 ) &
  ( timeout 420 vega device run-cmd -d VirtualDevice --command 'loggingctl log -f -o short_precise' > "$OUT/61-stream-all.txt" 2>&1 ) &
  sleep 5
  run 22-launch dev launch-app --appName "$APP_ID"
  sleep 60
  run 24-is-running dev is-app-running --appName "$APP_ID"
  # run-cmd では loggingctl の出力が返らないため、端末内のファイルに書いてから copy-from で取り出す
  applog() { # applog <記録名>
    # オプション無しの loggingctl log はヘルプを出すだけなので、出力形式を指定する
    dev run-cmd --command "loggingctl log -o short_precise > /tmp/$1.log 2>&1" > /dev/null 2>&1
    vega device copy-from -d VirtualDevice --source "/tmp/$1.log" --destination "$OUT/" > "$OUT/$1-copy.txt" 2>&1
    grep -iE 'FireTube|ReactNativeJS|Exception|TypeError|ReferenceError|fatal|w3cmedia|KeplerScript' "$OUT/$1.log" 2>/dev/null | tail -n 600 > "$OUT/$1-app.txt"
  }
  run 23-log-info dev get-log-info
  run 23b-copy-from-help vega device copy-from --help
  key() { vega exec inputd-cli button_press "$1" >> "$OUT/50-keys.txt" 2>&1 || dev run-cmd --command "inputd-cli button_press $1" >> "$OUT/50-keys.txt" 2>&1; sleep 2; }
  applog 40-log-home
  # ホームの先頭の動画 (起動時のフォーカス位置) を決定で開き、再生が始まるか見る
  key KEY_ENTER
  sleep 60
  applog 41-log-after-enter
  run 42-is-running dev is-app-running --appName "$APP_ID"
  # 再生中の操作: 右 (10 秒進む) → 下 (メニュー) → 戻る (閉じる) → 戻る (一覧へ)
  key KEY_RIGHT; key KEY_DOWN; sleep 3; key KEY_BACK; key KEY_BACK
  sleep 10
  applog 43-log-after-keys
  run 44-is-running dev is-app-running --appName "$APP_ID"
  wait_streams() { sleep 5; grep -hiE 'FireTube|ReactNativeJS|Exception|Error' "$OUT/60-stream-app.txt" "$OUT/61-stream-all.txt" 2>/dev/null | grep -v acr_core_dump | tail -n 800 > "$OUT/62-stream-app-lines.txt"; }
  wait_streams
  mkdir -p "$OUT/device-logs" "$OUT/doctor"
  # アプリのログは main/var_log 側にある
  run 46-copy-logs dev copy-logs --artifact main/var_log --directory "$OUT/device-logs"
  grep -iE 'FireTube|ReactNativeJS|Exception|TypeError|ReferenceError|w3cmedia|KeplerScript|com.firetube' "$OUT"/device-logs/*.log 2>/dev/null | tail -n 1500 > "$OUT/48-main-log-app.txt"
  run 47-doctor dev doctor --directory "$OUT/doctor" --since 30m --appName "$APP_ID"
  vega device stop-log-stream -d VirtualDevice > /dev/null 2>&1
fi

collect_vvd_logs final
run 90-vvd-stop vega virtual-device stop
find "$OUT" -type f -size +5M -delete
ls -laR "$OUT" > "$OUT/99-files.txt"
exit 0
