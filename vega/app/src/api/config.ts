/**
 * リモート設定 (リポジトリ直下の remote-config.json)。
 * Android 版と同じファイルを読み、YouTube 側の変更に .vpkg の再配布なしで追従する。
 */
export interface RemoteConfig {
  pipedInstances: string[];
  playerRaceClients: string[];
  playerFallbackClients: string[];
  popularQueries: string[];
  clientVersions: Record<string, string>;
}

const CONFIG_URL =
  'https://raw.githubusercontent.com/iwa-kasoutuuuuuka/FireTube/main/remote-config.json';

export const DEFAULT_CONFIG: RemoteConfig = {
  pipedInstances: [
    'https://api.piped.private.coffee',
    'https://pipedapi-libre.kavin.rocks',
  ],
  playerRaceClients: ['IOS_KIDS', 'VISIONOS'],
  playerFallbackClients: ['IOS_EMBEDDED', 'MWEB', 'ANDROID_KIDS', 'IOS'],
  popularQueries: [
    'ニュース',
    '音楽 MV',
    'ゲーム実況',
    'スポーツ ハイライト',
    'アニメ 公式',
  ],
  clientVersions: {},
};

let current: RemoteConfig = DEFAULT_CONFIG;
let loading: Promise<RemoteConfig> | null = null;

function stringList(value: unknown, fallback: string[]): string[] {
  if (!Array.isArray(value)) return fallback;
  const list = value.filter((v): v is string => typeof v === 'string' && v.length > 0);
  return list.length > 0 ? list : fallback;
}

export function parseConfig(json: unknown): RemoteConfig {
  const obj = (json ?? {}) as Record<string, unknown>;
  const versions: Record<string, string> = {};
  const rawVersions = obj.clientVersions;
  if (rawVersions && typeof rawVersions === 'object') {
    for (const [k, v] of Object.entries(rawVersions as Record<string, unknown>)) {
      if (typeof v === 'string' && v.length > 0) versions[k] = v;
    }
  }
  return {
    pipedInstances: stringList(obj.pipedInstances, DEFAULT_CONFIG.pipedInstances),
    playerRaceClients: stringList(obj.playerRaceClients, DEFAULT_CONFIG.playerRaceClients),
    playerFallbackClients: stringList(
      obj.playerFallbackClients,
      DEFAULT_CONFIG.playerFallbackClients,
    ),
    popularQueries: stringList(obj.popularQueries, DEFAULT_CONFIG.popularQueries),
    clientVersions: versions,
  };
}

/** 起動時に 1 回だけ取得する。失敗時は組み込みの既定値を使う */
export function loadConfig(): Promise<RemoteConfig> {
  if (!loading) {
    loading = fetchWithTimeout(CONFIG_URL, {}, 5000)
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`))))
      .then((json) => {
        current = parseConfig(json);
        return current;
      })
      .catch(() => current);
  }
  return loading;
}

export function getConfig(): RemoteConfig {
  return current;
}

export async function fetchWithTimeout(
  url: string,
  init: RequestInit,
  timeoutMs: number,
): Promise<Response> {
  return await Promise.race([
    fetch(url, init),
    new Promise<Response>((_, reject) =>
      setTimeout(() => reject(new Error('timeout')), timeoutMs),
    ),
  ]);
}
