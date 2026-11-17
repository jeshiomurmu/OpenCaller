#!/usr/bin/env python3
"""
Checks every translated strings.xml (values-bn, values-hi, …) against its English values/strings.xml (Phase 14,
docs/testing.md → Localization). Android Lint catches missing strings and broken format arguments in CI; this runs
in a second and also catches what lint lets through:

  - every translatable English string, plural and array is translated, and nothing else is (no stale names);
  - the same format placeholders (%1$s, %d, %%, …) as the English text, in any order;
  - plurals have `one` and `other`, and `one` keeps its number (Hindi and Bengali use `one` for 0 and 1 too);
  - no unescaped apostrophe or double quote (aapt2 would drop the text after it), no stray `\\` escape;
  - the file says it is awaiting a native speaker's review (the Play release waits for it, docs/phase.md).

Usage: check_translations.py [android dir] (default: android). Exit 1 on any problem.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc%]")
REVIEW_MARK = "NEEDS NATIVE REVIEW"
LOCALES = ("bn", "hi")


def entries(root: ET.Element) -> dict[str, tuple[str, dict[str, str]]]:
    """name -> (tag, {quantity or index or "": raw text})"""
    out = {}
    for element in root:
        if not isinstance(element.tag, str) or element.get("translatable") == "false":
            continue
        name = element.get("name")
        if element.tag == "string":
            out[name] = ("string", {"": "".join(element.itertext())})
        elif element.tag == "plurals":
            out[name] = ("plurals", {item.get("quantity"): "".join(item.itertext()) for item in element})
        elif element.tag == "string-array":
            out[name] = ("string-array", {str(i): "".join(item.itertext()) for i, item in enumerate(element)})
    return out


def placeholders(text: str) -> list[str]:
    return sorted(p for p in PLACEHOLDER.findall(text) if p != "%%")


def bad_escapes(text: str) -> list[str]:
    problems = []
    unescaped = re.sub(r"\\.", "", text)
    if text.startswith('"') and text.endswith('"') and len(text) >= 2:
        unescaped = unescaped[1:-1].replace("'", "")
    if "'" in unescaped:
        problems.append("unescaped apostrophe (write \\')")
    if '"' in unescaped:
        problems.append('unescaped double quote (write \\")')
    for escape in re.findall(r"\\(.)", text):
        if escape not in "'\"nt@?\\u":
            problems.append(f"unknown escape \\{escape}")
    return problems


def check(android: Path) -> list[str]:
    problems: list[str] = []
    for english in sorted(android.glob("**/src/main/res/values/strings.xml")):
        if "/build/" in str(english):
            continue
        source = entries(ET.parse(english).getroot())
        for locale in LOCALES:
            translated_file = english.parent.parent / f"values-{locale}" / "strings.xml"
            where = translated_file.relative_to(android)
            if not translated_file.exists():
                problems.append(f"{where}: missing")
                continue
            raw = translated_file.read_text(encoding="utf-8")
            if REVIEW_MARK not in raw:
                problems.append(f"{where}: no '{REVIEW_MARK}' note")
            try:
                translated = entries(ET.fromstring(raw))
            except ET.ParseError as e:
                problems.append(f"{where}: not valid XML ({e})")
                continue
            for name in sorted(set(source) - set(translated)):
                problems.append(f"{where}: {name} not translated")
            for name in sorted(set(translated) - set(source)):
                problems.append(f"{where}: {name} isn't in the English file")
            for name in sorted(set(source) & set(translated)):
                tag, texts = source[name]
                other_tag, other_texts = translated[name]
                if tag != other_tag:
                    problems.append(f"{where}: {name} is a {other_tag}, English has a {tag}")
                    continue
                if tag == "plurals":
                    if {"one", "other"} - set(other_texts):
                        problems.append(f"{where}: {name} needs 'one' and 'other'")
                    reference = placeholders(texts.get("other", ""))
                    for quantity, text in other_texts.items():
                        if placeholders(text) != reference:
                            problems.append(f"{where}: {name}[{quantity}] placeholders {placeholders(text)} != {reference}")
                elif tag == "string-array" and len(texts) != len(other_texts):
                    problems.append(f"{where}: {name} has {len(other_texts)} items, English {len(texts)}")
                else:
                    for key, text in other_texts.items():
                        if placeholders(text) != placeholders(texts.get(key, "")):
                            problems.append(
                                f"{where}: {name} placeholders {placeholders(text)} != {placeholders(texts.get(key, ''))}"
                            )
                for text in other_texts.values():
                    problems.extend(f"{where}: {name}: {p}" for p in bad_escapes(text))
                    if not text.strip():
                        problems.append(f"{where}: {name} is empty")
    return problems


def main() -> int:
    android = Path(sys.argv[1] if len(sys.argv) > 1 else "android")
    problems = check(android)
    for problem in problems:
        print(problem)
    count = sum(1 for _ in android.glob("**/src/main/res/values-*/strings.xml"))
    print(f"{count} translated files checked, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
