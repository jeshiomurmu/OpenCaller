"""
Equal error rate of the exported model on an evaluation subset, clean and through telephone codecs (codecs.py).

    python -m voice_spoof.evaluate --model model/aasist.onnx --data data/asvspoof2019 --out results

`--data` holds `protocol.txt` (lines: utterance attack label) and `flac/<utterance>.flac`, as written by
scripts/fetch_asvspoof2019_subset.py. Each utterance is scored the way the service sees a call: its first 4 s at
16 kHz (shorter ones repeated), after the condition's codecs.
"""

from __future__ import annotations

import argparse
import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .codecs import CONDITIONS, degrade
from .scorer import INPUT_SAMPLES, Scorer, fit


@dataclass(frozen=True)
class Utterance:
    name: str
    attack: str
    spoof: bool


def read_protocol(path: Path) -> list[Utterance]:
    rows = []
    for line in path.read_text().splitlines():
        parts = line.split()
        if len(parts) == 3:
            rows.append(Utterance(parts[0], parts[1], parts[2] != "bonafide"))
    return rows


def eer(bonafide: np.ndarray, spoof: np.ndarray) -> tuple[float, float]:
    """(EER, threshold) for scores where higher means spoof: the point where the false acceptance rate (spoof
    scored below the threshold) equals the false rejection rate (bona fide at or above it)."""
    if bonafide.size == 0 or spoof.size == 0:
        raise ValueError("need both bona fide and spoof scores")
    thresholds = np.unique(np.concatenate([bonafide, spoof]))
    far = np.array([(spoof < t).mean() for t in thresholds])
    frr = np.array([(bonafide >= t).mean() for t in thresholds])
    i = int(np.argmin(np.abs(far - frr)))
    return float((far[i] + frr[i]) / 2), float(thresholds[i])


def _load(path: Path) -> np.ndarray:
    import soundfile as sf

    audio, rate = sf.read(str(path), dtype="float32", always_2d=False)
    if rate != 16_000:
        raise ValueError(f"{path.name} is {rate} Hz, expected 16 kHz")
    return audio if audio.ndim == 1 else audio.mean(axis=1)


def score_condition(
    scorer: Scorer, data: Path, utterances: list[Utterance], condition: str, workers: int
) -> np.ndarray:
    def prepare(u: Utterance) -> np.ndarray:
        return fit(degrade(_load(data / "flac" / f"{u.name}.flac"), condition), INPUT_SAMPLES)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        clips = list(pool.map(prepare, utterances))
    return np.array([scorer.score(clip) for clip in clips])


def summarize(utterances: list[Utterance], scores: np.ndarray) -> dict:
    spoof = np.array([u.spoof for u in utterances])
    total, threshold = eer(scores[~spoof], scores[spoof])
    per_attack = {}
    for attack in sorted({u.attack for u in utterances if u.spoof}):
        mask = np.array([u.attack == attack for u in utterances])
        per_attack[attack] = eer(scores[~spoof], scores[mask])[0]
    return {
        "eer": total,
        "threshold": threshold,
        "bonafide": int((~spoof).sum()),
        "spoof": int(spoof.sum()),
        "per_attack": per_attack,
        # How the service's fixed 0.5 cut-off does on this condition.
        "at_0_5": {"frr": float((scores[~spoof] >= 0.5).mean()), "far": float((scores[spoof] < 0.5).mean())},
    }


def markdown(results: dict[str, dict]) -> str:
    lines = [
        "| Condition | Bona fide | Spoof | EER | At 0.5: bona fide flagged | At 0.5: spoof missed |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for condition, r in results.items():
        lines.append(
            f"| {condition} | {r['bonafide']} | {r['spoof']} | {r['eer'] * 100:.2f} % | "
            f"{r['at_0_5']['frr'] * 100:.1f} % | {r['at_0_5']['far'] * 100:.1f} % |"
        )
    attacks = sorted(next(iter(results.values()))["per_attack"])
    lines += ["", "| Attack | " + " | ".join(results) + " |", "| --- |" + " --- |" * len(results)]
    for attack in attacks:
        lines.append(
            f"| {attack} | " + " | ".join(f"{r['per_attack'][attack] * 100:.2f} %" for r in results.values()) + " |"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("results"))
    parser.add_argument("--conditions", nargs="+", default=list(CONDITIONS), choices=CONDITIONS)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--reuse", action="store_true", help="load a condition's saved scores instead of scoring again")
    args = parser.parse_args(argv)

    utterances = read_protocol(args.data / "protocol.txt")
    scorer = Scorer.load(args.model)
    results = {}
    args.out.mkdir(parents=True, exist_ok=True)
    for condition in args.conditions:
        saved = args.out / f"scores_{condition}.npy"
        if args.reuse and saved.exists():
            scores = np.load(saved)
        else:
            scores = score_condition(scorer, args.data, utterances, condition, args.workers)
        results[condition] = summarize(utterances, scores)
        np.save(args.out / f"scores_{condition}.npy", scores)
        print(f"{condition}: EER {results[condition]['eer'] * 100:.2f} %", flush=True)
    (args.out / "results.json").write_text(json.dumps(results, indent=2))
    (args.out / "results.md").write_text(markdown(results))
    print(markdown(results))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
