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
        print(f'\033[0;96mhttp://{self.headers.get("host")}{self.path}\033[0m\n')
        print(self.headers)
        self.send_response(HTTPStatus.OK)
        self.end_headers()

        if self.path == '/info':
            self.wfile.write(b'''
            {
                "version": "7.39.2",
                "git_commit": "f6f7bc9",
                "endpoints": [
                    "/v0.3/traces",
                    "/v0.3/services",
                    "/v0.4/traces",
                    "/v0.4/services",
                    "/v0.5/traces",
                    "/v0.7/traces",
                    "/profiling/v1/input",
                    "/telemetry/proxy/",
                    "/v0.6/stats",
                    "/v0.1/pipeline_stats",
                    "/appsec/proxy/",
                    "/evp_proxy/v1/",
                    "/debugger/v1/input",
                    "/v0.7/config",
                    "/config/set"
                ],
                "feature_flags": [
                    ""
                ],
                "client_drop_p0s": true,
                "span_meta_structs": true,
                "long_running_spans": true,
                "config": {
                    "default_env": "none",
                    "target_tps": 10,
                    "max_eps": 200,
                    "receiver_port": 8126,
                    "receiver_socket": "",
                    "connection_limit": 0,
                    "receiver_timeout": 0,
                    "max_request_bytes": 52428800,
                    "statsd_port": 8125,
                    "max_memory": 500000000,
                    "max_cpu": 0.5,
                    "analyzed_spans_by_service": {},
                    "obfuscation": {
                        "elastic_search": false,
                        "mongo": false,
                        "sql_exec_plan": false,
                        "sql_exec_plan_normalize": false,
                        "http": {
                            "remove_query_string": false,
                            "remove_path_digits": false
                        },
                        "remove_stack_traces": false,
                        "redis": false,
                        "memcached": false
                    }
                }
            }''')

    def do_POST(self):
        print_line()
        headers = str(self.headers)
        print(f'\033[0;96mhttp://{self.headers.get("host")}{self.path}\033[0m\n')
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
