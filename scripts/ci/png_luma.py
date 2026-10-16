#!/usr/bin/env python3
"""Print the mean relative luminance (0..1) of an 8-bit RGB/RGBA PNG, e.g. an `adb screencap`.

Used by emulator-smoke.sh to check that the app really switched between light and dark theme.
Standard library only (the CI runner has no Pillow guarantee).
"""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, width, height, channels = 8, b"", 0, 0, 0
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color_type = struct.unpack(">IIBB", chunk[:10])
            if depth != 8 or color_type not in (2, 6) or chunk[12] != 0:
                raise ValueError("only 8-bit, non-interlaced RGB/RGBA PNGs are supported")
            channels = 3 if color_type == 2 else 4
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    return width, height, channels, zlib.decompress(idat)


def unfilter(width, height, channels, raw):
    stride = width * channels
    prev = bytearray(stride)
    rows, pos = [], 0
    for _ in range(height):
        kind, line = raw[pos], bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up = prev[i]
            up_left = prev[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif kind == 4:
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                predictor = left if pa <= pb and pa <= pc else (up if pb <= pc else up_left)
                line[i] = (line[i] + predictor) & 0xFF
        rows.append(line)
        prev = line
    return rows


def mean_luminance(path, step=8):
    width, height, channels, raw = read_png(path)
    rows = unfilter(width, height, channels, raw)
    total, count = 0.0, 0
    for y in range(0, height, step):
        row = rows[y]
        for x in range(0, width, step):
            r, g, b = row[x * channels:x * channels + 3]
            total += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
            count += 1
    return total / count


if __name__ == "__main__":
    print(f"{mean_luminance(sys.argv[1]):.3f}")
