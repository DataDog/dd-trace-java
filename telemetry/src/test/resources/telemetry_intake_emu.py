# This is simple telemetry intake emulator used to debug communication protocol
import http.server
import socketserver
from http import HTTPStatus
from datetime import datetime

PORT = 8126

req_types = [
  'app-started',
  'app-dependencies-loaded',
  'app-integrations-change',
  'app-closing',
  'app-heartbeat',
  'generate-metrics'
]

def print_line():
    cur_time = datetime.now().strftime('%H:%M:%S')
    print(f'\n\033[0;32m══[{cur_time}]════════════════════════════════════════════════\033[0m')

class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        print_line()
        print(self.headers)
        self.send_response(HTTPStatus.OK)
        self.end_headers()
    def do_POST(self):
        print_line()
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
    def log_message(self, format, *args):
        return


httpd = socketserver.TCPServer(('', PORT), Handler)
print(f'\033[1;30m════════════════════════════════════════════════════════════')
print(f'  Started Telemetry Intake Emulator.')
print(f'  Listening request on port: {PORT}')
print(f'════════════════════════════════════════════════════════════\033[0m')
httpd.serve_forever()
