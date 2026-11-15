# voice-spoof

The voice scanner's anti-spoofing service (docs/prd.md F-PR-13, ADR 0011). It scores 4 s of a caller's voice for
signs of a generated or cloned voice. The `scanner` agent in `backend/agents` calls it; nothing else should, and it
is never exposed outside the operator's machine.

## Model and licence

- **Model:** AASIST (Jung et al., "AASIST: Audio Anti-Spoofing using Integrated Spectro-Temporal Graph Attention
  Networks", ICASSP 2022), the authors' implementation `clovaai/aasist`. `src/voice_spoof/aasist.py` is their
  `models/AASIST.py`, unchanged, under the MIT licence (`LICENSE-AASIST`, Copyright (c) 2021-present NAVER Corp.).
- **Weights:** `models/weights/AASIST.pth` of the same repository, trained on ASVspoof 2019 LA. MIT allows
  redistribution, but this repository carries no weights: they are downloaded when the image is built (or by
  `voice-spoof-export`) and checked against the SHA-256 pinned in `src/voice_spoof/weights.py`
  (`51d2d9cf0738172f61e2a384ec50a54a55363240f67c971ed55a92435bc1a1c0`). A download with another checksum is deleted
  and the build stops.
- **Runtime:** the weights are exported to ONNX (opset 17) and run with onnxruntime on the CPU; PyTorch is needed only
  for the export (the image's first stage, PyTorch's CPU wheels). Scoring a 4 s window took about 0.1 s on the
  4-core machine the evaluation ran on.

### Getting the weights by hand (self-hosters)

```shell
python3 -m venv .venv
.venv/bin/pip install -e '.[export]'          # PyTorch; on Linux without a GPU prefer:
# .venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0 && .venv/bin/pip install -e '.[export]'
.venv/bin/voice-spoof-export --weights model/AASIST.pth --out model/aasist.onnx
```

The export downloads `AASIST.pth`, checks it, writes `model/aasist.onnx` and compares the ONNX model's output with
PyTorch's on random audio. Behind a TLS proxy set `SSL_CERT_FILE`.

## API

Bearer token `VOICE_SPOOF_TOKEN` (32+ characters, shared with the scanner agent).

`POST /v1/score` with exactly 4 s of 16 kHz mono audio (64,000 samples):

- `Content-Type: application/octet-stream` or `audio/L16` (optional `rate=16000`): 16-bit little-endian PCM,
  128,000 bytes;
- `Content-Type: audio/wav`: a 16 kHz mono WAV of 64,000 samples.

```json
{ "spoofProbability": 0.0213, "model": "AASIST", "seconds": 4.0 }
```

`spoofProbability` is the softmax of the model's spoof class (0–1). It is **not calibrated**: 0.5 is simply where
the two classes meet, and the app reads it as "Possibly AI-generated" from 0.5 up and "Likely human" below, always
with the sentence that the check can be wrong. Errors: 400 (wrong length, rate or channels, unreadable WAV), 401,
413 (body too large), 415 (other content types). `GET /health` needs no token.

Run it: `docker build -t opencaller/voice-spoof ml/voice-spoof` (or the `scanner` profile of
`infra/docker/compose.telephony.yml`), or locally `VOICE_SPOOF_MODEL=model/aasist.onnx VOICE_SPOOF_TOKEN=… uvicorn
voice_spoof.service:app --port 8090`.

## Evaluation

`voice-spoof-evaluate` scores a labelled set clean and through telephone codecs (`src/voice_spoof/codecs.py`, ffmpeg)
and reports the equal error rate (EER: the threshold where real voices flagged as fake and fake voices missed are
equally frequent), the EER per attack, and the two error rates at the service's fixed 0.5.

- **Data:** ASVspoof 2019 LA (Wang et al., University of Edinburgh, https://datashare.ed.ac.uk/handle/10283/3336,
  ODC-By 1.0), evaluation partition (`ASVspoof2019.LA.cm.eval.trl.txt`): a stratified, deterministic subset of 1,000
  bona fide utterances and 100 of each of the 13 attacks A07–A19 (2,300 of 71,237), downloaded with
  `scripts/fetch_asvspoof2019_subset.py` (HTTP range requests; the whole archive is 7.6 GB). The data is not in the
  repository.
- **Conditions:** `clean` (16 kHz as published); `g711` (8 kHz G.711 µ-law, the PSTN and SIP trunk codec); `gsm`
  (8 kHz GSM-FR 06.10, older mobile networks); `g711_opus` (G.711, then Opus 24 kbit/s: Twilio → LiveKit SIP → WebRTC,
  this stack's path to the scanner). AMR-NB/WB, the codecs of today's mobile networks, aren't available in ffmpeg's
  default builds and are not covered. Each clip is cut or repeated to 4 s, as the scanner sends it.
- **Run:**

  ```shell
  python -I scripts/fetch_asvspoof2019_subset.py --out data/asvspoof2019
  voice-spoof-evaluate --model model/aasist.onnx --data data/asvspoof2019 --out results
  ```

### Results (2026-11-14, AASIST.pth above, onnxruntime 1.32.0, ffmpeg 6.1.1)

| Condition | Bona fide | Spoof | EER | At 0.5: bona fide flagged | At 0.5: spoof missed |
| --- | --- | --- | --- | --- | --- |
| clean | 1000 | 1300 | 0.78 % | 0.6 % | 5.5 % |
| g711 | 1000 | 1300 | 1.09 % | 1.1 % | 1.3 % |
| gsm | 1000 | 1300 | 6.61 % | 31.8 % | 0.1 % |
| g711_opus | 1000 | 1300 | 1.30 % | 1.7 % | 0.6 % |

| Attack | clean | g711 | gsm | g711_opus |
| --- | --- | --- | --- | --- |
| A07 | 0.85 % | 1.00 % | 6.15 % | 1.10 % |
| A08 | 0.15 % | 0.10 % | 2.10 % | 0.20 % |
| A09 | 0.00 % | 0.00 % | 1.00 % | 0.00 % |
| A10 | 0.90 % | 1.05 % | 8.15 % | 1.15 % |
| A11 | 0.15 % | 0.20 % | 5.00 % | 0.25 % |
| A12 | 0.90 % | 1.00 % | 7.00 % | 1.05 % |
| A13 | 0.05 % | 0.15 % | 8.05 % | 0.25 % |
| A14 | 0.90 % | 1.00 % | 4.90 % | 1.10 % |
| A15 | 1.05 % | 1.80 % | 6.95 % | 2.00 % |
| A16 | 0.85 % | 1.00 % | 5.10 % | 1.00 % |
| A17 | 1.00 % | 1.00 % | 10.00 % | 1.20 % |
| A18 | 3.00 % | 2.75 % | 8.00 % | 2.20 % |
| A19 | 0.20 % | 0.20 % | 4.00 % | 0.25 % |

Error rates at other cut-offs (real voices flagged / generated voices missed), from the same scores:

| Condition | cut-off 0.5 | cut-off 0.8 | cut-off 0.9 | cut-off 0.95 | cut-off 0.99 |
| --- | --- | --- | --- | --- | --- |
| clean | 0.6 % / 5.5 % | 0.5 % / 9.2 % | 0.4 % / 11.6 % | 0.3 % / 15.8 % | 0.1 % / 24.1 % |
| g711 | 1.1 % / 1.3 % | 0.8 % / 2.5 % | 0.6 % / 3.8 % | 0.4 % / 5.0 % | 0.3 % / 9.7 % |
| gsm | 31.8 % / 0.1 % | 23.7 % / 0.3 % | 20.5 % / 0.5 % | 17.6 % / 0.5 % | 11.9 % / 1.5 % |
| g711_opus | 1.7 % / 0.6 % | 1.3 % / 1.3 % | 1.1 % / 2.3 % | 0.7 % / 3.1 % | 0.5 % / 6.5 % |

The thresholds at the EER were 0.020 (clean), 0.435 (g711), 0.999 (gsm) and 0.803 (g711_opus): the score's meaning
moves with the channel.

The authors report 0.83 % EER for AASIST on the full ASVspoof 2019 LA evaluation set (clean, whole utterances).

### What this means

- On clean audio of the attacks it was trained against, AASIST is very accurate (0.78 % EER on this subset, in line
  with the published 0.83 %). Through G.711 and through this stack's own path (G.711 → Opus) it stays close (1.09 %
  and 1.30 % EER; at the service's 0.5, 1.7 % of real voices flagged and 0.6 % of generated ones missed).
- **GSM-FR breaks it:** 6.61 % EER, and at 0.5 it flags **31.8 % of real voices** as possibly generated; no cut-off
  fixes that (11.9 % still at 0.99). A caller on a 2G/GSM network reaches the scanner through GSM before G.711 and
  Opus, a chain not measured here. Until it is (and AMR, today's mobile codec), treat "Possibly AI-generated" for a
  mobile caller with suspicion; the app's wording says the check can be wrong. Fine-tuning AASIST on codec-augmented
  data is the known remedy (ASVspoof 2021 LA's channel conditions), not done here.
- The per-attack table shows which systems it misses most (A18 even on clean audio).
- ASVspoof 2019's attacks are text-to-speech and voice-conversion systems from before 2019. Current voice cloning
  (zero-shot cloning from a few seconds of speech, neural codecs) is not in this data and will be missed more often.
  Datasets with newer attacks and real-world recordings (ASVspoof 5, In-the-Wild) would show it; they were not run.
- The 4 s window is shorter than most evaluation utterances; the scanner scores up to two windows and reports their
  mean.
- Hence the wording in the app: a likelihood with an explanation that the check can be wrong, never a verdict, and
  never a reason to block or report a number.

## Tests

```shell
python3 -m venv .venv && .venv/bin/pip install -e '.[test]'
.venv/bin/ruff check src tests scripts && .venv/bin/ruff format --check src tests scripts
.venv/bin/pytest -q            # a tiny ONNX model stands in for AASIST; the codec tests need ffmpeg
```

CI (`voice-spoof` job) also builds the image (download, checksum, export) and scores a test signal with the real
model.
