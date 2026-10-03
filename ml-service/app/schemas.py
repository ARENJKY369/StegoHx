"""Pydantic schemas defining the analyzer service's public JSON contract.

These models are the single source of truth for the wire format consumed by
the Java engine (backend/) and the web console (frontend/). Field names use
snake_case throughout the stack.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


class MediaKind(str, Enum):
    IMAGE = "image"
    AUDIO = "audio"
    VIDEO = "video"
    OTHER = "other"


class Verdict(str, Enum):
    CLEAN = "CLEAN"
    INCONCLUSIVE = "INCONCLUSIVE"
    LIKELY_STEGO = "LIKELY_STEGO"
    HIGH_CONFIDENCE_STEGO = "HIGH_CONFIDENCE_STEGO"


class RecommendedAction(str, Enum):
    NONE = "NONE"
    FLAG_FOR_REVIEW = "FLAG_FOR_REVIEW"
    EXTRACT_ATTEMPT = "EXTRACT_ATTEMPT"
    CLEAN_SANITIZE = "CLEAN_SANITIZE"
    QUARANTINE = "QUARANTINE"


class FileInfo(BaseModel):
    name: str = Field(description="Original file name as uploaded")
    size_bytes: int = Field(ge=0)
    mime: str = Field(description="Best-effort sniffed MIME type")
    kind: MediaKind
    width: Optional[int] = Field(default=None, description="Pixels, image media only")
    height: Optional[int] = Field(default=None, description="Pixels, image media only")
    duration_samples: Optional[int] = Field(default=None, description="Audio: sample count")
    sample_rate: Optional[int] = Field(default=None, description="Audio: Hz")


class AnalyzerFinding(BaseModel):
    name: str = Field(description="Stable analyzer id, e.g. rs_analysis")
    display_name: str = Field(description="Human readable name")
    applicable: bool = Field(description="Whether this analyzer ran against the file")
    score: float = Field(ge=0.0, le=1.0, description="Normalized suspicion score, 0..1")
    weight: float = Field(ge=0.0, le=1.0, description="Ensemble weight assigned to this analyzer")
    notes: str = Field(default="", description="Human readable explanation of the result")
    details: dict[str, Any] = Field(default_factory=dict, description="Raw analyzer metrics")


class AnalysisResponse(BaseModel):
    analyzer_version: str
    file: FileInfo
    threat_score: float = Field(ge=0.0, le=1.0, description="Ensemble suspicion score, 0..1")
    confidence: float = Field(ge=0.0, le=1.0, description="Confidence in the verdict, 0..1")
    verdict: Verdict
    summary: str
    recommended_action: RecommendedAction
    analyzers: list[AnalyzerFinding] = Field(default_factory=list)
    model_used: str = Field(default="statistical-ensemble", description="Which model/ensemble produced the score")
    analysis_ms: int = Field(default=0, description="Wall-clock analysis time in milliseconds")


class AnalyzerInfo(BaseModel):
    name: str
    display_name: str
    media_kinds: list[MediaKind]
    weight: float
    description: str


class HealthResponse(BaseModel):
    status: str
    version: str
    model_loaded: bool
    model_path: Optional[str] = None
