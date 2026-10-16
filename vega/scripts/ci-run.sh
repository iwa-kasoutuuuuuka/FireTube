#!/usr/bin/env bash
# CI 用: コマンドを実行し、失敗時はログ末尾を GitHub のアノテーションとして出す。
# (Actions のログは未ログインだと読めないが、アノテーションは公開 API で読めるため)
# 使い方: ci-run.sh <タイトル> <コマンド...>
set -uo pipefail
title="$1"; shift
log="$(mktemp)"
"$@" 2>&1 | tee "$log"
code=${PIPESTATUS[0]}
if [ "$code" -ne 0 ]; then
  # 進捗表示などのノイズを除いてから末尾を取る
  body="$(grep -v -E '^\s*$' "$log" | tail -n 80 | tail -c 6000)"
  body="${body//'%'/'%25'}"; body="${body//$'\r'/}"; body="${body//$'\n'/'%0A'}"
  echo "::error title=${title} (exit ${code})::${body}"
fi
exit "$code"
