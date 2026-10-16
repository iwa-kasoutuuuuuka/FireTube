/**
 * 画面を実際に描画して、起動・タブ移動・検索・再生開始の流れで落ちないことを確かめる。
 * Vega 固有のプレーヤー (w3cmedia) と通信は差し替える
 */
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);

jest.mock('@amazon-devices/react-native-w3cmedia', () => {
  const React = require('react');
  class Emitter {
    listeners: Record<string, ((e?: unknown) => void)[]> = {};
    addEventListener(type: string, cb: (e?: unknown) => void) {
      (this.listeners[type] ??= []).push(cb);
    }
    removeEventListener() {}
    emit(type: string) {
      (this.listeners[type] ?? []).forEach((cb) => cb());
    }
  }
  class VideoPlayer extends Emitter {
    static last: VideoPlayer | null = null;
    currentTime = 0;
    duration = 100;
    paused = true;
    playbackRate = 1;
    autoplay = false;
    srcValue = '';
    srcObject: unknown = null;
    constructor() {
      super();
      VideoPlayer.last = this;
    }
    set src(v: string) {
      this.srcValue = v;
    }
    async initialize() {}
    async deinitialize() {}
    setSurfaceHandle() {}
    clearSurfaceHandle() {}
    play() {
      this.paused = false;
      return Promise.resolve();
    }
    pause() {
      this.paused = true;
    }
  }
  class MediaSource extends Emitter {
    readyState = 'closed';
    duration = 0;
    addSourceBuffer() {
      throw new Error('not in tests');
    }
    endOfStream() {}
  }
  const KeplerVideoSurfaceView = ({
    onSurfaceViewCreated,
  }: {
    onSurfaceViewCreated: (h: string) => void;
  }) => {
    // 実機と同じく、表示時に 1 回だけ通知する
    const created = React.useRef(onSurfaceViewCreated);
    React.useEffect(() => {
      created.current('surface');
    }, []);
    return null;
  };
  return {VideoPlayer, MediaSource, KeplerVideoSurfaceView};
});

import {act, fireEvent, render} from '@testing-library/react-native';
import * as React from 'react';
import {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import {App} from '../src/App';
import {history} from '../src/store/library';

const renderer = (i: number) => ({
  videoRenderer: {
    videoId: `video${String(i).padStart(6, '0')}`,
    title: {simpleText: `動画 ${i}`},
    ownerText: {runs: [{text: `チャンネル ${i}`}]},
    lengthText: {simpleText: '1:00'},
  },
});

function mockNetwork() {
  const json = (body: unknown) => ({
    ok: true,
    status: 200,
    json: async () => body,
    text: async (): Promise<string> => JSON.stringify(body),
  });
  // eslint-disable-next-line no-undef
  (globalThis as any).fetch = jest.fn(async (url: string) => {
    if (url.includes('/search')) {
      return json({contents: [renderer(1), renderer(2), renderer(3)]});
    }
    if (url.includes('/next')) {
      return json({contents: [renderer(7)]});
    }
    if (url.includes('/player')) {
      return json({
        playabilityStatus: {status: 'OK'},
        streamingData: {hlsManifestUrl: 'https://example.invalid/hls.m3u8'},
        videoDetails: {title: '動画 1', author: 'A', lengthSeconds: '60'},
      });
    }
    if (url.includes('returnyoutubedislike')) {
      return json({likes: 12345, dislikes: 67});
    }
    return {
      ok: false,
      status: 404,
      json: async () => ({}),
      text: async () => '',
    };
  });
}

/** 条件が満たされるまで実時間で待つ (このテストライブラリの版の waitFor は非同期処理を進めないため) */
async function until(check: () => unknown, timeoutMs = 4000): Promise<void> {
  const start = Date.now();
  for (;;) {
    try {
      check();
      return;
    } catch (e) {
      if (Date.now() - start > timeoutMs) {
        throw e;
      }
    }
    await act(async () => {
      await new Promise<void>((r) => setTimeout(r, 50));
    });
  }
}

describe('screens', () => {
  beforeEach(() => {
    mockNetwork();
    history.set([]);
  });

  it('starts, shows popular videos and switches tabs', async () => {
    const screen = render(<App />);
    await until(() => screen.getByText('動画 1'));
    for (const tab of [
      '登録チャンネル',
      '後で見る',
      '履歴',
      '設定',
      'ホーム',
    ]) {
      fireEvent.press(screen.getByText(tab));
    }
    expect(screen.getByText('登録チャンネルはまだありません')).toBeTruthy();
    expect(screen.getByText('最大画質')).toBeTruthy();
  });

  it('opens the player, starts HLS playback and records history', async () => {
    const screen = render(<App />);
    await until(() => screen.getByText('動画 1'));
    fireEvent.press(screen.getByText('動画 1'));

    const Player = VideoPlayer as unknown as {
      last: {srcValue: string; emit: (t: string) => void} | null;
    };
    await until(() => {
      expect(Player.last?.srcValue).toBe('https://example.invalid/hls.m3u8');
    });
    act(() => Player.last!.emit('playing'));
    await until(() => screen.getByText(/高評価 1.2万/));
    expect(history.get().map((e) => e.video.id)).toEqual(['video000001']);
  });

  it('searches from the search screen', async () => {
    const screen = render(<App />);
    await until(() => screen.getByText('動画 1'));
    fireEvent.press(screen.getByText('検索'));
    const input = screen.getByPlaceholderText('検索キーワードを入力して決定');
    fireEvent.changeText(input, 'テスト');
    fireEvent(input, 'submitEditing');
    await until(() => screen.getAllByText('動画 2'));
  });
});
