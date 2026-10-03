# StegoHX Architecture

## Overview

Three services, all local, communicating over plain HTTP on loopback:

```
                         ┌──────────────────────────────────────────────┐
                         │                 Browser                      │
                         │  Next.js console (static + client components)│
                         └───────────────────┬──────────────────────────┘
                                             │  same-origin /api/v1/*
                                             ▼
                         ┌──────────────────────────────────────────────┐
                         │  Next route handler (src/app/api/v1/[...])   │
                         │  • primary: proxy to engine :8080            │
                         │  • fallback (engine down):                   │
                         │     - scan → analyzer :8000 (wrapped,        │
                         │       scan id "preview-*")                   │
                         │     - system/status → synthesized            │
                         │     - else → 503 with a structured hint      │
                         └───────────────────┬──────────────────────────┘
                                             │ localhost
                                             ▼
        ┌──────────────────────────────────────────────────────────────────────┐
        │  Engine — Spring Boot 3 (Java 17), :8080                              │
        │                                                                        │
        │  api/          controllers: system, stego (hide/extract/clean/evolve), │
        │                scan (+ history, PDF)                                   │
        │  core/stego/  module registry + 5 carrier modules                      │
        │  core/codec/  STGX payload container (HMAC-SHA256 keystream)           │
        │  core/stego/lsb/  bit-plane embedder + keyed position permutation      │
        │  service/     AnalyzerClient (RestClient), ScanService, EvolveService, │
        │               ReportService (OpenPDF)                                  │
        │  domain/repo/ ScanReport (H2, ./data/stegohx)                          │
        └───────────────────────────────┬────────────────────────────────────────┘
                                        │ POST multipart /api/v1/analyze
                                        ▼
        ┌──────────────────────────────────────────────────────────────────────┐
        │  Analyzer — FastAPI + NumPy + Pillow, :8000                           │
        │                                                                        │
        │  media.py       sniff + decode (image/WAV/ISO-BMFF parse)             │
        │  analyzers/     rs_analysis · chi_square · plane_stats · audio_lsb ·  │
        │                 container_meta · model_inference (optional ONNX)      │
        │  ensemble.py    per-kind weighting, JPEG-blockiness downweighting,    │
        │                 verdict thresholds, recommended action                │
        └──────────────────────────────────────────────────────────────────────┘
```

## Request flows

### Scan (blue-team triage)

1. Console uploads the file to `POST /api/v1/scan` (same-origin).
2. Route handler proxies to the engine; the engine forwards the bytes to the
   analyzer's `POST /api/v1/analyze`.
3. Analyzer decodes the media, runs the applicable analyzers, combines them
   into a threat score / verdict / recommended action.
4. Engine persists a `ScanReport` (metadata + analyzer JSON, never the file
   content) and returns the full analysis to the console.
5. `GET /api/v1/scans/{id}/report.pdf` renders the professional PDF on demand.

### Evolve (adaptive embedding research)

1. Console posts cover + payload to `POST /api/v1/stego/evolve`.
2. Engine scores the original cover (baseline), then for each candidate
   strategy: embed in memory → analyze → record.
3. Lowest-scoring strategy wins; all candidates plus an advice string are
   returned. Nothing is persisted; no files leave the process.

### Hide / Extract / Clean

Pure engine operations (no analyzer involvement). Byte arrays in, byte
arrays out; the console turns base64 responses into downloads.

## Design decisions

- **One JSON convention (snake_case) across the stack.** The engine sets
  `spring.jackson.property-naming-strategy: SNAKE_CASE`; the analyzer and the
  console use the same names. One contract, three runtimes.
- **Pass-through analyzer payload.** The engine does not re-model the
  analyzer's response beyond typed DTOs; the console renders the analyzer's
  findings verbatim, so analyzers can evolve without console changes.
- **Graceful degradation in the console.** A preview deployment without Java
  still demonstrates the analyzer pipeline; everything else returns a
  structured 503 instead of a broken UI.
- **No file retention.** Covers and stego objects exist only in request
  memory. The H2 store keeps scan metadata (name, size, mime, verdict,
  analyzer JSON) for reporting — never file content.
- **Loopback by default.** All services bind to 127.0.0.1. The engine's CORS
  exists only for direct-browser development; production console traffic is
  same-origin via the proxy.

## Deployment notes

- **Single box:** run all three services; open the console. This is the
  intended deployment.
- **Team server:** run engine + analyzer on an internal host; set
  `STEGOHX_ENGINE_URL` / `STEGOHX_ANALYZER_URL` on the console and
  `STEGOHX_ANALYZER_URL` on the engine. Put TLS and auth in front (the
  services ship without authentication on purpose: they are analysis
  workbenches, not public endpoints).
- **Batch research:** the analyzer is usable headless
  (`curl -F file=@x.png http://127.0.0.1:8000/api/v1/analyze`) and the
  engine's H2 store can be pointed at a shared path for report history.
