"""Ensemble scoring: combines analyzer outputs into the final assessment.

Weighting policy
----------------
* each analyzer declares a default weight for its media kind
* a JPEG-decompressed cover (blockiness > 1.5) halves the weight of all
  spatial LSB analyzers, per Fridrich's guidance on JPEG covers
* an attached ONNX model, when loaded, takes 50% of the total weight and the
  statistical analyzers are renormalized over the remaining 50%

Verdict thresholds are deliberately conservative: steganalysis is noisy on
small or processed media, so 'INCONCLUSIVE' is a first-class outcome.
"""

from __future__ import annotations

import time
from typing import Optional

import numpy as np

from .. import __version__
from ..media import MediaContext
from ..schemas import (
    AnalysisResponse,
    AnalyzerFinding,
    FileInfo,
    RecommendedAction,
    Verdict,
)
from .audio_lsb import AudioLSBAnalyzer
from .base import Analyzer, AnalyzerResult
from .chi_square import ChiSquareAnalyzer
from .container_meta import ContainerMetaAnalyzer
from .model_inference import ModelInference
from .plane_stats import PlaneStatsAnalyzer
from .rs_analysis import RSAnalysisAnalyzer

SPATIAL_ANALYZERS = {"chi_square", "rs_analysis", "plane_stats"}

VERDICT_THRESHOLDS = (
    (0.70, Verdict.HIGH_CONFIDENCE_STEGO),
    (0.45, Verdict.LIKELY_STEGO),
    (0.20, Verdict.INCONCLUSIVE),
    (0.0, Verdict.CLEAN),
)


class Ensemble:
    def __init__(self, model: Optional[ModelInference] = None) -> None:
        self.model = model or ModelInference()
        self.analyzers: list[Analyzer] = [
            RSAnalysisAnalyzer(),
            ChiSquareAnalyzer(),
            PlaneStatsAnalyzer(),
            AudioLSBAnalyzer(),
            ContainerMetaAnalyzer(),
        ]

    def analyze(self, ctx: MediaContext, info: FileInfo) -> AnalysisResponse:
        started = time.perf_counter()
        findings: list[AnalyzerFinding] = []
        results: list[tuple[Analyzer, AnalyzerResult, float]] = []

        blocky = ctx.jpeg_blockiness is not None and ctx.jpeg_blockiness > 1.5

        for analyzer in self.analyzers:
            result = analyzer.run(ctx)
            weight = analyzer.weight
            if not result.applicable:
                findings.append(
                    AnalyzerFinding(
                        name=analyzer.name,
                        display_name=analyzer.display_name,
                        applicable=False,
                        score=0.0,
                        weight=weight,
                        notes=result.notes or "not applicable to this media kind",
                    )
                )
                continue
            if analyzer.name in SPATIAL_ANALYZERS and blocky:
                weight *= 0.5
            results.append((analyzer, result, weight))

        model_used = "statistical-ensemble"
        model_note = ""
        if ctx.is_image and self.model.loaded:
            score, note = self.model.analyze(ctx)
            if score is not None:
                model_used = "onnx:" + (self.model.path or "model")
                # model takes 50% of total weight; renormalize the rest
                results = [(a, r, w * 0.5) for a, r, w in results]
                results.append(
                    (
                        _ModelAdapter(),
                        AnalyzerResult(applicable=True, score=score, notes=note),
                        0.5,
                    )
                )
                model_note = f" ONNX model contributed to the score ({note})."

        applicable = [(a, r, w) for a, r, w in results if r.applicable]
        if applicable:
            total_weight = sum(w for _, _, w in applicable) or 1.0
            threat = float(sum(r.score * w for _, r, w in applicable) / total_weight)
            scores = [r.score for _, r, _ in applicable]
            agreement = 1.0 - float(np.std(scores)) if len(scores) > 1 else 0.75
            coverage = min(1.0, len(applicable) / 3.0)
            confidence = float(np.clip(0.6 * agreement + 0.4 * coverage, 0.0, 1.0))
        else:
            threat = 0.05
            confidence = 0.10

        verdict = next(v for t, v in VERDICT_THRESHOLDS if threat >= t)
        action = self._action(verdict, threat)
        summary = self._summary(verdict, threat, applicable, ctx, blocky) + model_note

        for analyzer, result, weight in applicable:
            findings.append(
                AnalyzerFinding(
                    name=analyzer.name,
                    display_name=analyzer.display_name,
                    applicable=True,
                    score=round(result.score, 4),
                    weight=round(weight, 4),
                    notes=result.notes,
                    details=result.details,
                )
            )
        # keep registry order in the response
        order = {a.name: i for i, a in enumerate(self.analyzers)}
        findings.sort(key=lambda f: order.get(f.name, 99))

        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return AnalysisResponse(
            analyzer_version=__version__,
            file=info,
            threat_score=round(threat, 4),
            confidence=round(confidence, 4),
            verdict=verdict,
            summary=summary,
            recommended_action=action,
            analyzers=findings,
            model_used=model_used,
            analysis_ms=elapsed_ms,
        )

    @staticmethod
    def _action(verdict: Verdict, threat: float) -> RecommendedAction:
        if threat >= 0.90:
            return RecommendedAction.QUARANTINE
        if verdict == Verdict.HIGH_CONFIDENCE_STEGO:
            return RecommendedAction.CLEAN_SANITIZE
        if verdict == Verdict.LIKELY_STEGO:
            return RecommendedAction.EXTRACT_ATTEMPT
        if verdict == Verdict.INCONCLUSIVE:
            return RecommendedAction.FLAG_FOR_REVIEW
        return RecommendedAction.NONE

    @staticmethod
    def _summary(
        verdict: Verdict,
        threat: float,
        applicable: list[tuple[Analyzer, AnalyzerResult, float]],
        ctx: MediaContext,
        blocky: bool,
    ) -> str:
        parts = [f"{verdict.value} (threat score {threat:.2f})."]
        if applicable:
            top_a, top_r, top_w = max(applicable, key=lambda t: t[1].score * t[2])
            parts.append(f"Strongest signal: {top_a.display_name} - {top_r.notes}")
        else:
            parts.append("No analyzers are applicable to this media kind.")
        if blocky:
            parts.append(
                "Cover appears to be a decompressed JPEG: spatial LSB statistics "
                "were downweighted and results may be less reliable."
            )
        return " ".join(parts)


class _ModelAdapter(Analyzer):
    """Duck-typed stand-in so the ONNX model slots into the ensemble loop."""

    name = "onnx_model"
    display_name = "ONNX Steganalysis Model"
    weight = 0.5
