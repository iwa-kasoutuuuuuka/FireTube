import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  BackHandler,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {useTVEventHandler} from '@amazon-devices/react-native-kepler';
import {
  KeplerVideoSurfaceView,
  MediaSource,
  VideoPlayer,
} from '@amazon-devices/react-native-w3cmedia';
import {
  chooseCaption,
  dashSource,
  extractStreamInfo,
  fetchCaptions,
} from '../api/innertube';
import {CATEGORY_LABELS, getSponsorSegments} from '../api/sponsorblock';
import {
  Cue,
  PlaybackSource,
  SponsorSegment,
  StreamInfo,
  VideoItem,
} from '../api/types';
import {MenuItem, PlayerMenu} from '../components/PlayerMenu';
import {CaptionOption, captionOptions, cueAt} from '../player/captions';
import {DashSession, MediaElementLike, MediaSourceLike} from '../player/dash';
import {
  addHistory,
  Channel,
  resumePosition,
  savePosition,
  subscriptions,
  toggleSubscription,
  toggleWatchLater,
  useStore,
  watchLater,
} from '../store/library';
import {settings} from '../store/settings';
import {colors, formatDuration, s} from '../theme';

interface Props {
  video: VideoItem;
  onEnded: () => void;
  onOpenChannel: (channel: Channel) => void;
}

const OVERLAY_MS = 4000;
const TOAST_MS = 3000;
const SEEK_SHORT = 10;
const SEEK_LONG = 30;
const SWITCH_GRACE_MS = 800;
/** この時間内に再生が始まらない候補は失敗とみなす (エラーが届かず止まったままになるのを防ぐ) */
const START_TIMEOUT_MS = 20000;
const SAVE_INTERVAL_MS = 15000;

/** play() の Promise 拒否 (データ到着前など) は error / playing イベント側で扱う */
function playSafely(p: VideoPlayer) {
  Promise.resolve(p.play()).catch(() => {});
}

/**
 * 再生画面。候補を順に試す: 分離 DASH (MSE で自前再生) → HLS / MP4 (Vega Media Player の URL モード)。
 * エラーなら次の候補、候補が尽きたら別の InnerTube クライアントで取り直す。
 *
 * リモコン: 決定/再生 = 一時停止切替、左右 = 10 秒、早送り/巻き戻し = 30 秒、
 * 下 (またはメニュー) = メニュー (後で見る・チャンネル登録・字幕・画質)、戻る = 一覧へ
 */
