#!/usr/bin/env python3
"""
Dependency audit (docs/security.md, MASVS-CODE-3): asks OSV (https://osv.dev, POST /v1/querybatch) about every
resolved dependency of the app, the server and the Python services, and fails on a known vulnerability that isn't
in docs/security/osv-ignore.txt with a reason.

Inputs (written by the CI job):
  --gradle FILE   output of `gradlew <module>:dependencies --configuration <runtime classpath>` (repeatable)
  --pip FILE      a `pip install --dry-run --report` JSON (repeatable)

Standard library only. Exit 0: nothing open; 1: open vulnerabilities; 2: OSV unreachable or bad input.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

OSV = "https://api.osv.dev/v1"
BATCH = 500  # OSV accepts up to 1,000 queries per batch
# "+--- group:artifact:1.0", "group:artifact:1.0 -> 1.1 (*)", "group:artifact -> 1.1" (version from a BOM),
# "group:artifact:1.0 -> other.group:other-artifact:2.0" (a substitution: the right-hand module is what ships)
GRADLE_LINE = re.compile(
    r"[+\\]--- ([\w.\-]+):([\w.\-]+)(?::([\w.\-+]+))?"
    r"(?: -> (?:([\w.\-]+):([\w.\-]+):)?([\w.\-+]+))?"
)


def gradle_packages(text: str) -> set[tuple[str, str, str]]:
    found = set()
    for line in text.splitlines():
        match = GRADLE_LINE.search(line)
        if not match or line.strip().endswith("FAILED"):
            continue
        group, artifact, declared, new_group, new_artifact, resolved = match.groups()
        if new_group:
            group, artifact = new_group, new_artifact
        version = resolved or declared
        if version:
            found.add(("Maven", f"{group}:{artifact}", version))
    return found


def pip_packages(report: dict) -> set[tuple[str, str, str]]:
    return {("PyPI", item["metadata"]["name"], item["metadata"]["version"]) for item in report.get("install", [])}


def post(path: str, body: dict) -> dict:
    request = urllib.request.Request(
        f"{OSV}/{path}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def get(path: str) -> dict:
    with urllib.request.urlopen(f"{OSV}/{path}", timeout=60) as response:
        return json.load(response)


def ignored(path: Path) -> dict[str, str]:
    """`ID  reason` per line; a line without a reason is an error (every exception must say why)."""
    entries = {}
    if not path.exists():
        return entries
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split(None, 1)
        if len(parts) < 2:
            sys.exit(f"{path}:{number}: {parts[0]} has no reason")
        entries[parts[0]] = parts[1]
    return entries


def fixed_versions(details: dict) -> list[str]:
    """`package fixed-version` for each range in the advisory, so an open finding says what to upgrade to."""
    fixed = []
    for affected in details.get("affected", []):
        name = affected.get("package", {}).get("name", "?")
        for event in (e for r in affected.get("ranges", []) for e in r.get("events", [])):
            if "fixed" in event:
                fixed.append(f"{name} {event['fixed']}")
    return sorted(set(fixed))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--gradle", action="append", default=[], type=Path)
    parser.add_argument("--pip", action="append", default=[], type=Path)
    parser.add_argument("--ignore", type=Path, default=Path("docs/security/osv-ignore.txt"))
    args = parser.parse_args()

    packages: set[tuple[str, str, str]] = set()
    for file in args.gradle:
        found = gradle_packages(file.read_text())
        if not found:
            print(f"No dependencies found in {file}", file=sys.stderr)
            return 2
        packages |= found
    for file in args.pip:
        packages |= pip_packages(json.loads(file.read_text()))
    ordered = sorted(packages)
    print(f"Checking {len(ordered)} packages with OSV")

    hits: dict[str, set[str]] = {}
    try:
        for start in range(0, len(ordered), BATCH):
            chunk = ordered[start : start + BATCH]
            queries = [{"package": {"ecosystem": e, "name": n}, "version": v} for e, n, v in chunk]
            results = post("querybatch", {"queries": queries})["results"]
            for (ecosystem, name, version), result in zip(chunk, results, strict=True):
                for vuln in result.get("vulns", []):
                    hits.setdefault(vuln["id"], set()).add(f"{ecosystem} {name} {version}")
    except (urllib.error.URLError, KeyError, ValueError) as e:
        print(f"OSV query failed: {e}", file=sys.stderr)
        return 2

    exceptions = ignored(args.ignore)
    open_count = 0
    for vuln_id in sorted(hits):
        try:
            details = get(f"vulns/{vuln_id}")
        except (urllib.error.URLError, ValueError):
            details = {}
        aliases = set(details.get("aliases", []))
        reason = next((exceptions[i] for i in {vuln_id, *aliases} if i in exceptions), None)
        status = f"ignored ({reason})" if reason else "OPEN"
        if not reason:
            open_count += 1
        print(f"{status}: {vuln_id} {details.get('summary', '')}".rstrip())
        fixed = fixed_versions(details)
        if fixed:
            print(f"    fixed in: {', '.join(fixed)}")
        for package in sorted(hits[vuln_id]):
            print(f"    {package}")
    print(f"{len(hits)} known vulnerabilities, {open_count} open")
    return 1 if open_count else 0


if __name__ == "__main__":
    sys.exit(main())
