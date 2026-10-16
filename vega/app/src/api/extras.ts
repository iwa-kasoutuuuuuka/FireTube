/** InnerTube 以外の補助 API (検索候補・Return YouTube Dislike) */
import {fetchWithTimeout} from './config';

export interface Votes {
  likes: number;
  dislikes: number;
}

const VOTES_CACHE_MAX = 50;
const votesCache = new Map<string, Votes>();

/** Return YouTube Dislike の推定値。取れなければ null */
export async function getVotes(videoId: string): Promise<Votes | null> {
  const hit = votesCache.get(videoId);
  if (hit) {
    return hit;
  }
  try {
    const res = await fetchWithTimeout(
      `https://returnyoutubedislikeapi.com/votes?videoId=${encodeURIComponent(
        videoId,
      )}`,
      {},
      8000,
    );
    if (!res.ok) {
      return null;
    }
    const json = (await res.json()) as {likes?: number; dislikes?: number};
    const votes = {
      likes: Number(json.likes) || 0,
      dislikes: Number(json.dislikes) || 0,
    };
    votesCache.set(videoId, votes);
    if (votesCache.size > VOTES_CACHE_MAX) {
      votesCache.delete(votesCache.keys().next().value as string);
    }
    return votes;
  } catch {
    return null;
  }
}

/** YouTube の検索候補 (応答は ["入力", ["候補1", "候補2", ...]]) */
export async function getSuggestions(query: string): Promise<string[]> {
  const q = query.trim();
  if (!q) {
    return [];
  }
  try {
    const res = await fetchWithTimeout(
      `https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=ja&gl=jp&q=${encodeURIComponent(
        q,
      )}`,
      {},
      5000,
    );
    if (!res.ok) {
      return [];
    }
    return parseSuggestions(await res.json());
  } catch {
    return [];
  }
}

export function parseSuggestions(json: unknown): string[] {
  const list = Array.isArray(json) ? json[1] : undefined;
  return Array.isArray(list)
    ? list.filter((s): s is string => typeof s === 'string').slice(0, 10)
    : [];
}

/** 1234 → "1,234"、12345 → "1.2万"、123456789 → "1.2億" */
export function formatCount(n: number): string {
  if (n >= 1e8) {
    return `${trim(n / 1e8)}億`;
  }
  if (n >= 1e4) {
    return `${trim(n / 1e4)}万`;
  }
  // Hermes の Intl 対応は端末次第なので toLocaleString は使わない
  return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function trim(v: number): string {
  return (v >= 100 ? Math.round(v) : Math.round(v * 10) / 10).toString();
}
