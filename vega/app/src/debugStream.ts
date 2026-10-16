/**
 * CI の仮想端末テスト用の再生情報。通常は空で、CI (vega/scripts/vvd-smoke.sh) だけが
 * テスト用の動画を指すように書き換えてビルドする (CI からは YouTube が bot 扱いで再生情報を返さないため)。
 * 再生画面を開くたびに順に使う (1 本目で DASH、2 本目で HLS を試す)
 */
import {StreamInfo} from './api/types';

export const DEBUG_STREAMS: StreamInfo[] = [];

let opened = 0;

export function nextDebugStream(): StreamInfo | null {
  return DEBUG_STREAMS.length > 0
    ? DEBUG_STREAMS[opened++ % DEBUG_STREAMS.length]
    : null;
}
