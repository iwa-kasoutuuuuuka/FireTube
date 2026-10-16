/**
 * 分離 DASH (映像 fMP4 + 音声 fMP4) を MSE で再生する最小実装。
 *
 * googlevideo.com は終端なし・範囲超過の Range に 403 を返すことがあるため (Android 版 GoogleVideoDataSource と同じ対策)、
 * sidx で得たセグメント境界を 1MB 以下の有界 Range に分けて取得する。
 */
import {DashTrack} from '../api/types';
import {parseSidx, Segment} from './sidx';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const MAX_CHUNK = 1024 * 1024;
const BUFFER_AHEAD = 30;
const BUFFER_BEHIND = 20;
const IDLE_MS = 500;

/** w3cmedia の型定義に依存しないよう、使う部分だけ構造的に定義する */
interface TimeRangesLike {
  length: number;
  start(i: number): number;
  end(i: number): number;
}
interface SourceBufferLike {
  updating: boolean;
  buffered: TimeRangesLike;
  appendBuffer(data: ArrayBuffer): void;
  remove(start: number, end: number): void;
  addEventListener(type: string, cb: () => void): void;
  removeEventListener?(type: string, cb: () => void): void;
}
export interface MediaSourceLike {
  readyState: string;
  duration: number;
  addSourceBuffer(type: string): SourceBufferLike;
  endOfStream(): void;
  addEventListener(type: string, cb: () => void): void;
}
export interface MediaElementLike {
  currentTime: number;
  srcObject: unknown;
  addEventListener(type: string, cb: () => void): void;
}

