#!/usr/bin/env python3
"""
Minimal mock Datadog Agent that intercepts DSM (Data Streams Monitoring) payloads
and logs Kafka config reports. Used for E2E testing of the Kafka config capture feature.

Listens on port 8126 and handles:
- PUT /v0.1/pipeline_stats  (DSM endpoint)
- PUT /v0.4/traces          (APM traces, accepted but not parsed in detail)
- GET  /info                (agent info for feature discovery)
"""

import gzip
import json
import sys
import io
from http.server import HTTPServer, BaseHTTPRequestHandler

try:
    import msgpack
except ImportError:
    print("ERROR: msgpack not installed. Run: pip install msgpack", file=sys.stderr)
    sys.exit(1)

# Track seen configs for dedup verification
seen_configs = []
payload_count = 0


class MockAgentHandler(BaseHTTPRequestHandler):
    def _read_body(self):
        """Read request body handling both Content-Length and chunked transfer encoding."""
        content_length = self.headers.get('Content-Length')
        if content_length is not None:
            return self.rfile.read(int(content_length))

        transfer_encoding = self.headers.get('Transfer-Encoding', '')
        if 'chunked' in transfer_encoding.lower():
            body = b''
            while True:
                line = self.rfile.readline().strip()
                chunk_size = int(line, 16)
                if chunk_size == 0:
                    self.rfile.readline()  # trailing \r\n
                    break
                body += self.rfile.read(chunk_size)
                self.rfile.readline()  # trailing \r\n after chunk
            return body

        return b''

    def _handle_request(self, method):
        global payload_count
        body = self._read_body()

        if self.path == '/v0.1/pipeline_stats':
            payload_count += 1
            print(f"\n=== DSM Payload #{payload_count} received via {method} ({len(body)} bytes) ===", flush=True)
            self.parse_dsm_payload(body)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{}')

        elif self.path.startswith('/v0.'):
            # Accept APM traces silently
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{}')

        else:
            print(f"{method} {self.path} (unknown)", flush=True)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{}')

    def do_PUT(self):
        self._handle_request('PUT')

    def do_POST(self):
        self._handle_request('POST')

    def do_GET(self):
        if self.path == '/info':
            info = {
                "version": "7.50.0",
                "endpoints": [
                    "/v0.4/traces",
                    "/v0.1/pipeline_stats",
                ],
                "feature_flags": [],
                "config": {},
            }
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(info).encode())
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{}')

    def parse_dsm_payload(self, body):
        try:
            # DSM payloads are gzip compressed MsgPack
            decompressed = gzip.decompress(body)
            data = msgpack.unpackb(decompressed, raw=False)

            env = data.get('Env', data.get(b'Env', ''))
            service = data.get('Service', data.get(b'Service', ''))
            lang = data.get('Lang', data.get(b'Lang', ''))
            version = data.get('Version', data.get(b'Version', ''))

            print(f"  Env={env}, Service={service}, Lang={lang}, Version={version}", flush=True)

            stats = data.get('Stats', data.get(b'Stats', []))
            print(f"  Number of stat buckets: {len(stats)}", flush=True)

            for i, bucket in enumerate(stats):
                start = bucket.get('Start', bucket.get(b'Start', 0))
                duration = bucket.get('Duration', bucket.get(b'Duration', 0))
                stats_groups = bucket.get('Stats', bucket.get(b'Stats', []))
                backlogs = bucket.get('Backlogs', bucket.get(b'Backlogs', []))
                configs = bucket.get('Configs', bucket.get(b'Configs', []))

                print(f"  Bucket #{i}: Start={start}, Duration={duration}, "
                      f"StatsGroups={len(stats_groups)}, Backlogs={len(backlogs)}, "
                      f"Configs={len(configs)}", flush=True)

                if configs:
                    for config_entry in configs:
                        config_type = config_entry.get('Type', config_entry.get(b'Type', ''))
                        config_map = config_entry.get('Config', config_entry.get(b'Config', {}))

                        # Normalize keys if they are bytes
                        if isinstance(config_type, bytes):
                            config_type = config_type.decode('utf-8')
                        normalized_config = {}
                        for k, v in config_map.items():
                            nk = k.decode('utf-8') if isinstance(k, bytes) else k
                            nv = v.decode('utf-8') if isinstance(v, bytes) else v
                            normalized_config[nk] = nv

                        print(f"    CONFIG: Type={config_type}", flush=True)
                        # Print a subset of interesting config keys
                        interesting_keys = [
                            'bootstrap.servers', 'acks', 'group.id', 'auto.offset.reset',
                            'client.id', 'linger.ms', 'key.serializer', 'value.serializer',
                            'key.deserializer', 'value.deserializer'
                        ]
                        for ik in interesting_keys:
                            if ik in normalized_config:
                                print(f"      {ik} = {normalized_config[ik]}", flush=True)
                        print(f"      Total config entries: {len(normalized_config)}", flush=True)

                        seen_configs.append({
                            'type': config_type,
                            'config': normalized_config,
                            'payload_number': payload_count
                        })

            # Print dedup summary
            if seen_configs:
                print(f"\n  === CUMULATIVE CONFIG SUMMARY ===", flush=True)
                print(f"  Total configs received across all payloads: {len(seen_configs)}", flush=True)
                types_seen = set(c['type'] for c in seen_configs)
                for t in sorted(types_seen):
                    count = sum(1 for c in seen_configs if c['type'] == t)
                    print(f"    {t}: {count} instance(s)", flush=True)

        except Exception as e:
            print(f"  ERROR parsing DSM payload: {e}", file=sys.stderr, flush=True)
            import traceback
            traceback.print_exc()

    def log_message(self, format, *args):
        # Suppress default request logging to reduce noise
        pass


def main():
    port = 8126
    print(f"[MockAgent] Starting mock Datadog agent on port {port}...", flush=True)
    server = HTTPServer(('0.0.0.0', port), MockAgentHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[MockAgent] Shutting down.", flush=True)
        server.shutdown()


if __name__ == '__main__':
    main()
