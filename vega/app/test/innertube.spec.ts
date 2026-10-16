import {parseConfig, DEFAULT_CONFIG} from '../src/api/config';
import {
  interleave,
  parseDuration,
  parsePlayerResponse,
  parseVideoRenderers,
} from '../src/api/innertube';

const video = (id: string, title = `t-${id}`) => ({
  id,
  title,
  uploaderName: '',
  thumbnailUrl: '',
  durationSeconds: 0,
});

describe('parseDuration', () => {
  it('parses h:mm:ss and m:ss', () => {
    expect(parseDuration('1:02:03')).toBe(3723);
    expect(parseDuration('4:05')).toBe(245);
    expect(parseDuration(undefined)).toBe(0);
  });
});

describe('interleave', () => {
  it('alternates lists and drops duplicates', () => {
    const r = interleave([[video('aaaaa'), video('bbbbb')], [video('ccccc'), video('aaaaa')]]);
    expect(r.map((v) => v.id)).toEqual(['aaaaa', 'ccccc', 'bbbbb']);
  });
});

describe('parseVideoRenderers', () => {
  it('finds videoRenderer and lockupViewModel recursively and filters invalid', () => {
    const json = {
      contents: [
        {
          videoRenderer: {
            videoId: 'vid000001',
            title: {runs: [{text: 'Hello'}]},
            ownerText: {runs: [{text: 'Ch'}]},
            lengthText: {simpleText: '3:10'},
            thumbnail: {thumbnails: [{url: 'a'}, {url: 'b'}]},
          },
        },
        {videoRenderer: {videoId: 'vid000002', title: {simpleText: '非公開動画'}}},
        {
          deep: {
            lockupViewModel: {
              contentType: 'LOCKUP_CONTENT_TYPE_VIDEO',
              contentId: 'vid000003',
              metadata: {lockupMetadataViewModel: {title: {content: 'Lockup'}}},
            },
          },
        },
        {videoRenderer: {videoId: 'vid000001', title: {simpleText: 'dup'}}},
      ],
    };
    const items = parseVideoRenderers(json);
    expect(items.map((v) => v.id)).toEqual(['vid000001', 'vid000003']);
    expect(items[0]).toMatchObject({title: 'Hello', uploaderName: 'Ch', durationSeconds: 190, thumbnailUrl: 'b'});
  });
});

describe('parsePlayerResponse', () => {
  it('orders HLS first, then muxed MP4 by height', () => {
    const info = parsePlayerResponse(
      {
        playabilityStatus: {status: 'OK'},
        streamingData: {
          hlsManifestUrl: 'hls',
          formats: [
            {url: 'm360', mimeType: 'video/mp4', height: 360, qualityLabel: '360p'},
            {url: 'm720', mimeType: 'video/mp4', height: 720, qualityLabel: '720p'},
            {url: 'w', mimeType: 'video/webm', height: 1080},
          ],
        },
        videoDetails: {title: 'T', author: 'A', lengthSeconds: '42'},
      },
      'vid',
      'IOS',
    );
    expect(info.sources.map((x) => x.url)).toEqual(['hls', 'm720', 'm360']);
    expect(info.durationSeconds).toBe(42);
  });

  it('throws the reason when unplayable', () => {
    expect(() =>
      parsePlayerResponse({playabilityStatus: {status: 'LOGIN_REQUIRED', reason: 'ログインが必要'}}, 'v', 'IOS'),
    ).toThrow('ログインが必要');
  });
});

describe('parseConfig', () => {
  it('falls back to defaults for missing or invalid fields', () => {
    const c = parseConfig({popularQueries: [], clientVersions: {IOS: '1.0', X: 3}});
    expect(c.popularQueries).toEqual(DEFAULT_CONFIG.popularQueries);
    expect(c.clientVersions).toEqual({IOS: '1.0'});
  });
});
