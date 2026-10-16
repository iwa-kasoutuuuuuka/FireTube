import {fetchWithTimeout} from './config';
import {SponsorSegment} from './types';

const CATEGORIES = ['sponsor', 'intro', 'outro', 'selfpromo', 'interaction'];

/** SponsorBlock のスキップ区間 (秒)。404 は「区間なし」の正常系 */
export async function getSponsorSegments(
  videoId: string,
): Promise<SponsorSegment[]> {
  const url =
    `https://sponsor.ajay.app/api/skipSegments?videoID=${encodeURIComponent(
      videoId,
    )}` + `&categories=${encodeURIComponent(JSON.stringify(CATEGORIES))}`;
  try {
    const res = await fetchWithTimeout(url, {}, 8000);
    if (!res.ok) {
      return [];
    }
    const json = (await res.json()) as {
      segment?: [number, number];
      category?: string;
    }[];
    return json
      .filter((s) => Array.isArray(s.segment) && s.segment[1] > s.segment[0])
      .map((s) => ({
        start: s.segment![0],
        end: s.segment![1],
        category: s.category ?? '',
      }));
  } catch {
    return [];
  }
}

export const CATEGORY_LABELS: Record<string, string> = {
  sponsor: 'スポンサー',
  intro: 'イントロ',
  outro: 'エンドカード',
  selfpromo: '宣伝',
  interaction: '登録のお願い',
};
