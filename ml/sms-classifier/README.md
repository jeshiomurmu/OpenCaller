# SMS classifier (`ml/sms-classifier`)

Trains OpenCaller's on-device SMS classifier and exports it to LiteRT (`.tflite`, int8). The app sorts messages into
Personal, OTP, Transaction, Bill, Delivery, Promotion, Spam and Phishing (`docs/architecture.md` §4.4 step 4).

**Status: no model ships yet.** The only corpus is a generated one (below). It is too small and isn't made of real
messages, so the training command refuses to run on it, and the app uses its rules-only classifier
(`core:ml` `RuleSmsClassifier`). The gap is recorded in `docs/phase.md` → Open issues.

## Corpus

| Source | What it is | Licence | Size |
| --- | --- | --- | --- |
| `corpus/templates.py` + `corpus/generate.py` (this repository) | Hand-written templates that imitate the shape of common SMS in Bangladesh, India and the United States (where the code sits, keywords, sender IDs, amount and card formats), filled with made-up values by a seeded generator | GPL-3.0-only, like the rest of the repository | 846 messages: 454 train, 202 val, 190 test; 360 carry a one-time code (120 each for BD, IN and US; English, Bengali and Hindi) |

No collected or third-party messages are included, and no real person's message is in it: codes, amounts, card
digits, order numbers, names, phone numbers (fictional ranges) and links are invented, and most brands are invented
too. The banks named in the transaction templates are real, but those formats are approximations written for this
project, not copies of real messages; confirm them against real messages before relying on them.

The files live in `android/core/testing/src/main/resources/fixtures/sms_corpus/` (`train.tsv`, `val.tsv`, `test.tsv`,
`corpus.json` with `"kind": "synthetic"`), so the app's extractor and classifier tests read the same data. Columns:
`id, template, country, lang, category, sender, otp, origin, app_hash, expiry_min, amount, currency, last4, merchant,
text` (tab-separated; `\n` in `text` is a line break). Each template goes to exactly one split, so the test split holds
formats that weren't seen while writing the rules. Because the same person wrote the corpus, the extractor and the
rules, scores on it are optimistic: they show the code does what it was written to do, not how it does on real SMS.

Regenerate after changing a template: `python3 -I ml/sms-classifier/corpus/generate.py`.

## Before a model can ship

1. A collected corpus: real messages, donated with consent (an in-app "share this message to improve filtering"
   flow, never automatic: `docs/architecture.md` §10), anonymised on the device before upload, with a licence that
   allows training and redistribution of the model. Its `corpus.json` says `"kind": "collected"`.
2. At least `--min-per-class` (default 300) training messages per label, from all three countries.
3. The app side: the LiteRT runtime dependency (artifact and version verified on Google Maven), loading
   `sms_classifier.tflite` from assets, and the same features as `features.py` (version 1, test vectors in
   `tests/test_corpus.py`).

## Model (when trained)

- Input: 4,096 hashed character 2–4-gram features (`features.py`, `FEATURE_VERSION = 1`): NFKC, lower case, every
  Unicode digit → `0`, whitespace collapsed, FNV-1a 32-bit of each gram's UTF-8 bytes modulo 4,096, `log(1 + count)`.
- Network: dense 64 (ReLU), dropout 0.2, dense 8 (softmax).
- Export: `TFLiteConverter` with full-integer quantisation (int8 inputs and outputs, a representative dataset of
  300 training rows); `sms_classifier.json` holds the label order, the feature version and the validation macro-F1.

## Commands

```bash
cd ml/sms-classifier
python3 -m venv .venv && . .venv/bin/activate
pip install -e '.[test]'          # pytest 9.2.0
pytest                            # corpus, features, training gate
pip install -e '.[train]'         # tensorflow 2.21.0, numpy 2.5.4 (Python 3.12+)
sms-classifier-train --out build  # refuses the generated corpus; --allow-synthetic only for experiments
```

A model trained with `--allow-synthetic` must never be copied into the app's assets.
