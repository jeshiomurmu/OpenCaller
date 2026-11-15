"""
Exports AASIST to ONNX (needs the `export` extra: torch, onnx, onnxscript). The service and the evaluation run the
ONNX file with onnxruntime, so the image needs no PyTorch.

    python -m voice_spoof.export --out model/aasist.onnx
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import numpy as np

from .weights import AASIST, AASIST_CONFIG, fetch

SAMPLES = AASIST_CONFIG["nb_samp"]


def export(weights_path: Path, out: Path) -> Path:
    import torch

    from .aasist import Model

    model = Model(AASIST_CONFIG)
    model.load_state_dict(torch.load(weights_path, map_location="cpu", weights_only=True))
    model.eval()

    class Logits(torch.nn.Module):
        """Only the two class logits (spoof, bona fide); AASIST's forward also returns its hidden embedding."""

        def __init__(self, inner: torch.nn.Module) -> None:
            super().__init__()
            self.inner = inner

        def forward(self, audio: torch.Tensor) -> torch.Tensor:
            return self.inner(audio)[1]

    wrapper = Logits(model).eval()
    example = torch.from_numpy(np.random.default_rng(0).standard_normal((1, SAMPLES)).astype(np.float32) * 0.05)
    out.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (example,),
            str(out),
            input_names=["audio"],
            output_names=["logits"],
            dynamic_axes={"audio": {0: "batch"}, "logits": {0: "batch"}},
            opset_version=17,
            dynamo=False,
        )
        expected = wrapper(example).numpy()
    _check(out, example.numpy(), expected)
    return out


def _check(onnx_path: Path, audio: np.ndarray, expected: np.ndarray) -> None:
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    actual = session.run(["logits"], {"audio": audio})[0]
    if not np.allclose(actual, expected, atol=1e-3):
        raise ValueError(f"ONNX output {actual} differs from PyTorch's {expected}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights", type=Path, default=Path("model/AASIST.pth"))
    parser.add_argument("--out", type=Path, default=Path("model/aasist.onnx"))
    args = parser.parse_args(argv)
    fetch(AASIST, args.weights, os.environ.get("SSL_CERT_FILE"))
    print(export(args.weights, args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
