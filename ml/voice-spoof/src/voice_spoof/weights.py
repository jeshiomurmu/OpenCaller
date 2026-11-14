"""
AASIST's pretrained weights (NAVER, MIT licence, trained on ASVspoof 2019 LA). The MIT licence allows redistribution,
but the weights are fetched from the authors' repository at build time and checked against a pinned SHA-256, so the
OpenCaller repository carries no binary model.
"""

from __future__ import annotations

import hashlib
import ssl
import urllib.request
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Weights:
    name: str
    url: str
    sha256: str


# The files in https://github.com/clovaai/aasist/tree/main/models/weights (read 2026-11-14).
AASIST = Weights(
    name="AASIST",
    url="https://raw.githubusercontent.com/clovaai/aasist/main/models/weights/AASIST.pth",
    sha256="51d2d9cf0738172f61e2a384ec50a54a55363240f67c971ed55a92435bc1a1c0",
)

# AASIST's model_config from config/AASIST.conf of the same repository.
AASIST_CONFIG = {
    "architecture": "AASIST",
    "nb_samp": 64600,
    "first_conv": 128,
    "filts": [70, [1, 32], [32, 32], [32, 64], [64, 64]],
    "gat_dims": [64, 32],
    "pool_ratios": [0.5, 0.7, 0.5, 0.5],
    "temperatures": [2.0, 2.0, 100.0, 100.0],
}


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(weights: Weights, target: Path, cafile: str | None = None) -> Path:
    """Downloads [weights] to [target] unless it is already there; raises if the checksum doesn't match."""
    if not target.exists():
        context = ssl.create_default_context(cafile=cafile) if cafile else None
        target.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(weights.url, context=context, timeout=120) as response:
            target.write_bytes(response.read())
    actual = sha256_of(target)
    if actual != weights.sha256:
        target.unlink()
        raise ValueError(f"{weights.name} weights have SHA-256 {actual}, expected {weights.sha256}")
    return target
