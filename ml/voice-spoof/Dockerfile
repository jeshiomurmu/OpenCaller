# ml/voice-spoof (ADR 0011): the voice scanner's anti-spoofing service. Internal only; the scanner agent calls it.
#
# Stage 1 downloads AASIST's pretrained weights (NAVER, MIT; SHA-256 pinned in src/voice_spoof/weights.py) and
# exports them to ONNX with PyTorch's CPU build. Stage 2 serves the ONNX model with onnxruntime, without PyTorch.
#
#   docker build -t opencaller/voice-spoof:dev ml/voice-spoof
FROM python:3.13-slim-bookworm AS export

ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /build
COPY pyproject.toml README.md LICENSE-AASIST ./
COPY src ./src
# PyTorch's CPU wheels (its CUDA wheels on PyPI pull several GB of NVIDIA libraries the export doesn't need);
# `torch==2.15.0` matches the index's `2.15.0+cpu`, so the export extra below finds it installed.
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0 \
    && pip install '.[export]' \
    && voice-spoof-export --weights model/AASIST.pth --out model/aasist.onnx

FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    VOICE_SPOOF_MODEL=/app/model/aasist.onnx

WORKDIR /app
COPY pyproject.toml README.md LICENSE-AASIST ./
COPY src ./src
RUN pip install . && useradd --create-home --uid 10001 spoof
COPY --from=export /build/model/aasist.onnx /app/model/aasist.onnx
USER spoof
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=3)"]
CMD ["uvicorn", "voice_spoof.service:app", "--host", "0.0.0.0", "--port", "8090"]