export const PlayerScreen = ({video, onEnded, onOpenChannel}: Props) => {
  const player = useRef<VideoPlayer | null>(null);
  const info = useRef<StreamInfo | null>(null);
  const sources = useRef<PlaybackSource[]>([]);
  const sourceIndex = useRef(0);
  const triedClients = useRef<string[]>([]);
  const segments = useRef<SponsorSegment[]>([]);
  const cues = useRef<Cue[]>([]);
  const ready = useRef(false);
  const dash = useRef<DashSession | null>(null);
  const streamDuration = useRef(video.durationSeconds);
  const switchedAt = useRef(0);
  const pendingSeek = useRef(0);
  const lastSaved = useRef(0);
  const recorded = useRef(false);
  const startTimer = useRef<ReturnType<typeof setTimeout>>();
  const hideTimer = useRef<ReturnType<typeof setTimeout>>();
  const toastTimer = useRef<ReturnType<typeof setTimeout>>();
  // playSource と nextSource は互いに呼び合うため ref 経由で参照する
  const nextSourceRef = useRef<(failed: number, reason?: string) => void>(
    () => {},
  );

  const [status, setStatus] = useState('再生情報を取得しています…');
  const [error, setError] = useState<string>();
  const [paused, setPaused] = useState(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(video.durationSeconds);
  const [overlay, setOverlay] = useState(true);
  const [toast, setToast] = useState<string>();
  const [sourceLabel, setSourceLabel] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const [captionList, setCaptionList] = useState<CaptionOption[]>([]);
  const [captionIndex, setCaptionIndex] = useState(0);
  const [subtitle, setSubtitle] = useState('');
  const [channel, setChannel] = useState<Channel | null>(
    video.channelId ? {id: video.channelId, name: video.uploaderName} : null,
  );
  const [currentSource, setCurrentSource] = useState<PlaybackSource>();

  const inLater = useStore(watchLater).some((v) => v.id === video.id);
  const subscribed = useStore(subscriptions).some((c) => c.id === channel?.id);

  const showToast = useCallback((text: string) => {
    setToast(text);
    if (toastTimer.current) {
      clearTimeout(toastTimer.current);
    }
    toastTimer.current = setTimeout(() => setToast(undefined), TOAST_MS);
  }, []);

  const showOverlay = useCallback(() => {
    setOverlay(true);
    if (hideTimer.current) {
      clearTimeout(hideTimer.current);
    }
    hideTimer.current = setTimeout(() => setOverlay(false), OVERLAY_MS);
  }, []);

  const savePositionNow = useCallback(() => {
    const p = player.current;
    if (!p || !settings.get().saveHistory || !recorded.current) {
      return;
    }
    savePosition(video.id, p.currentTime, streamDuration.current);
  }, [video.id]);

  /** @param resumeAt 再生開始後にこの位置へ移る (画質切替・続きから再生) */
  const playSource = useCallback((src: PlaybackSource, resumeAt = 0) => {
    const p = player.current;
    if (!p) {
      return;
    }
    dash.current?.destroy();
    dash.current = null;
    switchedAt.current = Date.now();
    pendingSeek.current = resumeAt;
    if (startTimer.current) {
      clearTimeout(startTimer.current);
    }
    const index = sourceIndex.current;
    startTimer.current = setTimeout(
      () => nextSourceRef.current(index, '再生が始まりませんでした'),
      START_TIMEOUT_MS,
    );
    setCurrentSource(src);
    setSourceLabel(`${src.label} / ${src.client}`);
    setStatus('読み込み中…');
    p.autoplay = true;
    if (src.kind === 'dash') {
      const session = new DashSession(
        p as unknown as MediaElementLike,
        () => new MediaSource() as unknown as MediaSourceLike,
        src.video,
        src.audio,
        streamDuration.current,
        (message) => nextSourceRef.current(index, message),
      );
      dash.current = session;
      session.start();
    } else {
      p.src = src.url;
    }
    playSafely(p);
  }, []);

  const applyInfo = useCallback((next: StreamInfo) => {
    info.current = next;
    sources.current = next.sources;
    sourceIndex.current = 0;
    if (next.durationSeconds) {
      streamDuration.current = next.durationSeconds;
      setDuration(next.durationSeconds);
    }
    if (next.channelId) {
      setChannel({id: next.channelId, name: next.uploaderName});
    }
  }, []);

  const selectCaption = useCallback(
    async (options: CaptionOption[], index: number) => {
      setCaptionIndex(index);
      cues.current = [];
      setSubtitle('');
      const opt = options[index];
      if (!opt?.track) {
        return;
      }
      try {
        cues.current = await fetchCaptions(opt.track, opt.translateTo);
      } catch (e) {
        showToast(`字幕を読み込めませんでした (${(e as Error).message})`);
      }
    },
    [showToast],
  );

  /**
   * 候補 failed を捨てて次へ。尽きたら別クライアントで取り直す。
   * MSE の失敗とプレーヤーの error イベントが重なっても 1 回だけ進むよう、失敗した候補の番号を受け取る
   */
  const nextSource = useCallback(
    async (failed: number, reason?: string) => {
      if (failed !== sourceIndex.current) {
        return;
      }
      const resumeAt = player.current?.currentTime ?? 0;
      sourceIndex.current += 1;
      if (sourceIndex.current < sources.current.length) {
        playSource(sources.current[sourceIndex.current], resumeAt);
        return;
      }
      try {
        setStatus('別の経路で取得し直しています…');
        const next = await extractStreamInfo(
          video.id,
          triedClients.current,
          settings.get().maxQuality,
        );
        triedClients.current.push(next.sources[0].client);
        applyInfo(next);
        playSource(next.sources[0], resumeAt);
      } catch (e) {
        if (startTimer.current) {
          clearTimeout(startTimer.current);
        }
        dash.current?.destroy();
        setError(
          reason ? `再生できませんでした (${reason})` : (e as Error).message,
        );
      }
    },
    [applyInfo, playSource, video.id],
  );
  nextSourceRef.current = nextSource;

  const start = useCallback(async () => {
    if (!ready.current) {
      return;
    }
    const st = settings.get();
    if (st.sponsorBlock) {
      getSponsorSegments(video.id).then((list) => (segments.current = list));
    }
    try {
      const next = await extractStreamInfo(video.id, [], st.maxQuality);
      triedClients.current = [next.sources[0].client];
      applyInfo(next);

      const options = captionOptions(next.captions);
      setCaptionList(options);
      const choice = chooseCaption(next.captions, st.subtitles);
      if (choice) {
        const idx = options.findIndex(
          (o) =>
            o.track?.baseUrl === choice.track.baseUrl &&
            o.translateTo === choice.translateTo,
        );
        selectCaption(options, Math.max(0, idx));
      }

      const saved = st.resume ? resumePosition(video.id) : 0;
      playSource(next.sources[0], saved);
      if (saved > 0) {
        showToast(`前回の続き (${formatDuration(saved)}) から再生します`);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [applyInfo, playSource, selectCaption, showToast, video.id]);

  const attachListeners = useCallback(
    (p: VideoPlayer) => {
      const el = p as unknown as {
        addEventListener: (type: string, cb: (ev?: unknown) => void) => void;
        error?: {code?: number; message?: string} | null;
      };
      const applyPendingSeek = () => {
        if (pendingSeek.current > 0) {
          p.currentTime = pendingSeek.current;
          pendingSeek.current = 0;
        }
      };
      el.addEventListener('loadedmetadata', applyPendingSeek);
      el.addEventListener('playing', () => {
        if (startTimer.current) {
          clearTimeout(startTimer.current);
          startTimer.current = undefined;
        }
        applyPendingSeek();
        if (!recorded.current) {
          recorded.current = true;
          if (settings.get().saveHistory) {
            addHistory({
              ...video,
              channelId: info.current?.channelId ?? video.channelId,
              durationSeconds: streamDuration.current,
            });
          }
        }
        setStatus('');
        setPaused(false);
        showOverlay();
      });
      el.addEventListener('pause', () => {
        setPaused(true);
        savePositionNow();
      });
      el.addEventListener('waiting', () => setStatus('バッファ中…'));
      el.addEventListener('durationchange', () => {
        const d = p.duration;
        if (Number.isFinite(d) && d > 0) {
          streamDuration.current = d;
          setDuration(d);
        }
      });
      el.addEventListener('timeupdate', () => {
        const t = p.currentTime;
        setPosition(t);
        setSubtitle(cues.current.length > 0 ? cueAt(cues.current, t) : '');
        if (Date.now() - lastSaved.current > SAVE_INTERVAL_MS) {
          lastSaved.current = Date.now();
          savePositionNow();
        }
        const seg = segments.current.find(
          (sg) => t >= sg.start && t < sg.end - 0.5,
        );
        if (seg) {
          p.currentTime = seg.end;
          showToast(
            `${
              CATEGORY_LABELS[seg.category] ?? seg.category
            } をスキップしました`,
          );
        }
      });
      el.addEventListener('ended', () => {
        if (settings.get().saveHistory) {
          // 最後まで見たので再生位置を消す
          savePosition(
            video.id,
            streamDuration.current,
            streamDuration.current,
          );
        }
        onEnded();
      });
      el.addEventListener('error', () => {
        // 切り替え直後のエラーは直前の (破棄した) ソースのものなので、新しい候補を失敗扱いにしない
        if (Date.now() - switchedAt.current < SWITCH_GRACE_MS) {
          return;
        }
        const err = el.error;
        nextSourceRef.current(
          sourceIndex.current,
          err
            ? `code ${err.code ?? '?'} ${err.message ?? ''}`.trim()
            : undefined,
        );
      });
    },
    [onEnded, savePositionNow, showOverlay, showToast, video],
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
    savePositionNow();
    player.current = null;
    ready.current = false;
    dash.current?.destroy();
    if (!p) {
      return;
    }
    p.clearSurfaceHandle(surfaceHandle);
    await p.deinitialize();
  };

  useEffect(
    () => () => {
      [hideTimer, startTimer, toastTimer].forEach(
        (t) => t.current && clearTimeout(t.current),
      );
      savePositionNow();
      dash.current?.destroy();
      player.current?.pause();
    },
    [savePositionNow],
  );

  // メニューを開いている間は、戻るでメニューだけ閉じる (App の戻る処理より先に呼ばれる)
  useEffect(() => {
    if (!menuOpen) {
      return;
    }
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setMenuOpen(false);
      return true;
    });
    return () => sub.remove();
  }, [menuOpen]);

  const seekBy = (delta: number) => {
    const p = player.current;
    if (!p) {
      return;
    }
    const max = duration > 0 ? duration - 1 : Number.MAX_SAFE_INTEGER;
    p.currentTime = Math.min(max, Math.max(0, p.currentTime + delta));
    showOverlay();
  };

  const togglePause = () => {
    const p = player.current;
    if (!p) {
      return;
    }
    if (p.paused) {
      playSafely(p);
    } else {
      p.pause();
    }
    showOverlay();
  };

  useTVEventHandler((evt: {eventType?: string; eventKeyAction?: number}) => {
    if (evt.eventKeyAction !== 0 || menuOpen) {
      return; // 押下時のみ (1 = 離した時)。メニュー表示中は標準のフォーカス移動に任せる
    }
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
      case 'down':
      case 'menu':
        if (!error) {
          setMenuOpen(true);
        }
        break;
      default:
        showOverlay();
    }
  });

  // ---------------------------------------------------------------- メニュー

  const qualityLabel =
    currentSource?.kind === 'dash'
      ? `${currentSource.video.height}p`
      : '自動 (HLS)';

  const cycleQuality = () => {
    const cur = info.current;
    const src = currentSource;
    if (!cur || !cur.dashAudio || cur.dashVideos.length === 0) {
      showToast('この動画は画質を切り替えられません');
      return;
    }
    // 高画質順の一覧で次 (1 段下) へ。最低画質の次は最高画質へ戻る
    const heights = cur.dashVideos;
    const i =
      src?.kind === 'dash'
        ? heights.findIndex((v) => v.height === src.video.height)
        : -1;
    const next = heights[(i + 1) % heights.length];
    const replacement = dashSource(next, cur.dashAudio, src?.client ?? '');
    sources.current[sourceIndex.current] = replacement;
    playSource(replacement, player.current?.currentTime ?? 0);
    showToast(`画質: ${next.height}p`);
  };

  const cycleCaption = () => {
    if (captionList.length <= 1) {
      showToast('この動画には字幕がありません');
      return;
    }
    const next = (captionIndex + 1) % captionList.length;
    selectCaption(captionList, next);
    showToast(`字幕: ${captionList[next].label}`);
  };

  const menuItems: MenuItem[] = [
    {
      label: inLater ? '「後で見る」から削除' : '「後で見る」に追加',
      onPress: () =>
        showToast(
          toggleWatchLater(video)
            ? '「後で見る」に追加しました'
            : '「後で見る」から削除しました',
        ),
    },
    {
      label: `字幕: ${captionList[captionIndex]?.label ?? 'オフ'}`,
      onPress: cycleCaption,
    },
    {label: `画質: ${qualityLabel}`, onPress: cycleQuality},
  ];
  if (channel) {
    menuItems.push(
      {
        label: subscribed ? 'チャンネル登録を解除' : 'チャンネル登録',
        onPress: () =>
          showToast(
            toggleSubscription(channel)
              ? `${channel.name} を登録しました`
              : `${channel.name} の登録を解除しました`,
          ),
      },
      {
        label: `チャンネルを開く: ${channel.name}`,
        onPress: () => {
          savePositionNow();
          onOpenChannel(channel);
        },
      },
    );
  }
  menuItems.push({label: '閉じる', onPress: () => setMenuOpen(false)});

  const progress = duration > 0 ? Math.min(1, position / duration) : 0;
  const showBar = (overlay || paused) && !error && !menuOpen;

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
          <Text style={error ? styles.error : styles.status}>
            {error ?? status}
          </Text>
          {error && <Text style={styles.hint}>戻るボタンで一覧に戻ります</Text>}
        </View>
      )}
      {subtitle !== '' && (
        <View
          style={[styles.subtitleBox, showBar && styles.subtitleRaised]}
          pointerEvents="none">
          <Text style={styles.subtitle}>{subtitle}</Text>
        </View>
      )}
      {toast && <Text style={styles.toast}>{toast}</Text>}
      {showBar && (
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
            {'    '}下キー: メニュー
          </Text>
        </View>
      )}
      {menuOpen && <PlayerMenu items={menuItems} />}
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
  error: {
    color: colors.error,
    fontSize: s(32),
    marginHorizontal: s(120),
    textAlign: 'center',
  },
  hint: {color: colors.textDim, fontSize: s(24), marginTop: s(16)},
  subtitleBox: {
    position: 'absolute',
    left: s(160),
    right: s(160),
    bottom: s(60),
    alignItems: 'center',
  },
  subtitleRaised: {bottom: s(260)},
  subtitle: {
    color: colors.text,
    fontSize: s(40),
    lineHeight: s(54),
    textAlign: 'center',
    paddingHorizontal: s(16),
    backgroundColor: 'rgba(0,0,0,0.7)',
  },
  toast: {
    position: 'absolute',
    top: s(48),
    left: s(48),
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
