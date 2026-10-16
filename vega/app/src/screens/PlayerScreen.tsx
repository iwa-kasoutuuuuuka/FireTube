import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, StyleSheet, Text, View} from 'react-native';
import {useTVEventHandler} from '@amazon-devices/react-native-kepler';
import {KeplerVideoSurfaceView, VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import {extractStreamInfo} from '../api/innertube';
import {CATEGORY_LABELS, getSponsorSegments} from '../api/sponsorblock';
import {PlaybackSource, SponsorSegment, VideoItem} from '../api/types';
import {colors, formatDuration, s} from '../theme';

interface Props {
  video: VideoItem;
  onEnded: () => void;
}

const OVERLAY_MS = 4000;
const SEEK_SHORT = 10;
const SEEK_LONG = 30;

/**
 * 再生画面。Vega Media Player の URL モードに候補 (HLS → MP4) を順に渡し、
 * エラーなら次の候補、候補が尽きたら別の InnerTube クライアントで取り直す。
 *
 * リモコン: 決定/再生 = 一時停止切替、左右 = 10 秒、早送り/巻き戻し = 30 秒、戻る = 一覧へ
 */
export const PlayerScreen = ({video, onEnded}: Props) => {
  const player = useRef<VideoPlayer | null>(null);
  const sources = useRef<PlaybackSource[]>([]);
  const sourceIndex = useRef(0);
  const triedClients = useRef<string[]>([]);
  const segments = useRef<SponsorSegment[]>([]);
  const ready = useRef(false);
  const hideTimer = useRef<ReturnType<typeof setTimeout>>();

  const [status, setStatus] = useState('再生情報を取得しています…');
  const [error, setError] = useState<string>();
  const [paused, setPaused] = useState(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(video.durationSeconds);
  const [overlay, setOverlay] = useState(true);
  const [toast, setToast] = useState<string>();
  const [sourceLabel, setSourceLabel] = useState('');

  const showOverlay = useCallback(() => {
    setOverlay(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setOverlay(false), OVERLAY_MS);
  }, []);

  const playSource = useCallback((src: PlaybackSource) => {
    const p = player.current;
    if (!p) return;
    setSourceLabel(`${src.label} / ${src.client}`);
    setStatus('読み込み中…');
    p.autoplay = true;
    p.src = src.url;
    p.play();
  }, []);

  /** 現在の候補を捨てて次へ。尽きたら別クライアントで取り直す */
  const nextSource = useCallback(
    async (reason?: string) => {
      sourceIndex.current += 1;
      if (sourceIndex.current < sources.current.length) {
        playSource(sources.current[sourceIndex.current]);
        return;
      }
      try {
        setStatus('別の経路で取得し直しています…');
        const info = await extractStreamInfo(video.id, triedClients.current);
        triedClients.current.push(info.sources[0].client);
        sources.current = info.sources;
        sourceIndex.current = 0;
        if (info.durationSeconds) setDuration(info.durationSeconds);
        playSource(info.sources[0]);
      } catch (e) {
        setError(reason ? `再生できませんでした (${reason})` : (e as Error).message);
      }
    },
    [playSource, video.id],
  );

  const start = useCallback(async () => {
    if (!ready.current) return;
    getSponsorSegments(video.id).then((list) => (segments.current = list));
    try {
      const info = await extractStreamInfo(video.id);
      triedClients.current = [info.sources[0].client];
      sources.current = info.sources;
      sourceIndex.current = 0;
      if (info.durationSeconds) setDuration(info.durationSeconds);
      playSource(info.sources[0]);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [playSource, video.id]);

  const attachListeners = useCallback(
    (p: VideoPlayer) => {
      const el = p as unknown as {
        addEventListener: (type: string, cb: (ev?: unknown) => void) => void;
        error?: {code?: number; message?: string} | null;
      };
      el.addEventListener('playing', () => {
        setStatus('');
        setPaused(false);
        showOverlay();
      });
      el.addEventListener('pause', () => setPaused(true));
      el.addEventListener('waiting', () => setStatus('バッファ中…'));
      el.addEventListener('durationchange', () => {
        const d = p.duration;
        if (Number.isFinite(d) && d > 0) setDuration(d);
      });
      el.addEventListener('timeupdate', () => {
        const t = p.currentTime;
        setPosition(t);
        const seg = segments.current.find((sg) => t >= sg.start && t < sg.end - 0.5);
        if (seg) {
          p.currentTime = seg.end;
          setToast(`${CATEGORY_LABELS[seg.category] ?? seg.category} をスキップしました`);
          setTimeout(() => setToast(undefined), 3000);
        }
      });
      el.addEventListener('ended', () => onEnded());
      el.addEventListener('error', () => {
        const err = el.error;
        nextSource(err ? `code ${err.code ?? '?'} ${err.message ?? ''}`.trim() : undefined);
      });
    },
    [nextSource, onEnded, showOverlay],
  );

  const onSurfaceViewCreated = async (surfaceHandle: string) => {
    const p = new VideoPlayer();
    player.current = p;
    await p.initialize();
    p.setSurfaceHandle(surfaceHandle);
    attachListeners(p);
    ready.current = true;
    start();
  };

  const onSurfaceViewDestroyed = async (surfaceHandle: string) => {
    const p = player.current;
    player.current = null;
    ready.current = false;
    if (!p) return;
    p.clearSurfaceHandle(surfaceHandle);
    await p.deinitialize();
  };

  useEffect(
    () => () => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
      player.current?.pause();
    },
    [],
  );

  const seekBy = (delta: number) => {
    const p = player.current;
    if (!p) return;
    const max = duration > 0 ? duration - 1 : Number.MAX_SAFE_INTEGER;
    p.currentTime = Math.min(max, Math.max(0, p.currentTime + delta));
    showOverlay();
  };

  const togglePause = () => {
    const p = player.current;
    if (!p) return;
    if (p.paused) p.play();
    else p.pause();
    showOverlay();
  };

  useTVEventHandler((evt: {eventType?: string; eventKeyAction?: number}) => {
    if (evt.eventKeyAction !== 0) return; // 押下時のみ (1 = 離した時)
    switch (evt.eventType) {
      case 'select':
      case 'playpause':
      case 'play':
      case 'pause':
        togglePause();
        break;
      case 'left':
        seekBy(-SEEK_SHORT);
        break;
      case 'right':
        seekBy(SEEK_SHORT);
        break;
      case 'skip_backward':
      case 'rewind':
        seekBy(-SEEK_LONG);
        break;
      case 'skip_forward':
      case 'fastForward':
        seekBy(SEEK_LONG);
        break;
      default:
        showOverlay();
    }
  });

  const progress = duration > 0 ? Math.min(1, position / duration) : 0;

  return (
    <View style={styles.root}>
      <KeplerVideoSurfaceView
        style={styles.surface}
        onSurfaceViewCreated={onSurfaceViewCreated}
        onSurfaceViewDestroyed={onSurfaceViewDestroyed}
      />
      {(status !== '' || error) && (
        <View style={styles.center} pointerEvents="none">
          {!error && <ActivityIndicator size="large" color={colors.accent} />}
          <Text style={error ? styles.error : styles.status}>{error ?? status}</Text>
          {error && <Text style={styles.hint}>戻るボタンで一覧に戻ります</Text>}
        </View>
      )}
      {toast && <Text style={styles.toast}>{toast}</Text>}
      {(overlay || paused) && !error && (
        <View style={styles.overlay} pointerEvents="none">
          <Text style={styles.title} numberOfLines={2}>
            {video.title}
          </Text>
          <Text style={styles.uploader}>
            {video.uploaderName}
            {sourceLabel ? `  ·  ${sourceLabel}` : ''}
          </Text>
          <View style={styles.bar}>
            <View style={[styles.barFill, {width: `${progress * 100}%`}]} />
          </View>
          <Text style={styles.time}>
            {paused ? '一時停止中  ' : ''}
            {formatDuration(position)} / {formatDuration(duration)}
          </Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#000'},
  surface: {flex: 1},
  center: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
  },
  status: {color: colors.text, fontSize: s(30), marginTop: s(24)},
  error: {color: colors.error, fontSize: s(32), marginHorizontal: s(120), textAlign: 'center'},
  hint: {color: colors.textDim, fontSize: s(24), marginTop: s(16)},
  toast: {
    position: 'absolute',
    top: s(48),
    right: s(48),
    paddingHorizontal: s(24),
    paddingVertical: s(12),
    borderRadius: s(8),
    backgroundColor: 'rgba(0,0,0,0.75)',
    color: colors.text,
    fontSize: s(24),
  },
  overlay: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: s(80),
    paddingTop: s(40),
    paddingBottom: s(48),
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  title: {color: colors.text, fontSize: s(36), fontWeight: 'bold'},
  uploader: {color: colors.textDim, fontSize: s(24), marginTop: s(8)},
  bar: {
    height: s(8),
    borderRadius: s(4),
    backgroundColor: 'rgba(255,255,255,0.3)',
    marginTop: s(24),
    overflow: 'hidden',
  },
  barFill: {height: '100%', backgroundColor: colors.accent},
  time: {color: colors.text, fontSize: s(24), marginTop: s(12)},
});
