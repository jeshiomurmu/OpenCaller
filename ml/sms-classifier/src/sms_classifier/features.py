"""Hashed character n-gram features: the model's input. The app must compute the same vector (FEATURE_VERSION).

Text is NFKC-normalised and lower-cased, every digit (ASCII, Bengali, Devanagari or any other Unicode decimal digit)
becomes "0" so codes and amounts don't become vocabulary, and runs of whitespace collapse to one space. The vector
counts the character 2-, 3- and 4-grams of " " + text + " " in BUCKETS buckets by FNV-1a (32-bit) of each gram's UTF-8
bytes, then applies log(1 + count).
"""

import math
import unicodedata

FEATURE_VERSION = 1
BUCKETS = 4096
NGRAMS = (2, 3, 4)
_FNV_OFFSET = 0x811C9DC5
_FNV_PRIME = 0x01000193


def normalise(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    text = "".join("0" if ch.isdecimal() else ch for ch in text)
    return " ".join(text.split())


def fnv1a(data: bytes) -> int:
    value = _FNV_OFFSET
    for byte in data:
        value ^= byte
        value = (value * _FNV_PRIME) & 0xFFFFFFFF
    return value


def vector(text: str) -> list[float]:
    padded = f" {normalise(text)} "
    counts = [0] * BUCKETS
    for n in NGRAMS:
        for i in range(len(padded) - n + 1):
            counts[fnv1a(padded[i:i + n].encode("utf-8")) % BUCKETS] += 1
    return [math.log1p(count) for count in counts]
