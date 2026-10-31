"""Trains the classifier and exports an int8 LiteRT model, but only on a corpus that can support one.

The gate: the corpus must be collected (not synthetic) and every label needs at least --min-per-class training
messages. The generated corpus in this repository fails both, so this command stops there and the app ships its
rules-only classifier (docs/phase.md, Open issues). --allow-synthetic trains anyway for experiments; such a model is
written to --out but must never be copied into the app's assets.
"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

from . import LABELS
from .corpus import DEFAULT_DIR, Message, kind, load
from .features import BUCKETS, FEATURE_VERSION, vector

MIN_PER_CLASS = 300


def gate(train: list[Message], corpus_kind: str, min_per_class: int, allow_synthetic: bool) -> list[str]:
    """The reasons not to train; empty when training may go ahead."""
    problems = []
    if corpus_kind != "collected" and not allow_synthetic:
        problems.append(f"the corpus is {corpus_kind}, not collected messages")
    counts = Counter(message.category for message in train)
    for label in LABELS:
        if counts[label] < min_per_class:
            problems.append(f"{label}: {counts[label]} training messages, need {min_per_class}")
    return problems


def macro_f1(truth: list[str], predicted: list[str]) -> float:
    scores = []
    for label in LABELS:
        tp = sum(1 for t, p in zip(truth, predicted) if t == p == label)
        fp = sum(1 for t, p in zip(truth, predicted) if p == label and t != label)
        fn = sum(1 for t, p in zip(truth, predicted) if t == label and p != label)
        if tp + fp + fn == 0:
            continue
        scores.append(2 * tp / (2 * tp + fp + fn))
    return sum(scores) / len(scores) if scores else 0.0


def train_and_export(train: list[Message], val: list[Message], out: Path) -> dict:  # pragma: no cover - needs TensorFlow
    import numpy as np
    import tensorflow as tf

    def matrix(messages):
        return np.array([vector(message.text) for message in messages], dtype=np.float32)

    x_train, x_val = matrix(train), matrix(val)
    y_train = np.array([LABELS.index(message.category) for message in train])
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(BUCKETS,)),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(len(LABELS), activation="softmax"),
    ])
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(x_train, y_train, epochs=20, batch_size=32, verbose=2)
    predicted = [LABELS[i] for i in model.predict(x_val, verbose=0).argmax(axis=1)]
    f1 = macro_f1([message.category for message in val], predicted)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: ([row[np.newaxis, :]] for row in x_train[:300])
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8
    out.mkdir(parents=True, exist_ok=True)
    (out / "sms_classifier.tflite").write_bytes(converter.convert())
    meta = {"labels": list(LABELS), "feature_version": FEATURE_VERSION, "buckets": BUCKETS, "val_macro_f1": round(f1, 4)}
    (out / "sms_classifier.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    return meta


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_DIR)
    parser.add_argument("--out", type=Path, default=Path("build"))
    parser.add_argument("--min-per-class", type=int, default=MIN_PER_CLASS)
    parser.add_argument("--allow-synthetic", action="store_true")
    args = parser.parse_args(argv)
    train = load("train", args.corpus)
    problems = gate(train, kind(args.corpus), args.min_per_class, args.allow_synthetic)
    if problems:
        print("Not training:", *problems, sep="\n  ", file=sys.stderr)
        return 2
    meta = train_and_export(train, load("val", args.corpus), args.out)
    print(json.dumps(meta))
    return 0


if __name__ == "__main__":
    sys.exit(main())
