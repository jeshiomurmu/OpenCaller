"""
Downloads a stratified subset of the ASVspoof 2019 LA evaluation set without fetching the whole 7.6 GB archive.

ASVspoof 2019 (Wang et al., University of Edinburgh) is published under the Open Data Commons Attribution License
(ODC-By 1.0) at https://datashare.ed.ac.uk/handle/10283/3336. The archive server accepts HTTP range requests, so
this script reads the zip's central directory, then only the evaluation protocol and the chosen FLAC files.

    python -I scripts/fetch_asvspoof2019_subset.py --out data/asvspoof2019 --bonafide 1000 --per-attack 100

The subset is deterministic (sorted by file name, every n-th file), so a re-run downloads the same files.
"""

from __future__ import annotations

import argparse
import http.client
import io
import os
import ssl
import struct
import sys
import time
import urllib.request
import zipfile
import zlib
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

LA_ZIP = "https://datashare.ed.ac.uk/bitstream/handle/10283/3336/LA.zip?sequence=3&isAllowed=y"
PROTOCOL = "LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.eval.trl.txt"
EVAL_FLAC = "LA/ASVspoof2019_LA_eval/flac/{}.flac"
RETRIES = 6


class HttpRangeFile(io.RawIOBase):
    """A read-only, seekable file over HTTP range requests (enough for `zipfile`)."""

    def __init__(self, url: str, context: ssl.SSLContext | None = None) -> None:
        self._context = context
        # The server answers HEAD with an HTML page, so a one-byte range finds the file's address and size.
        request = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
        with urllib.request.urlopen(request, context=context, timeout=60) as response:
            self.url = response.geturl()  # after redirects
            content_range = response.headers.get("Content-Range", "")
            if response.status != 206 or "/" not in content_range:
                raise OSError(f"The server doesn't serve byte ranges (HTTP {response.status})")
            self.size = int(content_range.rsplit("/", 1)[1])
        self._pos = 0

    def seekable(self) -> bool:
        return True

    def readable(self) -> bool:
        return True

    def tell(self) -> int:
        return self._pos

    def seek(self, offset: int, whence: int = io.SEEK_SET) -> int:
        base = {io.SEEK_SET: 0, io.SEEK_CUR: self._pos, io.SEEK_END: self.size}[whence]
        self._pos = max(0, base + offset)
        return self._pos

    def read(self, n: int = -1) -> bytes:
        if self._pos >= self.size:
            return b""
        end = self.size - 1 if n is None or n < 0 else min(self.size, self._pos + n) - 1
        request = urllib.request.Request(self.url, headers={"Range": f"bytes={self._pos}-{end}"})
        data = self._fetch(request)
        self._pos += len(data)
        return data

    def range(self, start: int, end: int) -> bytes:
        """Bytes [start, end) without touching the file position (safe from several threads)."""
        request = urllib.request.Request(self.url, headers={"Range": f"bytes={start}-{min(end, self.size) - 1}"})
        return self._fetch(request)

    def _fetch(self, request: urllib.request.Request) -> bytes:
        # The archive server drops connections now and then; a range read is safe to repeat.
        for attempt in range(RETRIES):
            try:
                with urllib.request.urlopen(request, context=self._context, timeout=120) as response:
                    return response.read()
            except (OSError, http.client.HTTPException) as e:
                if attempt == RETRIES - 1:
                    raise
                print(f"retrying after {e!r}", file=sys.stderr)
                time.sleep(2**attempt)
        raise AssertionError("unreachable")

    def readinto(self, buffer) -> int:  # type: ignore[override]
        data = self.read(len(buffer))
        buffer[: len(data)] = data
        return len(data)


def member(source: HttpRangeFile, info: zipfile.ZipInfo) -> bytes:
    """One archive member read with a single range request (local header, then the data), so workers can share
    [source]'s address without sharing a file position."""
    slack = 30 + len(info.filename.encode()) + 1024
    raw = source.range(info.header_offset, info.header_offset + slack + info.compress_size)
    if raw[:4] != b"PK\x03\x04":
        raise zipfile.BadZipFile(f"no local header for {info.filename}")
    name_length, extra_length = struct.unpack("<HH", raw[26:30])
    start = 30 + name_length + extra_length
    data = raw[start : start + info.compress_size]
    if info.compress_type == zipfile.ZIP_STORED:
        return data
    if info.compress_type == zipfile.ZIP_DEFLATED:
        return zlib.decompressobj(-zlib.MAX_WBITS).decompress(data)
    raise zipfile.BadZipFile(f"unsupported compression {info.compress_type} for {info.filename}")


def choose(protocol: str, bonafide: int, per_attack: int) -> list[tuple[str, str, str]]:
    """(utterance, attack, label) rows: `bonafide` genuine utterances and `per_attack` of each attack (A07–A19)."""
    groups: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    for line in protocol.splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        _speaker, utterance, _env, attack, label = parts
        groups["bonafide" if label == "bonafide" else attack].append((utterance, attack, label))
    chosen: list[tuple[str, str, str]] = []
    for key, rows in sorted(groups.items()):
        rows.sort()
        want = bonafide if key == "bonafide" else per_attack
        step = max(1, len(rows) // max(1, want))
        chosen.extend(rows[::step][:want])
    return chosen


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--bonafide", type=int, default=1000)
    parser.add_argument("--per-attack", type=int, default=100)
    parser.add_argument("--url", default=LA_ZIP)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args(argv)

    bundle = os.environ.get("SSL_CERT_FILE") or os.environ.get("REQUESTS_CA_BUNDLE")
    context = ssl.create_default_context(cafile=bundle) if bundle else None
    source = HttpRangeFile(args.url, context)
    archive = zipfile.ZipFile(io.BufferedReader(source, buffer_size=1 << 20))
    protocol = archive.read(PROTOCOL).decode()
    rows = choose(protocol, args.bonafide, args.per_attack)

    flac_dir = args.out / "flac"
    flac_dir.mkdir(parents=True, exist_ok=True)
    wanted = [archive.getinfo(EVAL_FLAC.format(u)) for u, _a, _l in rows if not (flac_dir / f"{u}.flac").exists()]
    done = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for name, data in pool.map(lambda info: (info.filename, member(source, info)), wanted):
            (flac_dir / Path(name).name).write_bytes(data)
            done += 1
            if done % 200 == 0:
                print(f"{done}/{len(wanted)}", file=sys.stderr)
    (args.out / "protocol.txt").write_text("".join(f"{u} {a} {lbl}\n" for u, a, lbl in rows))
    print(f"{len(rows)} utterances in {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
