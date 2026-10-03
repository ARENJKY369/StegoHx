"""Analyzer framework: a uniform interface over all detectors.

Each analyzer is a pure function of a MediaContext returning an
AnalyzerResult. Analyzers must never raise for 'unsupported media' - they
simply report ``applicable=False`` so the ensemble can skip them.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..media import MediaContext


@dataclass
class AnalyzerResult:
    applicable: bool = False
    score: float = 0.0  # normalized suspicion, 0..1
    notes: str = ""
    details: dict[str, Any] = field(default_factory=dict)


class Analyzer:
    """Base class for all analyzers."""

    name: str = "analyzer"
    display_name: str = "Analyzer"
    media_kinds: tuple[str, ...] = ()
    weight: float = 0.0  # default ensemble weight; ensembles may override
    description: str = ""

    def run(self, ctx: MediaContext) -> AnalyzerResult:
        raise NotImplementedError


def clamp01(x: float) -> float:
    return float(max(0.0, min(1.0, x)))
