/**
 * 再生情報の先読み。一覧でカードにしばらくフォーカスが留まったら裏で取得しておき、
 * 決定を押したときの待ち (InnerTube player の往復) をなくす。
 * googlevideo の URL は数時間で失効するため、古いものは使わない
 */
import {extractStreamInfo} from './innertube';
import {StreamInfo} from './types';

const TTL_MS = 20 * 60 * 1000;
const MAX_ENTRIES = 20;

interface Entry {
  promise: Promise<StreamInfo>;
  maxHeight: number;
  at: number;
}

const entries = new Map<string, Entry>();

function fresh(e: Entry | undefined, maxHeight: number, now: number) {
  return !!e && e.maxHeight === maxHeight && now - e.at < TTL_MS;
}

function start(videoId: string, maxHeight: number, now: number): Entry {
  const entry: Entry = {
    promise: extractStreamInfo(videoId, [], maxHeight),
    maxHeight,
    at: now,
  };
  // 失敗したものは残さない (次は取り直す)
  entry.promise.catch(() => {
    if (entries.get(videoId) === entry) {
      entries.delete(videoId);
    }
  });
  entries.delete(videoId);
  entries.set(videoId, entry);
  while (entries.size > MAX_ENTRIES) {
    entries.delete(entries.keys().next().value as string);
  }
  return entry;
}

/** 裏で取得しておく (取得済みで新しければ何もしない) */
export function prefetchStream(
  videoId: string,
  maxHeight: number,
  now = Date.now(),
) {
  if (!fresh(entries.get(videoId), maxHeight, now)) {
    start(videoId, maxHeight, now);
  }
}

/** 先読み済みならそれを、無ければ今から取得する。使った分はキャッシュから外す */
export function takeStream(
  videoId: string,
  maxHeight: number,
  now = Date.now(),
): Promise<StreamInfo> {
  const e = entries.get(videoId);
  const entry = fresh(e, maxHeight, now) ? e! : start(videoId, maxHeight, now);
  entries.delete(videoId);
  return entry.promise;
}

export function clearStreamCache() {
  entries.clear();
}
