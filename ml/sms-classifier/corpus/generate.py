"""Generates the OpenCaller SMS corpus from corpus/templates.py.

Writes train.tsv, val.tsv and test.tsv to android/core/testing/src/main/resources/fixtures/sms_corpus/ (or the
directory given as the first argument). Every template goes to exactly one split, so the test split holds message
formats that were not seen while writing the extractors and rules. The output is deterministic (fixed seed).

Run: python -I ml/sms-classifier/corpus/generate.py
"""

import json
import random
import string
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from templates import ALL, Template  # noqa: E402

COLUMNS = [
    "id", "template", "country", "lang", "category", "sender", "otp", "origin", "app_hash", "expiry_min",
    "amount", "currency", "last4", "merchant", "text",
]
SPLITS = ("train", "train", "val", "test")
SAMPLES = {"OTP": 8}
DEFAULT_SAMPLES = 6
SEED = 20261031

MERCHANTS = {
    "US": ["Corner Grocer", "Maple Fuel", "Bluebird Cafe", "Pixel Mart", "Harbor Books"],
    "IN": ["Sharma Kirana", "Metro Fuels", "Chai Point Cafe", "Digital Bazaar", "Lotus Pharmacy"],
    "BD": ["Shwapno Mart", "Aarong Craft", "Dhaka Fuel", "Bengal Books", "Mina Bazar"],
}
NAMES = {"US": ["Sam", "Alex", "Jordan", "Riley"], "IN": ["Rahul", "Priya", "Anil", "Kavya"], "BD": ["Rafi", "Nusrat", "Tanvir", "Mim"]}
PHONES = {"US": "+1 555-01{:02d}", "IN": "+91 95550 12{:03d}", "BD": "+880 1555-012{:03d}"}
URLS = ["https://shopnest.example/t/{}", "https://ridely.example/r/{}", "https://glimmer.example/s/{}"]
# Lookalike and throwaway domains used by the phishing templates (none of them resolves).
PHISH = [
    "https://northpeak-bank.verify-login.top/{}", "http://nоrthpeak.example/secure",  # Cyrillic "о"
    "https://bit.ly/3x{}", "http://sbi-kyc-update.click/{}", "https://bkash-verify.xyz/{}",
    "http://paypa1-secure.example/{}", "https://swiftparce1.example/fee/{}", "http://dbbl-card.support/{}",
]
BENGALI_DIGITS = str.maketrans("0123456789", "০১২৩৪৫৬৭৮৯")
DEVANAGARI_DIGITS = str.maketrans("0123456789", "०१२३४५६७८९")
HASH_ALPHABET = string.ascii_letters + string.digits + "+/"


def amount(rng: random.Random, country: str) -> tuple:
    value = rng.choice([rng.randint(1, 99) + rng.randint(0, 99) / 100, rng.randint(100, 9999) + rng.randint(0, 99) / 100, float(rng.randint(100, 60000))])
    plain = f"{value:.2f}"
    if country == "US":
        shown = f"{value:,.2f}"
    elif rng.random() < 0.5:
        shown = f"{value:,.2f}"
    else:
        shown = plain if value % 1 else str(int(value))
    return shown, plain


def fill(template: Template, rng: random.Random, n: int) -> dict:
    country = template.country
    code = "".join(rng.choice(string.digits) for _ in range(template.digits))
    if code[0] == "0":
        code = str(rng.randint(1, 9)) + code[1:]
    minutes = str(rng.choice([2, 3, 5, 10, 15, 30]))
    shown_amount, plain_amount = amount(rng, country)
    shown_balance, _ = amount(rng, country)
    values = {
        "code": code,
        "amt": shown_amount,
        "bal": shown_balance,
        "last4": "".join(rng.choice(string.digits) for _ in range(4)),
        "merchant": rng.choice(MERCHANTS[country]),
        "date": f"{rng.randint(1, 28):02d}-{rng.choice(['Oct', 'Nov', 'Dec'])}-26",
        "time": f"{rng.randint(1, 12)}:{rng.randint(0, 59):02d} PM",
        "order": str(rng.randint(10_000_000, 99_999_999)),
        "awb": "".join(rng.choice(string.ascii_uppercase) for _ in range(2)) + str(rng.randint(100_000_000, 999_999_999)),
        "phone": PHONES[country].format(rng.randint(0, 99)),
        "hash": "".join(rng.choice(HASH_ALPHABET) for _ in range(11)),
        "min": minutes,
        "name": rng.choice(NAMES[country]),
        "url": rng.choice(URLS).format(rng.randint(1000, 9999)),
        "phish": rng.choice(PHISH).format(rng.randint(1000, 9999)),
        "pct": str(rng.choice([10, 20, 25, 30, 40, 50])),
    }
    shown = dict(values)
    # Messages in Bengali or Hindi sometimes write the code and the minutes in their own digits.
    if template.lang == "bn" and rng.random() < 0.5:
        shown["code"] = code.translate(BENGALI_DIGITS)
        shown["min"] = minutes.translate(BENGALI_DIGITS)
    elif template.lang == "hi" and rng.random() < 0.3:
        shown["code"] = code.translate(DEVANAGARI_DIGITS)
        shown["min"] = minutes.translate(DEVANAGARI_DIGITS)
    # The domain-bound last line always uses ASCII digits (the format requires them).
    text = template.text.replace("#{code}", "#" + code).format(**shown)
    origin = ""
    if template.origin:
        origin = text.rstrip().splitlines()[-1].split()[0].lstrip("@")
    currency, has_merchant = template.txn if template.txn else ("", False)
    return {
        "id": f"{template.id}-{n:02d}",
        "template": template.id,
        "country": country,
        "lang": template.lang,
        "category": template.category,
        "sender": template.sender,
        "otp": code if template.otp else "",
        "origin": origin,
        "app_hash": values["hash"] if template.app_hash else "",
        "expiry_min": minutes if template.expiry else "",
        "amount": plain_amount if template.txn else "",
        "currency": currency,
        "last4": values["last4"] if template.txn and "{last4}" in template.text else "",
        "merchant": values["merchant"] if has_merchant else "",
        "text": text,
    }


def split_of(template: Template, index_in_category: int) -> str:
    return SPLITS[index_in_category % len(SPLITS)]


def escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ")


def main(out: Path) -> None:
    rng = random.Random(SEED)
    rows = {"train": [], "val": [], "test": []}
    seen = {}
    for template in ALL:
        index = seen.get(template.category, 0)
        seen[template.category] = index + 1
        split = split_of(template, index)
        for n in range(SAMPLES.get(template.category, DEFAULT_SAMPLES)):
            rows[split].append(fill(template, rng, n))
    out.mkdir(parents=True, exist_ok=True)
    for split, items in rows.items():
        lines = ["\t".join(COLUMNS)] + ["\t".join(escape(row[column]) for column in COLUMNS) for row in items]
        (out / f"{split}.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{split}: {len(items)} messages, {sum(1 for row in items if row['otp'])} with a code")
    # What kind of corpus this is: training refuses a synthetic corpus unless asked to (see README.md).
    meta = {"kind": "synthetic", "generator": "ml/sms-classifier/corpus/generate.py", "seed": SEED, "columns": COLUMNS}
    (out / "corpus.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    default = Path(__file__).resolve().parents[3] / "android/core/testing/src/main/resources/fixtures/sms_corpus"
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else default)
