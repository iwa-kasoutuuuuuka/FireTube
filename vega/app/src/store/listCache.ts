/**
 * 一覧のキャッシュ (端末内)。起動直後は前回の一覧をすぐ表示し、裏で取り直したものに差し替える。
 * 人気の動画は 5 ジャンル分の検索 (応答は計 4MB 以上) が要るため、これが無いと毎回待たされる
 */
import {VideoItem} from '../api/types';
import {persisted} from './persisted';

interface CachedList {
  /** 登録チャンネルの一覧など、内容の前提が変わったら使わないための鍵 */
  key: string;
  items: VideoItem[];
  savedAt: number;
}

const MAX_ITEMS = 120;

const lists = persisted<Record<string, CachedList>>('listCache', {}, (raw) =>
  raw && typeof raw === 'object' ? (raw as Record<string, CachedList>) : {},
);

export function getCachedList(name: string, key = ''): VideoItem[] {
  const c = lists.get()[name];
  return c && c.key === key && Array.isArray(c.items) ? c.items : [];
}

export function putCachedList(
  name: string,
  items: VideoItem[],
  key = '',
  now = Date.now(),
) {
  lists.set((all) => ({
    ...all,
    [name]: {key, items: items.slice(0, MAX_ITEMS), savedAt: now},
  }));
}
