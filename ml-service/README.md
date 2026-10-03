# StegoHX Analyzer Service

Statistical steganalysis microservice (Python / FastAPI). The StegoHX Java
engine calls it for every `scan` and `evolve` operation; it can also be used
stand-alone for blue-team triage and detector research.

## Analyzers

| Analyzer | Media | What it does |
|---|---|---|
| `rs_analysis` | image | Fridrich/Goljan/Du Regular-Singular groups estimator. Recovers the LSB-replacement rate via the closed-form quadratic from the 2001 paper; robust against scattered embedding. Also applied to low-activity audio segments by `audio_lsb`. |
| `chi_square` | image | Westfeld/Pfitzmann pairs-of-values attack, evaluated over multiple raster prefixes to expose sequential embedding. Fires at high embedding rates. |
| `plane_stats` | image | Compares spatial autocorrelation and entropy of the LSB plane against bit-plane 1. |
| `audio_lsb` | audio | RS analysis + chi-square over low-activity PCM segments (the regime where LSB replacement is statistically visible). |
| `container_meta` | video/other | Walks ISO-BMFF top-level atoms, flags unknown atoms / oversized padding and scans for payload markers. |
| `onnx_model` *(optional)* | image | Pluggable deep model. Set `STEGOHX_MODEL_PATH` to an ONNX file accepting `(1,3,256,256)` float32 RGB in `[0,1]` and returning a sigmoid probability; it then joins the ensemble at 50% weight. Install `onnxruntime` from `requirements-model.txt`. |

Ensemble behavior: analyzer scores are combined with per-kind weights; when
the cover looks like a decompressed JPEG (8×8 blockiness test), spatial
analyzers are downweighted. Verdicts: `CLEAN` < 0.20, `INCONCLUSIVE` < 0.45,
`LIKELY_STEGO` < 0.70, `HIGH_CONFIDENCE_STEGO` >= 0.70.

## Run

```bash
cd ml-service
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Interactive docs: <http://127.0.0.1:8000/docs>

## Self-test

Generates synthetic cover/stego pairs and asserts the ensemble separates
them; also refreshes the shared samples in `../samples/`:

```bash
.venv/bin/python scripts/selftest.py
```

## API

- `GET /health` - liveness, version, model status
- `GET /api/v1/analyzers` - analyzer registry (used by the engine's status endpoint)
- `POST /api/v1/analyze` - multipart `file` -> full `AnalysisResponse` (see `app/schemas.py` and `docs/API.md`)

## Known limitations (documented on purpose)

- Loud full-scale audio has a near-uniform low byte; LSB embedding there is
  not statistically detectable by current methods. The analyzer reports
  reduced sensitivity when a recording lacks quiet segments.
- Smooth synthetic images (gradients without histogram roughness) can
  equalize pairs-of-values naturally and inflate the chi-square signal.
- The statistical ensemble targets **LSB replacement**. LSB **matching**
  (±1 embedding), palette transforms, and DCT-domain JPEG stego need the
  pluggable ONNX model path.
