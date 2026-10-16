/**
 * CI の仮想端末テスト用の再生情報。通常は null で、CI (vega/scripts/vvd-smoke.sh) だけが
 * テスト用の動画を指すように書き換えてビルドする (CI からは YouTube が bot 扱いで再生情報を返さないため)
 */
import {StreamInfo} from './api/types';

export const DEBUG_STREAM: StreamInfo | null = null;
