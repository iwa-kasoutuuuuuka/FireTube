jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);

import {extractStreamInfo, prefetchVisitorData} from '../src/api/innertube';
import {getCachedList, putCachedList} from '../src/store/listCache';
import {VISITOR_TTL_MS, visitorCache, warmUp} from '../src/startup';
import {APP_VERSION} from '../src/version';

const video = (id: string) => ({
  id,
  title: `t-${id}`,
  uploaderName: '',
  thumbnailUrl: '',
  durationSeconds: 0,
});

const okPlayer = {
  playabilityStatus: {status: 'OK'},
  streamingData: {hlsManifestUrl: 'hls'},
  videoDetails: {},
};

function mockFetch() {
  const calls: string[] = [];
  const fn = jest.fn(async (url: string) => {
    calls.push(url);
    if (url.includes('/watch?')) {
      return {
        ok: true,
        text: async (): Promise<string> =>
          '..."visitorData":"VISITOR123"...,"signatureTimestamp":20500,...',
      };
    }
    return {
      ok: true,
      json: async () => okPlayer,
      text: async (): Promise<string> => '',
    };
  });
  // eslint-disable-next-line no-undef
  (globalThis as any).fetch = fn;
  return calls;
}

describe('list cache', () => {
  it('returns the cached list only for the same key', () => {
    putCachedList('subscriptions', [video('a')], 'UC1,UC2');
    expect(getCachedList('subscriptions', 'UC1,UC2').map((v) => v.id)).toEqual([
      'a',
    ]);
    expect(getCachedList('subscriptions', 'UC1')).toEqual([]);
    expect(getCachedList('unknown')).toEqual([]);
  });
});

describe('visitorData', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('reuses a fresh saved value without downloading the watch page', async () => {
    const calls = mockFetch();
    visitorCache.set({
      visitorData: 'SAVED',
      signatureTimestamp: 1,
      fetchedAt: 1000,
    });
    warmUp(1000 + VISITOR_TTL_MS - 1);
    jest.runAllTimers();
    await extractStreamInfo('vid');
    expect(calls.some((u) => u.includes('/watch?'))).toBe(false);
  });

  it('prefetches once even when called concurrently', async () => {
    const calls = mockFetch();
    jest.resetModules();
    const fresh = require('../src/api/innertube');
    const [a, b] = await Promise.all([
      fresh.prefetchVisitorData(5),
      fresh.prefetchVisitorData(5),
    ]);
    expect(a).toEqual({
      visitorData: 'VISITOR123',
      signatureTimestamp: 20500,
      fetchedAt: 5,
    });
    expect(b).toEqual(a);
    expect(calls.filter((u) => u.includes('/watch?'))).toHaveLength(1);
  });

  it('exports the prefetch function', () => {
    expect(typeof prefetchVisitorData).toBe('function');
  });
});

describe('version', () => {
  it('matches package.json', () => {
    expect(require('../package.json').version).toBe(APP_VERSION);
  });
});
