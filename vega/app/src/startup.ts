/** 起動直後に裏でやっておく準備 (最初の画面表示・最初の再生を待たせないため) */
import {loadConfig} from './api/config';
import {
  prefetchVisitorData,
  restoreVisitorData,
  VisitorState,
} from './api/innertube';
import {persisted} from './store/persisted';

/** visitorData は数日は有効だが、念のため半日で取り直す */
export const VISITOR_TTL_MS = 12 * 60 * 60 * 1000;
/** 人気の動画の取得 (起動直後の通信) と重ならないよう少し遅らせる */
const PREFETCH_DELAY_MS = 3000;

export const visitorCache = persisted<VisitorState | null>(
  'visitor',
  null,
  (raw) => {
    const v = raw as VisitorState | null;
    return v &&
      typeof v.visitorData === 'string' &&
      Number.isFinite(v.signatureTimestamp) &&
      Number.isFinite(v.fetchedAt)
      ? v
      : null;
  },
);

/** hydrateAll() の後に呼ぶ */
export function warmUp(now = Date.now()) {
  loadConfig();
  const cached = visitorCache.get();
  if (cached && now - cached.fetchedAt < VISITOR_TTL_MS) {
    restoreVisitorData(cached);
    return;
  }
  setTimeout(() => {
    prefetchVisitorData()
      .then((state) => state && visitorCache.set(state))
      .catch(() => {});
  }, PREFETCH_DELAY_MS);
}
