# This is simple telemetry intake emulator used to debug communication protocol
import http.server
import socketserver
from http import HTTPStatus

req_types = [
  'app-started',
  'app-dependencies-loaded',
  'app-integrations-change',
  'app-closing',
  'app-heartbeat',
  'generate-metrics'
]

def print_split_line():
    print(f'\033[0;32m════════════════════════════════════════════════════════════\033[0m')

class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        print(self.headers)
        self.send_response(HTTPStatus.OK)
        self.end_headers()
        print_split_line()
    def do_POST(self):
        headers = str(self.headers)
        for type in req_types:
          headers = headers.replace(type, f'\033[0;33m{type}\033[1;30m')
        print(f'\033[1;30m{headers}\033[0m')

        self.send_response(HTTPStatus.ACCEPTED)
        self.end_headers()
        hdr = self.headers.get('Content-Length')
        if hdr is not None:
            content_len = int(hdr)
            body = str(self.rfile.read(content_len), 'utf-8')
            print(body)
            #print(f'\033[0;33m{body}\033[0m')
            #self.wfile.write(b'Hello world')
        print_split_line()
    def log_message(self, format, *args):
        return

httpd = socketserver.TCPServer(('', 8126), Handler)
print_split_line()
httpd.serve_forever()
