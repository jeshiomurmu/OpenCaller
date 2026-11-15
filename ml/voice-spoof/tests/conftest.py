"""A tiny ONNX model with AASIST's interface (audio [N, 64600] → logits [N, 2]) for the tests."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest


def tiny_model(path: Path) -> Path:
    """logits = [10·mean(audio), −10·mean(audio)]: positive audio scores as spoof, negative as bona fide."""
    import onnx
    from onnx import TensorProto, helper

    scale = helper.make_tensor("scale", TensorProto.FLOAT, [1, 2], [10.0, -10.0])
    nodes = [
        helper.make_node("ReduceMean", ["audio"], ["mean"], keepdims=1, axes=[1]),
        helper.make_node("Mul", ["mean", "scale"], ["logits"]),
    ]
    graph = helper.make_graph(
        nodes,
        "tiny",
        [helper.make_tensor_value_info("audio", TensorProto.FLOAT, ["batch", 64600])],
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["batch", 2])],
        initializer=[scale],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    model.ir_version = 8
    onnx.save(model, str(path))
    return path


@pytest.fixture
def model_path(tmp_path: Path) -> Path:
    return tiny_model(tmp_path / "tiny.onnx")


@pytest.fixture
def speech() -> np.ndarray:
    rng = np.random.default_rng(1)
    return (rng.standard_normal(64_000) * 0.1).astype(np.float32)
