/** 履歴・後で見る・登録チャンネル・再生位置 (端末内に保存) */
import {VideoItem} from '../api/types';
import {persisted} from './persisted';

export {useStore, useStoreSelector} from './persisted';

export interface Channel {
  id: string;
  name: string;
}

export interface HistoryEntry {
  video: VideoItem;
  watchedAt: number;
}

export interface WatchPosition {
  position: number;
  duration: number;
  updatedAt: number;
}

const HISTORY_MAX = 200;
const POSITIONS_MAX = 300;

function isVideo(v: unknown): v is VideoItem {
  const o = v as VideoItem | null;
  return !!o && typeof o.id === 'string' && typeof o.title === 'string';
}

function normalizeVideo(v: VideoItem): VideoItem {
  return {
    id: v.id,
    title: v.title,
    uploaderName: typeof v.uploaderName === 'string' ? v.uploaderName : '',
    thumbnailUrl: typeof v.thumbnailUrl === 'string' ? v.thumbnailUrl : '',
    durationSeconds: Number(v.durationSeconds) || 0,
    channelId: typeof v.channelId === 'string' ? v.channelId : undefined,
  };
}

export const history = persisted<HistoryEntry[]>('history', [], (raw) =>
  Array.isArray(raw)
    ? raw
        .filter((e) => isVideo(e?.video))
        .map((e) => ({
          video: normalizeVideo(e.video),
          watchedAt: Number(e.watchedAt) || 0,
        }))
    : [],
);

export const watchLater = persisted<VideoItem[]>('watchLater', [], (raw) =>
  Array.isArray(raw) ? raw.filter(isVideo).map(normalizeVideo) : [],
);

export const subscriptions = persisted<Channel[]>('subscriptions', [], (raw) =>
  Array.isArray(raw)
    ? raw
        .filter((c) => typeof c?.id === 'string' && typeof c?.name === 'string')
        .map((c) => ({id: c.id, name: c.name}))
    : [],
);

export const positions = persisted<Record<string, WatchPosition>>(
  'positions',
  {},
  (raw) =>
    raw && typeof raw === 'object'
      ? (raw as Record<string, WatchPosition>)
      : {},
);

// ---------------------------------------------------------------- 操作

export function addHistory(video: VideoItem, now = Date.now()) {
  history.set((list) =>
    [
      {video: normalizeVideo(video), watchedAt: now},
      ...list.filter((e) => e.video.id !== video.id),
    ].slice(0, HISTORY_MAX),
  );
}

export function clearHistory() {
  history.set([]);
  positions.set({});
}

export function isInWatchLater(id: string): boolean {
  return watchLater.get().some((v) => v.id === id);
}

/** @returns 追加後に入っていれば true */
export function toggleWatchLater(video: VideoItem): boolean {
  const exists = isInWatchLater(video.id);
  watchLater.set((list) =>
    exists
      ? list.filter((v) => v.id !== video.id)
      : [normalizeVideo(video), ...list],
  );
  return !exists;
}

export function removeWatchLater(id: string) {
  watchLater.set((list) => list.filter((v) => v.id !== id));
}

export function isSubscribed(channelId: string): boolean {
  return subscriptions.get().some((c) => c.id === channelId);
}

/** @returns 登録後に入っていれば true */
export function toggleSubscription(channel: Channel): boolean {
  const exists = isSubscribed(channel.id);
  subscriptions.set((list) =>
    exists ? list.filter((c) => c.id !== channel.id) : [...list, channel],
  );
  return !exists;
}

/** 最後まで見た (残り 15 秒未満) 動画は位置を消し、次回は最初から再生する */
export function savePosition(
  id: string,
  position: number,
  duration: number,
  now = Date.now(),
) {
  positions.set((map) => {
    const next = {...map};
    if (duration > 0 && position > duration - 15) {
      delete next[id];
    } else if (position >= 10) {
      next[id] = {position, duration, updatedAt: now};
    } else {
      return map;
    }
    const keys = Object.keys(next);
    if (keys.length > POSITIONS_MAX) {
      keys
        .sort((a, b) => next[a].updatedAt - next[b].updatedAt)
        .slice(0, keys.length - POSITIONS_MAX)
        .forEach((k) => delete next[k]);
    }
    return next;
  });
}

export function resumePosition(id: string): number {
  return positions.get()[id]?.position ?? 0;
}

// ---------------------------------------------------------------- 検索履歴

const SEARCH_HISTORY_MAX = 20;

export const searchHistory = persisted<string[]>('searchHistory', [], (raw) =>
  Array.isArray(raw)
    ? raw.filter((q): q is string => typeof q === 'string' && q.length > 0)
    : [],
);

export function addSearchHistory(query: string) {
  const q = query.trim();
  if (!q) {
    return;
  }
  searchHistory.set((list) =>
    [q, ...list.filter((x) => x !== q)].slice(0, SEARCH_HISTORY_MAX),
  );
}
