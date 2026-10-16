export interface VideoItem {
  id: string;
  title: string;
  uploaderName: string;
  thumbnailUrl: string;
  durationSeconds: number;
}

/** 分離 DASH の 1 トラック (fMP4)。initRange / indexRange は InnerTube の adaptiveFormats から */
export interface DashTrack {
  url: string;
  mimeType: string;
  initEnd: number;
  indexStart: number;
  indexEnd: number;
  height: number;
  bitrate: number;
}

/**
 * 再生候補。先頭から順に試し、失敗したら次へ。
 * dash = MSE で映像+音声を自前で積む / hls・mp4 = Vega Media Player の URL モード
 */
export type PlaybackSource =
  | {kind: 'dash'; video: DashTrack; audio: DashTrack; label: string; client: string}
  | {kind: 'hls' | 'mp4'; url: string; label: string; client: string};

export interface StreamInfo {
  videoId: string;
  title: string;
  uploaderName: string;
  durationSeconds: number;
  sources: PlaybackSource[];
}

export interface SponsorSegment {
  start: number;
  end: number;
  category: string;
}
