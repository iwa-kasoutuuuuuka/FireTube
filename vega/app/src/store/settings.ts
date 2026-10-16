import {persisted} from './persisted';

/** 字幕の既定: off / 日本語 (無ければ自動翻訳) / 動画の既定トラック */
export type SubtitleMode = 'off' | 'ja' | 'default';

export interface Settings {
  /** DASH 映像の上限 (縦の画素数) */
  maxQuality: number;
  subtitles: SubtitleMode;
  sponsorBlock: boolean;
  saveHistory: boolean;
  resume: boolean;
  /** 再生が終わったら関連動画の先頭を続けて再生する */
  autoplay: boolean;
  playbackRate: number;
}

export const QUALITY_OPTIONS = [1080, 720, 480, 360];
export const SPEED_OPTIONS = [0.75, 1, 1.25, 1.5, 2];

export const DEFAULT_SETTINGS: Settings = {
  maxQuality: 1080,
  subtitles: 'off',
  sponsorBlock: true,
  saveHistory: true,
  resume: true,
  autoplay: true,
  playbackRate: 1,
};

export const settings = persisted<Settings>(
  'settings',
  DEFAULT_SETTINGS,
  (raw) => {
    const o = (raw ?? {}) as Partial<Settings>;
    const bool = (v: unknown, d: boolean) => (typeof v === 'boolean' ? v : d);
    return {
      maxQuality: QUALITY_OPTIONS.includes(Number(o.maxQuality))
        ? Number(o.maxQuality)
        : DEFAULT_SETTINGS.maxQuality,
      subtitles:
        o.subtitles === 'ja' || o.subtitles === 'default' ? o.subtitles : 'off',
      sponsorBlock: bool(o.sponsorBlock, DEFAULT_SETTINGS.sponsorBlock),
      saveHistory: bool(o.saveHistory, DEFAULT_SETTINGS.saveHistory),
      resume: bool(o.resume, DEFAULT_SETTINGS.resume),
      autoplay: bool(o.autoplay, DEFAULT_SETTINGS.autoplay),
      playbackRate: SPEED_OPTIONS.includes(Number(o.playbackRate))
        ? Number(o.playbackRate)
        : DEFAULT_SETTINGS.playbackRate,
    };
  },
);

export function updateSettings(patch: Partial<Settings>) {
  settings.set((s) => ({...s, ...patch}));
}
