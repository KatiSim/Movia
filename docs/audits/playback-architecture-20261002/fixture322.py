from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import json, urllib.parse, threading, time, ssl

OUT = Path('/data/data/com.termux/files/home/.cache/movia-architecture-20261002')
ROOT = Path('/data/data/com.termux/files/home/.cache/movia-implementation-20261001/fixtures')
lock = threading.Lock()
state = {'mode': 'online', 'mediaRequests': 0, 'paths': []}
duplicate = '#EXTM3U\n#EXT-X-VERSION:3\n'
for group in ('audio', 'audio-copy'):
    for voice in ('A', 'B'):
        default = 'YES' if voice == 'A' else 'NO'
        duplicate += f'#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="{group}",NAME="Studio {voice}",LANGUAGE="ru",DEFAULT={default},AUTOSELECT=YES,URI="audio{voice}/index.m3u8?group={group}"\n'
for group in ('audio', 'audio-copy'):
    for height, width, bandwidth, codec in ((360,640,120000,'avc1.42c01e'),(720,1280,500000,'avc1.42c01f')):
        duplicate += f'#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION={width}x{height},CODECS="{codec},mp4a.40.2",AUDIO="{group}"\nv{height}/index.m3u8?group={group}\n'

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=str(ROOT), **k)
    def log_message(self, *a): pass
    def reply(self, body, mime='application/json'):
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/__control':
            mode = urllib.parse.parse_qs(parsed.query).get('mode', ['online'])[0]
            if mode not in ('online', 'offline'): self.send_error(400); return
            with lock:
                state.update(mode=mode, mediaRequests=0, paths=[])
                body = json.dumps(state).encode()
            self.reply(body); return
        if parsed.path == '/__stats':
            with lock: body = json.dumps(state).encode()
            self.reply(body); return
        with lock:
            state['mediaRequests'] += 1
            state['paths'].append(parsed.path)
            mode = state['mode']
        if mode == 'offline': self.send_error(503, 'Fixture media intentionally offline'); return
        if parsed.path == '/stall.mp4':
            time.sleep(12)
            try: self.send_error(504, 'Intentional startup stall')
            except (BrokenPipeError, ConnectionResetError): pass
            return
        if parsed.path == '/duplicate-master.m3u8':
            self.reply(duplicate.encode(), 'application/vnd.apple.mpegurl'); return
        super().do_GET()

tls = ThreadingHTTPServer(('127.0.0.1', 8898), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(OUT/'fixture-cert.pem', OUT/'fixture-key.pem')
tls.socket = context.wrap_socket(tls.socket, server_side=True)
threading.Thread(target=tls.serve_forever, daemon=True).start()
print('FIXTURE_SERVER_READY localhost:8897 TLS:8898', flush=True)
ThreadingHTTPServer(('127.0.0.1', 8897), Handler).serve_forever()
