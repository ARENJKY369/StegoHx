# StegoHX API Reference

All engine and analyzer endpoints speak JSON with **snake_case** field names.
Multipart requests are `multipart/form-data`; the upload limit is 30 MB.

Error envelope (any non-2xx):

```json
{
  "timestamp": "2026-10-03T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "payload too large for cover: need 12000 carriers, have 9000",
  "path": "/api/v1/stego/hide"
}
```

---

## Engine (Spring Boot, :8080)

### System

#### `GET /api/v1/system/status`

Engine + analyzer health, module registry, scan count.

```json
{
  "status": "OK",
  "engine":  { "version": "1.0.0", "java_version": "17.0.12", "uptime_seconds": 512 },
  "analyzer": { "reachable": true, "base_url": "http://127.0.0.1:8000", "version": "1.0.0", "model_loaded": false },
  "scans_executed": 14,
  "modules": [
    { "id": "image_lsb", "display_name": "Image LSB (RGB bit planes)", "description": "…",
      "media_kind": "IMAGE", "requires_cover": true }
  ]
}
```

`status` is `OK` when the analyzer is reachable, otherwise `DEGRADED`.

#### `GET /api/v1/health` → `{"status":"ok"}`

#### `GET /api/v1/modules` → module list (same shape as above)

### Stego operations

#### `POST /api/v1/stego/hide` *(multipart)*

| part | type | default | notes |
|---|---|---|---|
| `file` | file | — | optional for text/network modules |
| `payload` | text | required | embedded as UTF-8 |
| `key` | text | `""` | keystream + scatter seed derivation |
| `module_id` | text | auto | `image_lsb`, `audio_lsb_wav`, `video_mp4_atom`, `network_transport`, `text_armored` |
| `bit_depth` | int 1–2 | 1 | image/audio modules |
| `spread` | `SEQUENTIAL`\|`SEEDED` | `SEQUENTIAL` | seeded = keyed scatter |
| `seed` | int | 0 | non-zero overrides key-derived seed; re-supply on extract |
| `variant` | `dns`\|`http` | `dns` | network module only |

Response:

```json
{
  "module_id": "image_lsb", "module_name": "Image LSB (RGB bit planes)",
  "output_name": "stegohx_out.png", "output_mime": "image/png",
  "output_base64": "…", "payload_bytes": 41, "capacity_used_ratio": 0.000421
}
```

#### `POST /api/v1/stego/extract` *(multipart: `file`, `key`, `module_id?`, `seed?`)*

```json
{
  "module_id": "image_lsb", "module_name": "Image LSB (RGB bit planes)",
  "payload_text": "meet at 22:00, dock 7",
  "payload_base64": "…", "payload_bytes": 21, "container_verified": true
}
```

`payload_text` is present when ≥90% of the payload bytes are printable.

#### `POST /api/v1/stego/clean` *(multipart: `file`, `module_id?`)*

Returns the sanitized file (`output_base64`) plus a `note` describing exactly
what was destroyed (e.g. "zeroed the two lowest bit planes of every RGB
channel").

#### `POST /api/v1/stego/evolve` *(multipart: `file`, `payload`, `key`, `module_id?`)*

```json
{
  "baseline_threat_score": 0.178,
  "selected": {
    "strategy": { "label": "lsb1-seeded", "bit_depth": 1, "spread": "SEEDED" },
    "ok": true, "threat_score": 0.402, "payload_bytes": 41,
    "capacity_used": 0.0004, "error": null
  },
  "candidates": [ … ],
  "advice": "Strategy 'lsb1-seeded' scores 0.402 vs cover baseline 0.178 (+0.224): …"
}
```

### Scans & reports

#### `POST /api/v1/scan` *(multipart: `file`)*

Runs the analyzer, persists the result, returns the full assessment:

```json
{
  "scan_id": "6f0c…", "created_at": "2026-10-03T12:00:00Z", "engine_version": "1.0.0",
  "analysis": { /* AnalyzerResponse, below */ }
}
```

#### `GET /api/v1/scans?limit=20` → recent scans (summaries, no analyzer detail)
#### `GET /api/v1/scans/{id}` → full scan including analyzer findings
#### `GET /api/v1/scans/{id}/report.pdf` → `application/pdf` report

---

## Analyzer service (FastAPI, :8000)

#### `GET /health` → `{"status":"ok","version":"1.0.0","model_loaded":false,"model_path":null}`

#### `GET /api/v1/analyzers` → registry (name, media kinds, weight, description)

#### `POST /api/v1/analyze` *(multipart: `file`)*

```json
{
  "analyzer_version": "1.0.0",
  "file": { "name": "stego.png", "size_bytes": 241721, "mime": "image/png",
            "kind": "image", "width": 512, "height": 384 },
  "threat_score": 0.5712,
  "confidence": 0.744,
  "verdict": "LIKELY_STEGO",
  "summary": "LIKELY_STEGO (threat score 0.57). Strongest signal: RS Analysis …",
  "recommended_action": "EXTRACT_ATTEMPT",
  "analyzers": [
    { "name": "rs_analysis", "display_name": "RS Analysis (Regular/Singular Groups)",
      "applicable": true, "score": 0.9774, "weight": 0.55,
      "notes": "max |estimated LSB flip rate| 34.9% (above clean-image bias)",
      "details": { "estimated_rate": 0.349, "per_channel": [ … ] } },
    { "name": "audio_lsb", "applicable": false, "score": 0.0, "weight": 1.0,
      "notes": "not a decodable 16-bit PCM WAV" }
  ],
  "model_used": "statistical-ensemble",
  "analysis_ms": 56
}
```

**Verdict scale** (ensemble threat score):

| Verdict | Range | Recommended action |
|---|---|---|
| `CLEAN` | < 0.20 | `NONE` |
| `INCONCLUSIVE` | 0.20 – 0.45 | `FLAG_FOR_REVIEW` |
| `LIKELY_STEGO` | 0.45 – 0.70 | `EXTRACT_ATTEMPT` |
| `HIGH_CONFIDENCE_STEGO` | ≥ 0.70 | `CLEAN_SANITIZE` (≥ 0.90 → `QUARANTINE`) |

**Optional ONNX model:** set `STEGOHX_MODEL_PATH` to a model accepting
`float32 (1,3,256,256)` RGB in `[0,1]` and returning a sigmoid probability.
It joins the ensemble at 50% weight; the statistical ensemble keeps running.

---

## Console proxy (Next.js, :3000)

The browser only ever calls same-origin `/api/v1/*`. The route handler
forwards to the engine; when the engine is down it degrades explicitly:

- `POST /api/v1/scan` → served by the analyzer directly; `scan_id` prefixed
  `preview-`, `engine_version` = `analyzer-fallback`
- `GET /api/v1/system/status` → synthesized status (analyzer probed live)
- anything else → `503 {"error":"engine_offline", "hint": "…"}`

Environment knobs: `STEGOHX_ENGINE_URL` (default `http://127.0.0.1:8080`),
`STEGOHX_ANALYZER_URL` (default `http://127.0.0.1:8000`).
