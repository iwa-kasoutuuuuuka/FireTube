export interface VideoItem {
  id: string;
  title: string;
  uploaderName: string;
  thumbnailUrl: string;
  durationSeconds: number;
}

/** 再生候補。Vega Media Player の URL モードへ順に渡し、失敗したら次を試す */
export interface PlaybackSource {
  url: string;
  kind: 'hls' | 'mp4';
  label: string;
  client: string;
}

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
