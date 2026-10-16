#!/usr/bin/env bash
# CI 用: 再生テスト用の動画を作り、アプリがそれを再生するように src/debugStream.ts を書き換える。
#   - DASH: 映像 (H.264) と音声 (AAC) を別々の fMP4 (sidx 付き) にする (YouTube の adaptiveFormats と同じ形)
#   - HLS : 映像+音声の TS
# 仮想端末からは、ホスト (この CI マシン) が 10.0.2.2 に見える
set -euo pipefail
MEDIA="${MEDIA:-/tmp/test-media}"
HOST_URL="${HOST_URL:-http://10.0.2.2:8000}"
mkdir -p "$MEDIA/hls"
SRC=(-f lavfi -i "testsrc2=size=426x240:rate=30:duration=60" -f lavfi -i "sine=frequency=440:duration=60")
FRAG=(-movflags +frag_keyframe+empty_moov+default_base_moof+global_sidx -g 60 -keyint_min 60 -sc_threshold 0)
ffmpeg -loglevel error -y "${SRC[@]}" -map 0:v -c:v libx264 -profile:v main -level 3.0 -pix_fmt yuv420p "${FRAG[@]}" "$MEDIA/v.mp4"
ffmpeg -loglevel error -y "${SRC[@]}" -map 1:a -c:a aac -b:a 128k -movflags +frag_keyframe+empty_moov+default_base_moof+global_sidx -frag_duration 2000000 "$MEDIA/a.mp4"
ffmpeg -loglevel error -y "${SRC[@]}" -c:v libx264 -profile:v main -level 3.0 -pix_fmt yuv420p -g 60 -c:a aac -f hls -hls_time 4 -hls_playlist_type vod "$MEDIA/hls/index.m3u8"
python3 "$(dirname "$0")/write-debug-stream.py" "$MEDIA" "$HOST_URL" vega/app/src/debugStream.ts
cat vega/app/src/debugStream.ts
