"""CI 用: テスト動画の箱 (moov / sidx) の位置を調べ、src/debugStream.ts を書く。"""
import json
import struct
import sys


def boxes(path):
    out, pos = [], 0
    with open(path, 'rb') as f:
        data = f.read()
    while pos + 8 <= len(data):
        size, kind = struct.unpack('>I4s', data[pos:pos + 8])
        if size == 1:
            size = struct.unpack('>Q', data[pos + 8:pos + 16])[0]
        out.append((kind.decode('latin1'), pos, size))
        if size < 8:
            break
        pos += size
    return out


def track(path, url, mime, height):
    b = {k: (o, s) for k, o, s in boxes(path) if k in ('moov', 'sidx')}
    moov_off, moov_size = b['moov']
    sidx_off, sidx_size = b['sidx']
    return {
        'url': url, 'mimeType': mime,
        'initEnd': moov_off + moov_size - 1,
        'indexStart': sidx_off, 'indexEnd': sidx_off + sidx_size - 1,
        'height': height, 'bitrate': 1,
    }


media, host, out = sys.argv[1:4]
print('v.mp4 boxes:', [(k, o, s) for k, o, s in boxes(f'{media}/v.mp4')][:5])
print('a.mp4 boxes:', [(k, o, s) for k, o, s in boxes(f'{media}/a.mp4')][:5])
video = track(f'{media}/v.mp4', f'{host}/v.mp4', 'video/mp4; codecs="avc1.4D401F"', 720)
audio = track(f'{media}/a.mp4', f'{host}/a.mp4', 'audio/mp4; codecs="mp4a.40.2"', 0)
stream = {
    'videoId': 'debug', 'title': 'CI テスト動画', 'uploaderName': 'CI', 'durationSeconds': 40,
    'sources': [
        {'kind': 'dash', 'video': video, 'audio': audio, 'label': 'DASH 720p', 'client': 'CI'},
        {'kind': 'hls', 'url': f'{host}/hls/index.m3u8', 'label': 'HLS', 'client': 'CI'},
    ],
    'dashVideos': [video], 'dashAudio': audio, 'captions': [],
}
with open(out, 'w', encoding='utf-8') as f:
    f.write("import {StreamInfo} from './api/types';\n\n")
    f.write('export const DEBUG_STREAM: StreamInfo | null = ' + json.dumps(stream, ensure_ascii=False, indent=2) + ';\n')
