/**
 * YouTube InnerTube クライアント (Android 版 InnerTubeClient.kt の TypeScript 移植)。
 * NewPipeExtractor は使えないため、一覧も再生 URL も InnerTube を直接叩く。
 */
import {fetchWithTimeout, getConfig} from './config';
import {
  CaptionTrack,
  Cue,
  DashTrack,
  PlaybackSource,
  StreamInfo,
  VideoItem,
} from './types';

const BASE_URL = 'https://www.youtube.com/youtubei/v1';
const TIMEOUT_MS = 12000;

/** 動画のみ・今週アップロード・視聴回数順 */
export const SEARCH_FILTER_POPULAR_THIS_WEEK = 'CAMSBAgDEAE=';

const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)';
const CHROME_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const VISIONOS_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';
const MWEB_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1';

type Json = Record<string, any>;

function version(client: string, builtIn: string): string {
  return getConfig().clientVersions[client] ?? builtIn;
}

async function postJson(
  path: string,
  body: Json,
  headers: Record<string, string> = {},
): Promise<Json> {
  const res = await fetchWithTimeout(
    `${BASE_URL}/${path}`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'User-Agent': DESKTOP_UA,
        ...headers,
      },
      body: JSON.stringify(body),
    },
    TIMEOUT_MS,
  );
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return (await res.json()) as Json;
}

function webContext(): Json {
  return {
    client: {
      hl: 'ja',
      gl: 'JP',
      clientName: 'WEB',
      clientVersion: version('WEB', '2.20240401.01.00'),
      utcOffsetMinutes: 540,
    },
  };
}

// ---------------------------------------------------------------- 一覧

export async function searchVideos(
  query: string,
  params?: string,
): Promise<VideoItem[]> {
  const body: Json = {context: webContext(), query};
  if (params) {
    body.params = params;
  }
  const json = await postJson('search', body);
  return parseVideoRenderers(json);
}

/**
 * 「人気の動画」。急上昇 (FEtrending) は廃止されたため、
 * 複数ジャンルを「今週 × 視聴回数順」で検索し交互に並べる (Android 版と同じ方式)
 */
export async function getPopularVideos(
  queries: string[],
): Promise<VideoItem[]> {
  const lists = await Promise.all(
    queries.map((q) =>
      searchVideos(q, SEARCH_FILTER_POPULAR_THIS_WEEK).catch(() => []),
    ),
  );
  const items = interleave(lists);
  if (items.length === 0) {
    throw new Error('人気の動画を取得できませんでした');
  }
  return items;
}

export async function getRelatedVideos(videoId: string): Promise<VideoItem[]> {
  const json = await postJson('next', {context: webContext(), videoId});
  return parseVideoRenderers(json).filter((v) => v.id !== videoId);
}

export function interleave(lists: VideoItem[][]): VideoItem[] {
  const seen = new Set<string>();
  const result: VideoItem[] = [];
  const max = Math.max(0, ...lists.map((l) => l.length));
  for (let i = 0; i < max; i++) {
    for (const list of lists) {
      const item = list[i];
      if (item && !seen.has(item.id)) {
        seen.add(item.id);
        result.push(item);
      }
    }
  }
  return result;
}

const INVALID_TITLE = /非公開動画|private video|deleted video|削除された動画/i;
const UPCOMING = /プレミア|公開予定|UPCOMING/i;
const VIEW_COUNT = /回視聴|views?$/i;

function isValid(item: VideoItem): boolean {
  if (!item.id || item.id.length < 5) {
    return false;
  }
  const t = item.title.trim();
  if (!t || t === 'No title' || t === 'Unknown Title') {
    return false;
  }
  return !INVALID_TITLE.test(t);
}

function runsText(obj: Json | undefined): string | undefined {
  if (!obj) {
    return undefined;
  }
  if (Array.isArray(obj.runs)) {
    const s = obj.runs
      .map((r: Json) => r?.text ?? '')
      .join('')
      .trim();
    if (s) {
      return s;
    }
  }
  return typeof obj.simpleText === 'string' ? obj.simpleText : undefined;
}

export function parseDuration(text?: string): number {
  if (!text) {
    return 0;
  }
  return text
    .split(':')
    .reduce((sec, p) => sec * 60 + (Number.parseInt(p, 10) || 0), 0);
}

