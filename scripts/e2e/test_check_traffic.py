"""Self-test of check_traffic.py on synthetic captures (run: python3 -m unittest scripts/e2e/test_check_traffic.py)."""

from __future__ import annotations

import io
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import check_traffic  # noqa: E402


def frame(src: str, sport: int, dst: str, dport: int, seq: int, payload: bytes) -> bytes:
    tcp = struct.pack("!HHIIBBHHH", sport, dport, seq, 0, 5 << 4, 0x18, 65535, 0, 0) + payload
    total = 20 + len(tcp)
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, total, 0, 0, 64, 6, 0, bytes(map(int, src.split("."))), bytes(map(int, dst.split("."))))
    return b"\x00" * 12 + b"\x08\x00" + ip + tcp


def pcap(frames: list[bytes]) -> bytes:
    out = struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1)
    for f in frames:
        out += struct.pack("<IIII", 0, 0, len(f), len(f)) + f
    return out


class CheckTrafficTest(unittest.TestCase):
    def run_check(self, frames: list[bytes], *canaries: str) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "a.pcap")
            path.write_bytes(pcap(frames))
            argv = ["check_traffic.py", "--pcap", str(path)] + [a for c in canaries for a in ("--canary", c)]
            out = io.StringIO()
            sys.argv = argv
            with redirect_stdout(out):
                code = check_traffic.main()
            return code, out.getvalue()

    request = b"POST /v1/reports HTTP/1.1\r\nHost: 10.0.2.2:8080\r\nContent-Length: 2\r\n\r\n{}"

    def test_clean_traffic_passes_and_lists_requests(self):
        code, out = self.run_check([frame("10.0.2.16", 40000, "10.0.2.2", 8080, 1, self.request)], "+12025550177")
        self.assertEqual(0, code, out)
        self.assertIn("POST /v1/reports", out)

    def test_a_canary_in_any_spelling_fails(self):
        for leaked in (b"+12025550177", b"12025550177", b"%2B12025550177", b"Canary Contact"):
            body = self.request + b"," + leaked
            code, out = self.run_check(
                [frame("10.0.2.16", 40000, "10.0.2.2", 8080, 1, body)], "+12025550177", "Canary Contact"
            )
            self.assertEqual(1, code, leaked)
            self.assertIn("canary", out)

    def test_a_canary_split_across_segments_is_found(self):
        first, second = self.request + b"CANARY-SMS-", b"7d1f"
        frames = [
            frame("10.0.2.16", 40000, "10.0.2.2", 8080, 1, first),
            frame("10.0.2.16", 40000, "10.0.2.2", 8080, 1 + len(first), second),
            frame("10.0.2.16", 40000, "10.0.2.2", 8080, 1, first),  # a retransmission
        ]
        code, _ = self.run_check(frames, "CANARY-SMS-7d1f")
        self.assertEqual(1, code)

    def test_no_server_traffic_fails(self):
        code, out = self.run_check([frame("10.0.2.16", 40000, "142.250.0.1", 443, 1, b"\x16\x03\x01")])
        self.assertEqual(1, code)
        self.assertIn("no request to the server", out)


if __name__ == "__main__":
    unittest.main()
