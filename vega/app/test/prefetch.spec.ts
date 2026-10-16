jest.mock('../src/api/innertube', () => ({
  extractStreamInfo: jest.fn(),
}));

import {extractStreamInfo} from '../src/api/innertube';
import {
  clearStreamCache,
  prefetchStream,
  takeStream,
} from '../src/api/streamCache';
import {signal} from '../src/player/signal';

const extract = extractStreamInfo as jest.Mock;

describe('stream prefetch', () => {
  beforeEach(() => {
    clearStreamCache();
    extract.mockReset();
    extract.mockImplementation(async (id: string) => ({videoId: id}));
  });

  it('reuses a prefetched result once', async () => {
    prefetchStream('a', 1080, 0);
    prefetchStream('a', 1080, 1000); // 取得済みなので通信しない
    expect(await takeStream('a', 1080, 2000)).toEqual({videoId: 'a'});
    expect(extract).toHaveBeenCalledTimes(1);
    await takeStream('a', 1080, 3000); // 使った分は外れているので取り直す
    expect(extract).toHaveBeenCalledTimes(2);
  });

  it('refetches when expired or the quality limit changed', async () => {
    prefetchStream('a', 1080, 0);
    await takeStream('a', 720, 1);
    prefetchStream('b', 1080, 0);
    await takeStream('b', 1080, 21 * 60 * 1000);
    expect(extract).toHaveBeenCalledTimes(4);
  });

  it('drops failed prefetches', async () => {
    extract.mockRejectedValueOnce(new Error('x'));
    prefetchStream('a', 1080, 0);
    await Promise.resolve();
    await Promise.resolve();
    expect(await takeStream('a', 1080, 1)).toEqual({videoId: 'a'});
    expect(extract).toHaveBeenCalledTimes(2);
  });
});

describe('signal', () => {
  it('notifies only when the value changes', () => {
    const s = signal(0);
    const listener = jest.fn();
    const off = s.subscribe(listener);
    s.set(0);
    s.set(1);
    s.set(1);
    off();
    s.set(2);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(s.get()).toBe(2);
  });
});