/** 構造変更に強いよう、JSON 全体を再帰的に探索して動画を拾う */
export function parseVideoRenderers(root: Json): VideoItem[] {
  const result: VideoItem[] = [];
  const seen = new Set<string>();
  const add = (item: VideoItem | null) => {
    if (item && isValid(item) && !seen.has(item.id)) {
      seen.add(item.id);
      result.push(item);
    }
  };
  const walk = (node: any, depth: number) => {
    if (depth > 32 || node === null || typeof node !== 'object') {
      return;
    }
    if (Array.isArray(node)) {
      for (const child of node) {
        walk(child, depth + 1);
      }
      return;
    }
    for (const [key, value] of Object.entries(node)) {
      if (value === null || typeof value !== 'object') {
        continue;
      }
      if (
        key === 'videoRenderer' ||
        key === 'compactVideoRenderer' ||
        key === 'gridVideoRenderer'
      ) {
        add(parseVideoRenderer(value as Json));
      } else if (key === 'lockupViewModel') {
        add(parseLockup(value as Json));
      } else {
        walk(value, depth + 1);
      }
    }
  };
  walk(root, 0);
  return result;
}

function parseVideoRenderer(obj: Json): VideoItem | null {
  const id = obj.videoId;
  if (typeof id !== 'string') {
    return null;
  }
  if (obj.isPlayable === false || obj.unplayableText || obj.upcomingEventData) {
    return null;
  }
  const badges: Json[] = Array.isArray(obj.badges) ? obj.badges : [];
  if (
    badges.some((b) => UPCOMING.test(b?.metadataBadgeRenderer?.label ?? ''))
  ) {
    return null;
  }

  const owner = obj.ownerText ?? obj.shortBylineText ?? obj.longBylineText;
  return {
    id,
    title: runsText(obj.title) ?? 'No title',
    uploaderName:
      runsText(obj.ownerText) ??
      runsText(obj.shortBylineText) ??
      runsText(obj.longBylineText) ??
      '',
    thumbnailUrl: thumbnailFor(id),
    durationSeconds: parseDuration(runsText(obj.lengthText)),
    channelId: channelIdOf(owner?.runs?.[0]?.navigationEndpoint),
  };
}

/**
 * 一覧用のサムネイル (480x360・約 18KB)。応答に入っている hq720 (1280x720・約 220KB) は
 * カード (幅 400px) には大きすぎ、Fire TV Stick では通信量・デコード・メモリの負担になる
 */
