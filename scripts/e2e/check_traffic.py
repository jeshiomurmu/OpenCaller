#!/usr/bin/env python3
"""
Privacy traffic check (docs/architecture.md §10, docs/testing.md → Privacy) on the e2e emulators' packet captures
(`emulator -tcpdump`): every TCP payload in either direction is reassembled per connection and

  1. no canary may appear anywhere: the contact, call-log entry and SMS that scripts/e2e/ci.sh planted on the phones
     before the tests (their names, numbers in any common spelling, message text) must never leave the phone, to
     the server or to any other host;
  2. the HTTP requests to the OpenCaller server (debug builds talk plain HTTP to 10.0.2.2:8080) are listed as an
     inventory, method and path with digits masked, so the endpoints used can be compared with §10;
  3. at least one request to the server must have been seen, so the check can't pass on an empty capture.

Also printed: the other destinations (IP:port, DNS names asked, TLS server names), for the record; the Android
system on the google_apis image talks to Google, which is not the app.

Standard library only. Exit 0: clean; 1: a canary was found or no server traffic; 2: unreadable capture.
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path

ETHERNET, RAW_IP, LINUX_SLL, LINUX_SLL2 = 1, 101, 113, 276
SERVER = ("10.0.2.2", 8080)
REQUEST_LINE = re.compile(rb"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) (/[^ \r\n]*) HTTP/1\.[01]\r\n")


def packets(path: Path):
    """(linktype, frame bytes) for each record of a classic pcap file."""
    data = path.read_bytes()
    if len(data) < 24:
        raise ValueError(f"{path}: not a pcap file")
    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
        endian = "<"
    elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
        endian = ">"
    else:
        raise ValueError(f"{path}: unknown pcap magic {magic.hex()}")
    linktype = struct.unpack(endian + "I", data[20:24])[0]
    offset = 24
    while offset + 16 <= len(data):
        _, _, captured, _ = struct.unpack(endian + "IIII", data[offset : offset + 16])
        offset += 16
        yield linktype, data[offset : offset + captured]
        offset += captured


def ip_payload(linktype: int, frame: bytes) -> bytes | None:
    if linktype == ETHERNET:
        if len(frame) < 14:
            return None
        ethertype = struct.unpack("!H", frame[12:14])[0]
        start = 14
        if ethertype == 0x8100 and len(frame) >= 18:  # VLAN tag
            ethertype, start = struct.unpack("!H", frame[16:18])[0], 18
        return frame[start:] if ethertype == 0x0800 else None
    if linktype == LINUX_SLL:
        return frame[16:] if len(frame) > 16 and struct.unpack("!H", frame[14:16])[0] == 0x0800 else None
    if linktype == LINUX_SLL2:  # tcpdump -i any: protocol type in the first two bytes of a 20-byte header
        return frame[20:] if len(frame) > 20 and struct.unpack("!H", frame[0:2])[0] == 0x0800 else None
    if linktype == RAW_IP:
        return frame if frame and frame[0] >> 4 == 4 else None
    return None


def segments(paths: list[Path]):
    """IPv4 TCP and UDP segments: (proto, src, sport, dst, dport, seq, payload)."""
    for path in paths:
        for linktype, frame in packets(path):
            ip = ip_payload(linktype, frame)
            if ip is None or len(ip) < 20:
                continue
            header = (ip[0] & 0x0F) * 4
            total = struct.unpack("!H", ip[2:4])[0] or len(ip)
            proto = ip[9]
            src, dst = ".".join(map(str, ip[12:16])), ".".join(map(str, ip[16:20]))
            body = ip[header:total]
            if proto == 6 and len(body) >= 20:
                sport, dport, seq = struct.unpack("!HHI", body[:8])
                offset = (body[12] >> 4) * 4
                yield "tcp", src, sport, dst, dport, seq, body[offset:]
            elif proto == 17 and len(body) >= 8:
                sport, dport = struct.unpack("!HH", body[:4])
                yield "udp", src, sport, dst, dport, 0, body[8:]


def streams(paths: list[Path]) -> tuple[dict, Counter, set]:
    """TCP payload per direction (deduplicated by sequence number), UDP destinations and DNS names asked."""
    pieces: dict[tuple, dict[int, bytes]] = defaultdict(dict)
    destinations: Counter = Counter()
    dns_names: set[str] = set()
    for proto, src, sport, dst, dport, seq, payload in segments(paths):
        if proto == "udp":
            destinations[f"udp {dst}:{dport}"] += 1
            if dport == 53:
                name = dns_question(payload)
                if name:
                    dns_names.add(name)
            udp = pieces[("udp", src, sport, dst, dport)]
            udp[len(udp)] = payload  # datagrams in arrival order
            continue
        if payload:
            pieces[("tcp", src, sport, dst, dport)].setdefault(seq, payload)
    joined = {flow: b"".join(chunks[k] for k in sorted(chunks)) for flow, chunks in pieces.items()}
    for (proto, _src, _sport, dst, dport) in joined:
        if proto == "tcp":
            destinations[f"tcp {dst}:{dport}"] += 1
    return joined, destinations, dns_names


def dns_question(payload: bytes) -> str | None:
    if len(payload) < 13:
        return None
    labels, i = [], 12
    while i < len(payload) and payload[i] != 0:
        length = payload[i]
        if length > 63 or i + 1 + length > len(payload):
            return None
        labels.append(payload[i + 1 : i + 1 + length].decode("ascii", "replace"))
        i += 1 + length
    return ".".join(labels) or None


def tls_server_names(data: bytes) -> set[str]:
    """Server names of TLS ClientHellos in a client-to-server stream (SNI extension)."""
    names = set()
    for match in re.finditer(rb"\x16\x03[\x00-\x04]..\x01", data, re.S):
        hello = data[match.start() + 5 :]
        try:
            i = 4 + 2 + 32
            i += 1 + hello[i]  # session id
            i += 2 + struct.unpack("!H", hello[i : i + 2])[0]  # cipher suites
            i += 1 + hello[i]  # compression
            end = i + 2 + struct.unpack("!H", hello[i : i + 2])[0]
            i += 2
            while i + 4 <= end:
                kind, length = struct.unpack("!HH", hello[i : i + 4])
                if kind == 0:
                    name_length = struct.unpack("!H", hello[i + 7 : i + 9])[0]
                    names.add(hello[i + 9 : i + 9 + name_length].decode("ascii", "replace"))
                i += 4 + length
        except (IndexError, struct.error):
            continue
    return names


def variants(canary: str) -> set[bytes]:
    """The spellings a canary could leave the phone in: as given, digits only, URL-encoded +, national forms."""
    out = {canary.encode()}
    digits = re.sub(r"\D", "", canary)
    if canary.startswith("+") and len(digits) >= 7:
        out |= {digits.encode(), ("%2B" + digits).encode(), digits[-10:].encode()}
    return {v for v in out if len(v) >= 6}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--pcap", action="append", type=Path, required=True)
    parser.add_argument("--canary", action="append", default=[], help="text or E.164 number that must never be sent")
    parser.add_argument(
        "--server-port",
        type=int,
        help="count requests to this port at any address as server requests (a capture on the CI host sees the "
        "server at 127.0.0.1 and the Docker bridge, not at the emulator's 10.0.2.2)",
    )
    args = parser.parse_args()

    try:
        flows, destinations, dns_names = streams(args.pcap)
    except (OSError, ValueError) as e:
        print(f"Unreadable capture: {e}", file=sys.stderr)
        return 2

    status = 0
    found = False
    for canary in args.canary:
        for spelling in variants(canary):
            for (proto, src, sport, dst, dport), data in flows.items():
                if spelling in data:
                    print(f"::error::canary {canary!r} ({spelling.decode()}) in {proto} {src}:{sport} -> {dst}:{dport}")
                    found = True
                    status = 1

    requests: Counter = Counter()
    for (proto, _src, _sport, dst, dport), data in flows.items():
        if proto == "tcp" and (dport == args.server_port if args.server_port else (dst, dport) == SERVER):
            for match in REQUEST_LINE.finditer(data):
                path = match.group(2).split(b"?")[0].decode("ascii", "replace")
                requests[f"{match.group(1).decode()} {re.sub(r'[0-9]{4,}', '…', path)}"] += 1
    where = f"port {args.server_port}" if args.server_port else f"{SERVER[0]}:{SERVER[1]}"
    print(f"Requests to the OpenCaller server at {where} (digits masked):")
    for line, count in sorted(requests.items()):
        print(f"  {count:4d}  {line}")
    if not requests:
        print("::error::no request to the server in the capture; the check would prove nothing")
        status = 1

    names = set()
    for (proto, _src, _sport, _dst, dport), data in flows.items():
        if proto == "tcp" and dport == 443:
            names |= tls_server_names(data)
    print("Other destinations (system and app; for the record):")
    for destination, count in sorted(destinations.items()):
        print(f"  {count:6d}  {destination}")
    print("DNS names asked: " + (", ".join(sorted(dns_names)) or "none"))
    print("TLS server names: " + (", ".join(sorted(names)) or "none"))
    print("Canaries: " + ("none given" if not args.canary else f"{len(args.canary)} checked, " + ("FOUND" if found else "none found")))
    return status


if __name__ == "__main__":
    sys.exit(main())
