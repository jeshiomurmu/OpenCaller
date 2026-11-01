"""Reads the SMS corpus shared with the app's tests (android/core/testing/.../fixtures/sms_corpus)."""

import csv
import json
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DIR = Path(__file__).resolve().parents[4] / "android/core/testing/src/main/resources/fixtures/sms_corpus"
SPLITS = ("train", "val", "test")


@dataclass(frozen=True)
class Message:
    id: str
    template: str
    country: str
    lang: str
    category: str
    sender: str
    otp: str
    origin: str
    app_hash: str
    expiry_min: str
    amount: str
    currency: str
    last4: str
    merchant: str
    text: str


def unescape(value: str) -> str:
    """Undoes the TSV escaping of the generator: "\\n" is a line break, "\\\\" a backslash."""
    out, i = [], 0
    while i < len(value):
        if value[i] == "\\" and i + 1 < len(value):
            out.append("\n" if value[i + 1] == "n" else value[i + 1])
            i += 2
        else:
            out.append(value[i])
            i += 1
    return "".join(out)


def load(split: str, directory: Path = DEFAULT_DIR) -> list[Message]:
    """The messages of [split] (train, val or test)."""
    if split not in SPLITS:
        raise ValueError(f"unknown split {split}")
    with open(directory / f"{split}.tsv", encoding="utf-8", newline="") as handle:
        rows = csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE)
        return [Message(**{key: unescape(value) for key, value in row.items()}) for row in rows]


def kind(directory: Path = DEFAULT_DIR) -> str:
    """"synthetic" for the generated corpus; a corpus of collected messages says "collected"."""
    return json.loads((directory / "corpus.json").read_text(encoding="utf-8"))["kind"]