export function thumbnailFor(videoId: string): string {
  return `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
}

/** browseEndpoint.browseId が "UC..." ならチャンネル ID */
function channelIdOf(endpoint: Json | undefined): string | undefined {
  const id = endpoint?.browseEndpoint?.browseId;
  return typeof id === 'string' && /^UC[\w-]{22}$/.test(id) ? id : undefined;
}

function parseLockup(obj: Json): VideoItem | null {
  if (obj.contentType && obj.contentType !== 'LOCKUP_CONTENT_TYPE_VIDEO') {
    return null;
  }
  const id: unknown =
    obj.contentId ||
    obj.rendererContext?.commandContext?.onTap?.innertubeCommand?.watchEndpoint
      ?.videoId;
  if (typeof id !== 'string') {
    return null;
  }

  const meta = obj.metadata?.lockupMetadataViewModel;
  const ownerText =
    meta?.metadata?.contentMetadataViewModel?.metadataRows?.[0]
      ?.metadataParts?.[0]?.text;
  const uploader = ownerText?.content;
  const image = obj.contentImage?.thumbnailViewModel;

  let duration = 0;
  for (const ov of image?.overlays ?? []) {
    for (const b of ov?.thumbnailBottomOverlayViewModel?.badges ?? []) {
      const t: string | undefined = b?.thumbnailBadgeViewModel?.text;
      if (!t) {
        continue;
      }
      if (UPCOMING.test(t)) {
        return null;
      }
      if (t.includes(':')) {
        duration = parseDuration(t);
      }
    }
  }
  return {
    id,
    title: meta?.title?.content ?? 'No title',
    uploaderName:
      typeof uploader === 'string' && !VIEW_COUNT.test(uploader)
        ? uploader
        : '',
    thumbnailUrl: thumbnailFor(id),
    durationSeconds: duration,
    channelId: channelIdOf(
      ownerText?.commandRuns?.[0]?.onTap?.innertubeCommand,
    ),
  };
}

// ---------------------------------------------------------------- チャンネル

/** チャンネル「動画」タブ (新しい順) を指定する browse params */
const CHANNEL_VIDEOS_TAB_PARAMS = 'EgZ2aWRlb3PyBgQKAjoA';

/**
 * チャンネルの新着動画。動画タブが空 (ショート・ライブのみ等) ならホームタブにフォールバック。
 * 一覧の投稿者名・チャンネル ID はチャンネル自身で埋める (タブ内の項目には無いことが多い)
 */
export async function getChannelVideos(
  channelId: string,
  channelName?: string,
): Promise<VideoItem[]> {
  const browse = (params?: string) =>
    postJson('browse?prettyPrint=false', {
      context: webContext(),
      browseId: channelId,
      ...(params ? {params} : {}),
    }).then(parseVideoRenderers);
  let items = await browse(CHANNEL_VIDEOS_TAB_PARAMS);
  if (items.length === 0) {
    items = await browse();
  }
  return items.map((v) => ({
    ...v,
    channelId: v.channelId ?? channelId,
    // タブ内の項目は 1 行目が投稿者でなく視聴回数のことがあるため、チャンネル名で上書きする
    uploaderName: channelName || v.uploaderName,
  }));
}

// ---------------------------------------------------------------- 再生 URL

let visitorData: string | undefined;
let signatureTimestamp = 20711;
let visitorTask: Promise<void> | null = null;

/** 端末に保存して次回起動時に使い回す visitorData (watch ページ約 1.4MB の取得を省く) */
export interface VisitorState {
  visitorData: string;
  signatureTimestamp: number;
  fetchedAt: number;
}

/** 保存済みの値を使う。以後 ensureVisitorData は通信しない */
export function restoreVisitorData(state: VisitorState) {
  visitorData = state.visitorData;
  signatureTimestamp = state.signatureTimestamp;
  visitorTask = Promise.resolve();
}

/**
 * watch ページから visitorData と signatureTimestamp を取る (VISIONOS / 埋め込み IOS 用)。
 * 同時に呼ばれても取得は 1 回。失敗したら次の呼び出しで取り直す
 */
function ensureVisitorData(): Promise<void> {
  if (!visitorTask) {
    visitorTask = fetchVisitorData().then(
      (ok) => {
        if (!ok) {
          visitorTask = null;
        }
      },
      () => {
        visitorTask = null;
      },
    );
  }
  return visitorTask;
}

async function fetchVisitorData(): Promise<boolean> {
  const res = await fetchWithTimeout(
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
    {headers: {'User-Agent': CHROME_UA}},
    TIMEOUT_MS,
  );
  if (!res.ok) {
    return false;
  }
  const html = await res.text();
  visitorData = /["']visitorData["']\s*:\s*["']([^"']+)["']/.exec(html)?.[1];
  const sts = /["']signatureTimestamp["']\s*:\s*(\d+)/.exec(html)?.[1];
  if (sts) {
    signatureTimestamp = Number(sts);
  }
  return visitorData !== undefined;
}

/**
 * 最初の再生を待たせないよう、起動後に先に取得しておく。
 * @returns 取得できたら保存用の値 (取れなければ null)
 */
export async function prefetchVisitorData(
  now = Date.now(),
): Promise<VisitorState | null> {
  await ensureVisitorData();
  return visitorData ? {visitorData, signatureTimestamp, fetchedAt: now} : null;
}

interface ClientSpec {
  context: Json;
  userAgent: string;
  extraBody?: Json;
  headers?: Record<string, string>;
  needsVisitor?: boolean;
}

function iosClient(name: 'IOS' | 'IOS_KIDS', ver: string): Json {
  return {
    hl: 'ja',
    gl: 'JP',
    clientName: name,
    clientVersion: ver,
    clientScreen: 'WATCH',
    platform: 'MOBILE',
    deviceMake: 'Apple',
    deviceModel: 'iPhone16,2',
    osName: 'iOS',
    osVersion: '18.7.2.22H124',
    utcOffsetMinutes: 540,
  };
}

function clientSpec(name: string): ClientSpec | null {
  const iosVer = version('IOS', '21.03.2');
  const iosUa = `com.google.ios.youtube/${iosVer}(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; ja_JP)`;
  switch (name) {
    case 'IOS':
      return {context: {client: iosClient('IOS', iosVer)}, userAgent: iosUa};
    case 'IOS_EMBEDDED':
      return {
        context: {client: iosClient('IOS', iosVer)},
        userAgent: iosUa,
        needsVisitor: true,
        extraBody: {
          playbackContext: {
            contentPlaybackContext: {html5Preference: 'HTML5_PREF_WANTS'},
          },
          thirdParty: {embedUrl: 'https://www.google.com'},
          contentCheckOk: true,
          racyCheckOk: true,
        },
      };
    case 'IOS_KIDS': {
      const ver = version('IOS_KIDS', '9.01.0');
      return {
        context: {client: iosClient('IOS_KIDS', ver)},
        userAgent: `com.google.ios.youtubekids/${ver} (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; ja_JP)`,
      };
    }
    case 'ANDROID_KIDS': {
      const ver = version('ANDROID_KIDS', '9.01.0');
      return {
        context: {
          client: {
            hl: 'ja',
            gl: 'JP',
            clientName: 'ANDROID_KIDS',
            clientVersion: ver,
            androidSdkVersion: 30,
            osName: 'Android',
            osVersion: '11',
            platform: 'MOBILE',
            utcOffsetMinutes: 540,
          },
        },
        userAgent: `com.google.android.apps.youtube.kids/${ver} (Linux; U; Android 11)`,
      };
    }
    case 'MWEB':
      return {
        context: {
          client: {
            hl: 'ja',
            gl: 'JP',
            clientName: 'MWEB',
            clientVersion: version('MWEB', '2.20231201.01.00'),
            platform: 'MOBILE',
            utcOffsetMinutes: 540,
          },
        },
        userAgent: MWEB_UA,
      };
    case 'VISIONOS': {
      const ver = version('VISIONOS', '1.02');
      return {
        context: {
          client: {
            hl: 'ja',
            gl: 'JP',
            clientName: 'VISIONOS',
            clientVersion: ver,
            deviceMake: 'Apple',
            deviceModel: 'RealityDevice17,1',
            userAgent: VISIONOS_UA,
            osName: 'visionOS',
            osVersion: '26.5.23O471',
            utcOffsetMinutes: 540,
          },
        },
        userAgent: VISIONOS_UA,
        needsVisitor: true,
        headers: {
          'X-Youtube-Client-Name': '101',
          'X-Youtube-Client-Version': ver,
          Origin: 'https://www.youtube.com',
        },
        extraBody: {
          playbackContext: {
            contentPlaybackContext: {html5Preference: 'HTML5_PREF_WANTS'},
          },
          contentCheckOk: true,
          racyCheckOk: true,
        },
      };
    }
    default:
      return null;
  }
}

async function fetchPlayer(
  name: string,
  videoId: string,
  maxHeight: number,
): Promise<StreamInfo> {
  const spec = clientSpec(name);
  if (!spec) {
    throw new Error(`Unknown client ${name}`);
  }
  if (spec.needsVisitor) {
    await ensureVisitorData();
  }

  const body: Json = {
    context: JSON.parse(JSON.stringify(spec.context)),
    videoId,
    ...(spec.extraBody ? JSON.parse(JSON.stringify(spec.extraBody)) : {}),
  };
  const headers: Record<string, string> = {
    'User-Agent': spec.userAgent,
    ...spec.headers,
  };
  if (spec.needsVisitor) {
    if (body.playbackContext?.contentPlaybackContext) {
      body.playbackContext.contentPlaybackContext.signatureTimestamp =
        signatureTimestamp;
    }
    if (visitorData && name === 'VISIONOS') {
      body.context.client.visitorData = visitorData;
      headers['X-Goog-Visitor-Id'] = visitorData;
    }
  }
  const json = await postJson('player?prettyPrint=false', body, headers);
  return parsePlayerResponse(json, videoId, name, maxHeight);
}

export function parsePlayerResponse(
  json: Json,
  videoId: string,
  client: string,
  maxHeight = MAX_DASH_HEIGHT,
): StreamInfo {
  const ps = json.playabilityStatus;
  if (ps?.status !== 'OK') {
    const r = ps?.errorScreen?.playerErrorMessageRenderer;
    const reason =
      runsText(r?.subreason) ??
      runsText(r?.reason) ??
      ps?.reason ??
      (ps?.status === 'LIVE_STREAM_OFFLINE'
        ? 'プレミア公開またはライブ配信前です'
        : `動画を再生できません (ステータス: ${ps?.status ?? '不明'})`);
    throw new Error(reason);
  }
  const sd = json.streamingData;
  if (!sd) {
    throw new Error('No streamingData');
  }

  const sources: PlaybackSource[] = [];
  const dash = pickDash(sd.adaptiveFormats ?? []);
  const video = dash && chooseQuality(dash.videos, maxHeight);
  if (dash && video) {
    sources.push(dashSource(video, dash.audio, client));
  }
  if (typeof sd.hlsManifestUrl === 'string') {
    sources.push({
      url: sd.hlsManifestUrl,
      kind: 'hls',
      label: 'HLS (自動画質)',
      client,
    });
  }
  // 映像+音声が 1 本になった MP4 (360p / 720p)。高画質順
  const muxed: Json[] = (sd.formats ?? []).filter(
    (f: Json) =>
      typeof f.url === 'string' && String(f.mimeType ?? '').includes('mp4'),
  );
  muxed.sort((a, b) => (b.height ?? 0) - (a.height ?? 0));
  for (const f of muxed) {
    sources.push({
      url: f.url,
      kind: 'mp4',
      label: `MP4 ${f.qualityLabel ?? ''}`.trim(),
      client,
    });
  }
  if (sources.length === 0) {
    throw new Error('再生できるストリームがありません');
  }

  const vd = json.videoDetails ?? {};
  return {
    videoId,
    title: vd.title ?? '',
    uploaderName: vd.author ?? '',
    channelId: typeof vd.channelId === 'string' ? vd.channelId : undefined,
    durationSeconds: Number(vd.lengthSeconds) || 0,
    sources,
    dashVideos: dash?.videos ?? [],
    dashAudio: dash?.audio,
    captions: parseCaptionTracks(json),
  };
}

export function dashSource(
  video: DashTrack,
  audio: DashTrack,
  client: string,
): PlaybackSource {
  return {kind: 'dash', video, audio, label: `DASH ${video.height}p`, client};
}

/** maxHeight 以下で最も高画質な映像。無ければ最も低い画質 */
export function chooseQuality(
  videos: DashTrack[],
  maxHeight: number,
): DashTrack | undefined {
  return videos.find((v) => v.height <= maxHeight) ?? videos[videos.length - 1];
}

function parseCaptionTracks(json: Json): CaptionTrack[] {
  const tracks: Json[] =
    json.captions?.playerCaptionsTracklistRenderer?.captionTracks ?? [];
  return tracks
    .filter((t) => typeof t.baseUrl === 'string')
    .map((t) => ({
      baseUrl: t.baseUrl,
      languageCode: String(t.languageCode ?? ''),
      name: runsText(t.name) ?? String(t.languageCode ?? ''),
      isAutoGenerated: t.kind === 'asr',
    }));
}

/** 映像の上限。Fire TV Stick はメモリが少ないため 1080p まで */
export const MAX_DASH_HEIGHT = 1080;

function toTrack(f: Json): DashTrack | null {
  const initEnd = Number(f.initRange?.end);
  const indexStart = Number(f.indexRange?.start);
  const indexEnd = Number(f.indexRange?.end);
  if (
    typeof f.url !== 'string' ||
    !Number.isFinite(initEnd) ||
    !Number.isFinite(indexEnd)
  ) {
    return null;
  }
  return {
    url: f.url,
    mimeType: String(f.mimeType ?? ''),
    initEnd,
    indexStart,
    indexEnd,
    height: Number(f.height) || 0,
    bitrate: Number(f.bitrate) || 0,
  };
}

/**
 * MSE 用に H.264 (avc1) 映像 + AAC (mp4a) 音声の組を選ぶ。
 * VP9 / AV1 / Opus は端末の対応が未確認のため使わない
 */
export function pickDash(
  formats: Json[],
): {videos: DashTrack[]; audio: DashTrack} | null {
  const tracks = formats.map(toTrack).filter((t): t is DashTrack => t !== null);
  const videos = tracks
    .filter(
      (t) => t.mimeType.startsWith('video/mp4') && t.mimeType.includes('avc1'),
    )
    .filter((t) => t.height > 0 && t.height <= MAX_DASH_HEIGHT)
    .sort((a, b) => b.height - a.height || b.bitrate - a.bitrate)
    // 同じ画質が複数あればビットレートの高い方だけ残す
    .filter((t, i, list) => i === 0 || list[i - 1].height !== t.height);
  const audio = tracks
    .filter(
      (t) =>
        t.mimeType.startsWith('audio/mp4') && t.mimeType.includes('mp4a.40.2'),
    )
    .sort((a, b) => b.bitrate - a.bitrate)[0];
  return videos.length > 0 && audio ? {videos, audio} : null;
}

const PREFERRED_REASON =
  /非公開|プレミア|配信前|ご覧いただけません|削除|ログイン/;

/**
 * 再生情報の取得。race クライアントを並列に叩いて優先順に採用し、
 * 全滅したら fallback クライアントを順に試す。
 * @param skip 既に試して再生に失敗したクライアント (プレーヤー側のエラー時に別経路を取り直す)
 */
export async function extractStreamInfo(
  videoId: string,
  skip: string[] = [],
  maxHeight = MAX_DASH_HEIGHT,
): Promise<StreamInfo> {
  const config = getConfig();
  const reasons: string[] = [];
  const race = config.playerRaceClients.filter((c) => !skip.includes(c));
  const pending = race.map((c) =>
    fetchPlayer(c, videoId, maxHeight).catch((e: Error) => e),
  );
  for (const p of pending) {
    const r = await p;
    if (!(r instanceof Error)) {
      return r;
    }
    reasons.push(r.message);
  }
  for (const c of config.playerFallbackClients) {
    if (skip.includes(c) || race.includes(c)) {
      continue;
    }
    try {
      return await fetchPlayer(c, videoId, maxHeight);
    } catch (e) {
      reasons.push((e as Error).message);
    }
  }
  const best =
    reasons.find((r) => PREFERRED_REASON.test(r)) ??
    reasons.find((r) => !/HTTP|failed|streamingData|timeout/i.test(r)) ??
    'この動画は再生できません';
  throw new Error(best);
}

// ---------------------------------------------------------------- 字幕

/**
 * 字幕 (timedtext の json3 形式) を取得してキューにする。
 * @param translateTo 指定するとその言語へ自動翻訳した字幕を取る (例: "ja")
 */
export async function fetchCaptions(
  track: CaptionTrack,
  translateTo?: string,
): Promise<Cue[]> {
  const url =
    `${track.baseUrl}&fmt=json3` +
    (translateTo ? `&tlang=${encodeURIComponent(translateTo)}` : '');
  const res = await fetchWithTimeout(url, {}, TIMEOUT_MS);
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return parseJson3(await res.json());
}

export function parseJson3(json: Json): Cue[] {
  const events: Json[] = Array.isArray(json?.events) ? json.events : [];
  const cues: Cue[] = [];
  for (const e of events) {
    const segs: Json[] = Array.isArray(e.segs) ? e.segs : [];
    const text = segs
      .map((s) => s?.utf8 ?? '')
      .join('')
      .trim();
    const start = Number(e.tStartMs);
    if (!text || !Number.isFinite(start)) {
      continue;
    }
    const end = start + (Number(e.dDurationMs) || 0);
    cues.push({start: start / 1000, end: end / 1000, text});
  }
  return cues;
}

/** 字幕の既定 (設定) から使うトラックを決める。日本語が無ければ最初のトラックを日本語に翻訳する */
export function chooseCaption(
  tracks: CaptionTrack[],
  mode: 'off' | 'ja' | 'default',
): {track: CaptionTrack; translateTo?: string} | null {
  if (mode === 'off' || tracks.length === 0) {
    return null;
  }
  if (mode === 'ja') {
    const ja =
      tracks.find((t) => t.languageCode === 'ja' && !t.isAutoGenerated) ??
      tracks.find((t) => t.languageCode === 'ja');
    return ja ? {track: ja} : {track: tracks[0], translateTo: 'ja'};
  }
  return {track: tracks.find((t) => !t.isAutoGenerated) ?? tracks[0]};
}
