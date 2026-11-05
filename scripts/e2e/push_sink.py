#!/usr/bin/env python3
"""Stand-in for a UnifiedPush distributor's Web Push endpoint in the emulator app-call test (scripts/e2e/voip.sh).

Emulators have no distributor app, so the test registers this sink as the callee's endpoint. It answers 201 like a
push service and appends one JSON line per push to <log>: the path, the Web Push headers and the body's size and
first bytes (hex). The body is RFC 8291 ciphertext; the sink can't read it, which is the point (ADR 0007).

Usage: push_sink.py <port> <log>
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
LOG = sys.argv[2]
HEADERS = ("Content-Encoding", "Content-Type", "TTL", "Urgency", "Authorization")
MAX_BODY = 8192


class Sink(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 (http.server's naming)
        length = min(int(self.headers.get("Content-Length", "0") or 0), MAX_BODY)
        body = self.rfile.read(length)
        record = {
            "path": self.path,
            "headers": {name: self.headers.get(name) for name in HEADERS if self.headers.get(name) is not None},
            "length": len(body),
            "head": body[:32].hex(),
            "plain": body.decode("utf-8", "replace") if body[:1] == b"{" else None,
        }
        # VAPID's JWT is a credential of the test's throwaway key pair; keep only its scheme.
        if "Authorization" in record["headers"]:
            record["headers"]["Authorization"] = record["headers"]["Authorization"].split(" ", 1)[0]
        with open(LOG, "a", encoding="utf-8") as out:
            out.write(json.dumps(record) + "\n")
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", PORT), Sink).serve_forever()
