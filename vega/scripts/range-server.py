"""CI 用: Range 要求に対応した静的ファイルサーバ (python の http.server は Range に対応していないため)。
アクセスは標準エラーに記録する (仮想端末から届いたかの確認用)。"""
import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class RangeHandler(SimpleHTTPRequestHandler):
    def send_head(self):
        path = self.translate_path(self.path)
        m = re.match(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        if not m or not os.path.isfile(path):
            return super().send_head()
        size = os.path.getsize(path)
        start = int(m.group(1))
        end = int(m.group(2)) if m.group(2) else size - 1
        if start >= size:
            self.send_error(416)
            return None
        end = min(end, size - 1)
        f = open(path, 'rb')
        f.seek(start)
        self.send_response(206)
        self.send_header('Content-Type', self.guess_type(path))
        self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.send_header('Content-Length', str(end - start + 1))
        self.send_header('Accept-Ranges', 'bytes')
        self.end_headers()
        self.range_left = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        left = getattr(self, 'range_left', None)
        if left is None:
            return super().copyfile(source, outputfile)
        while left > 0:
            chunk = source.read(min(65536, left))
            if not chunk:
                break
            outputfile.write(chunk)
            left -= len(chunk)


os.chdir(sys.argv[1])
ThreadingHTTPServer(('0.0.0.0', int(sys.argv[2])), RangeHandler).serve_forever()
