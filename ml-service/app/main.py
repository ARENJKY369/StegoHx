"""StegoHX Analyzer Service - FastAPI application."""

from __future__ import annotations

import logging

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .analyzers import Ensemble, ModelInference
from .media import build_context
from .schemas import AnalyzerInfo, AnalysisResponse, HealthResponse

logger = logging.getLogger("stegohx.analyzer")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

MAX_UPLOAD_BYTES = 30 * 1024 * 1024  # keep in sync with the engine's multipart limits

app = FastAPI(
    title="StegoHX Analyzer Service",
    description=(
        "Statistical steganalysis microservice. Consumed by the StegoHX Java "
        "engine to produce threat scores, verdicts and recommended actions."
    ),
    version=__version__,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # server-to-server (engine -> analyzer); the console proxies through the engine
    allow_methods=["*"],
    allow_headers=["*"],
)

ensemble = Ensemble(ModelInference())


@app.get("/health", response_model=HealthResponse, tags=["system"])
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        version=__version__,
        model_loaded=ensemble.model.loaded,
        model_path=ensemble.model.path,
    )


@app.get("/api/v1/analyzers", response_model=list[AnalyzerInfo], tags=["analysis"])
def list_analyzers() -> list[AnalyzerInfo]:
    infos = [
        AnalyzerInfo(
            name=a.name,
            display_name=a.display_name,
            media_kinds=list(a.media_kinds),  # type: ignore[arg-type]
            weight=a.weight,
            description=a.description,
        )
        for a in ensemble.analyzers
    ]
    if ensemble.model.loaded:
        infos.append(
            AnalyzerInfo(
                name="onnx_model",
                display_name="ONNX Steganalysis Model",
                media_kinds=["image"],
                weight=0.5,
                description="Pluggable deep model (STEGOHX_MODEL_PATH); joins the ensemble at 50% weight.",
            )
        )
    return infos


@app.post("/api/v1/analyze", response_model=AnalysisResponse, tags=["analysis"])
async def analyze(file: UploadFile = File(..., description="Media file to analyze")) -> AnalysisResponse:
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="empty upload")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="file exceeds the 30 MiB analysis limit")
    name = file.filename or "upload.bin"
    logger.info("analyzing %s (%d bytes)", name, len(data))
    try:
        ctx, info = build_context(data, name)
    except Exception as exc:  # noqa: BLE001
        logger.exception("failed to parse upload")
        raise HTTPException(status_code=422, detail=f"could not parse upload: {exc}") from exc
    try:
        response = ensemble.analyze(ctx, info)
    except Exception as exc:  # noqa: BLE001
        logger.exception("analysis failed")
        raise HTTPException(status_code=500, detail=f"analysis failed: {exc}") from exc
    logger.info(
        "verdict=%s threat=%.3f file=%s (%d ms)",
        response.verdict, response.threat_score, name, response.analysis_ms,
    )
    return response


if __name__ == "__main__":  # pragma: no cover - convenience entrypoint
    import uvicorn

    uvicorn.run("app.main:app", host="127.0.0.1", port=8000, reload=False)