/** RN の fetch は arrayBuffer() が使えない版があるため XHR で取る */
export function fetchRange(url: string, start: number, end: number): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', url);
    xhr.responseType = 'arraybuffer';
    xhr.setRequestHeader('Range', `bytes=${start}-${end}`);
    xhr.setRequestHeader('User-Agent', UA);
    xhr.timeout = 20000;
    xhr.onload = () => {
      if (xhr.status === 200 || xhr.status === 206) resolve(xhr.response as ArrayBuffer);
      else reject(new Error(`HTTP ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('network error'));
    xhr.ontimeout = () => reject(new Error('timeout'));
    xhr.send();
  });
}

async function fetchBytes(url: string, start: number, end: number): Promise<ArrayBuffer> {
  if (end - start + 1 <= MAX_CHUNK) return retry(() => fetchRange(url, start, end));
  const parts: ArrayBuffer[] = [];
  for (let s = start; s <= end; s += MAX_CHUNK) {
    const e = Math.min(end, s + MAX_CHUNK - 1);
    parts.push(await retry(() => fetchRange(url, s, e)));
  }
  const total = parts.reduce((n, p) => n + p.byteLength, 0);
  const out = new Uint8Array(total);
  let pos = 0;
  for (const p of parts) {
    out.set(new Uint8Array(p), pos);
    pos += p.byteLength;
  }
  return out.buffer;
}

async function retry<T>(fn: () => Promise<T>, times = 3): Promise<T> {
  let last: unknown;
  for (let i = 0; i < times; i++) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      // 403 は URL 失効なので再試行しても無駄
      if (String((e as Error).message).includes('403')) break;
      await sleep(500 * (i + 1));
    }
  }
  throw last;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function bufferedEndAt(ranges: TimeRangesLike, t: number): number {
  for (let i = 0; i < ranges.length; i++) {
    if (ranges.start(i) <= t + 0.3 && t <= ranges.end(i)) return ranges.end(i);
  }
  return t;
}

class TrackPump {
  segments: Segment[] = [];
  next = 0;
  done = false;
  private sb!: SourceBufferLike;

  constructor(private track: DashTrack, private session: DashSession) {}

  async init(ms: MediaSourceLike): Promise<void> {
    this.sb = ms.addSourceBuffer(this.track.mimeType);
    const head = await fetchBytes(this.track.url, 0, this.track.indexEnd);
    const bytes = new Uint8Array(head);
    this.segments = parseSidx(bytes.subarray(this.track.indexStart), this.track.indexStart);
    if (this.segments.length === 0) throw new Error('セグメントがありません');
    await this.append(head.slice(0, this.track.initEnd + 1));
  }

  /** 現在位置を含むセグメントから取り直す (バッファ済みならそのまま) */
  seek(t: number) {
    const end = bufferedEndAt(this.sb.buffered, t);
    const target = end > t ? end : t;
    let i = this.segments.findIndex((s) => s.time + s.duration > target + 0.01);
    if (i < 0) i = this.segments.length;
    this.next = i;
    this.done = i >= this.segments.length;
  }

  /** 1 回分の作業をする。何もしなかったら false */
  async step(t: number): Promise<boolean> {
    if (this.done || this.sb.updating) return false;
    const ahead = bufferedEndAt(this.sb.buffered, t) - t;
    if (ahead >= BUFFER_AHEAD) {
      await this.evict(t);
      return false;
    }
    const seg = this.segments[this.next];
    const gen = this.session.generation;
    const data = await fetchBytes(this.track.url, seg.start, seg.end);
    if (gen !== this.session.generation || this.session.destroyed) return true; // シークで不要になった
    await this.append(data);
    if (gen !== this.session.generation) return true; // 追加中にシークされた (seek() が next を決め直している)
    this.next += 1;
    if (this.next >= this.segments.length) this.done = true;
    return true;
  }

  private async evict(t: number) {
    const b = this.sb.buffered;
    if (b.length > 0 && b.start(0) < t - BUFFER_BEHIND - 10) {
      await this.waitUpdate(() => this.sb.remove(0, t - BUFFER_BEHIND));
    }
  }

  private append(data: ArrayBuffer) {
    return this.waitUpdate(() => this.sb.appendBuffer(data));
  }

  private waitUpdate(action: () => void): Promise<void> {
    return new Promise((resolve, reject) => {
      const onEnd = () => {
        cleanup();
        resolve();
      };
      const onError = () => {
        cleanup();
        reject(new Error('SourceBuffer error'));
      };
      const cleanup = () => {
        this.sb.removeEventListener?.('updateend', onEnd);
        this.sb.removeEventListener?.('error', onError);
      };
      this.sb.addEventListener('updateend', onEnd);
      this.sb.addEventListener('error', onError);
      try {
        action();
      } catch (e) {
        cleanup();
        reject(e);
      }
    });
  }
}

export class DashSession {
  generation = 0;
  destroyed = false;
  private pumps: TrackPump[] = [];

  constructor(
    private element: MediaElementLike,
    private createMediaSource: () => MediaSourceLike,
    private video: DashTrack,
    private audio: DashTrack,
    private durationSeconds: number,
    private onFatal: (message: string) => void,
  ) {}

  start() {
    const ms = this.createMediaSource();
    ms.addEventListener('sourceopen', () => {
      this.run(ms).catch((e: Error) => {
        if (!this.destroyed) this.onFatal(e.message);
      });
    });
    this.element.addEventListener('seeking', () => {
      if (this.destroyed) return;
      this.generation += 1;
      for (const p of this.pumps) p.seek(this.element.currentTime);
    });
    this.element.srcObject = ms;
  }

  destroy() {
    this.destroyed = true;
  }

  private async run(ms: MediaSourceLike) {
    if (this.durationSeconds > 0) ms.duration = this.durationSeconds;
    this.pumps = [new TrackPump(this.video, this), new TrackPump(this.audio, this)];
    // 映像と音声の SourceBuffer は両方を作ってから初期化セグメントを積む
    await Promise.all(this.pumps.map((p) => p.init(ms)));
    let ended = false;
    while (!this.destroyed) {
      const t = this.element.currentTime;
      // 映像と音声が離れすぎないよう、バッファの少ない方から 1 セグメントずつ進める
      const results = await Promise.all(this.pumps.map((p) => p.step(t)));
      if (this.pumps.every((p) => p.done)) {
        if (!ended && ms.readyState === 'open') {
          ended = true;
          try {
            ms.endOfStream();
          } catch {
            ended = false;
          }
        }
      } else {
        ended = false;
      }
      if (!results.some(Boolean)) await sleep(IDLE_MS);
    }
  }
}
