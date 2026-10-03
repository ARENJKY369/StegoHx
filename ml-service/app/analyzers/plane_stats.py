"""Bit-plane coherence statistics.

In natural imagery the LSB plane is noisy but not white noise: it retains
weak spatial structure correlated with the image's edges, similar to (though
weaker than) bit-plane 1. LSB replacement destroys that structure while
leaving higher planes untouched. This analyzer measures:

  * lag-1 spatial autocorrelation of the LSB plane vs bit-plane 1
  * the raw entropy of the LSB plane

Both are cheap, complementary signals: autocorrelation collapse flags
scattered embedding; near-maximal entropy flags high-rate embedding.
"""

from __future__ import annotations

import numpy as np

from ..media import MediaContext
from .base import Analyzer, AnalyzerResult, clamp01


def _lag1_autocorr(plane: np.ndarray) -> float:
    """Mean lag-1 autocorrelation over horizontal and vertical directions."""
    x = plane.astype(np.float64)
    x = x - x.mean()
    denom = float((x * x).sum())
    if denom < 1e-9:
        return 0.0
    horiz = float((x[:, :-1] * x[:, 1:]).sum())
    vert = float((x[:-1, :] * x[1:, :]).sum())
    return float((horiz + vert) / (2.0 * denom))


def _entropy_bits(plane: np.ndarray) -> float:
    counts = np.bincount(plane.reshape(-1), minlength=2)
    p = counts[counts > 0] / counts.sum()
    return float(-(p * np.log2(p)).sum())


class PlaneStatsAnalyzer(Analyzer):
    name = "plane_stats"
    display_name = "Bit-Plane Coherence"
    media_kinds = ("image",)
    weight = 0.20
    description = (
        "Compares spatial autocorrelation and entropy of the LSB plane against "
        "bit-plane 1; embedding randomizes plane 0 while leaving plane 1 intact."
    )

    def run(self, ctx: MediaContext) -> AnalyzerResult:
        if not ctx.is_image or ctx.channels is None:
            return AnalyzerResult(applicable=False, notes="not an image")
        per_channel = []
        scores = []
        for c in range(ctx.channels.shape[0]):
            ch = ctx.channels[c]
            lsb = ch & 1
            plane1 = (ch >> 1) & 1
            ac0 = _lag1_autocorr(lsb)
            ac1 = _lag1_autocorr(plane1)
            h0 = _entropy_bits(lsb)
            # How much of plane-1's structure is missing from the LSB plane?
            ac_gap = clamp01((ac1 - ac0) / (abs(ac1) + 0.05))
            # Near-uniform-random LSB plane (H ~ 8 bits) only occurs after
            # high-rate embedding; natural planes sit well below.
            ent_signal = clamp01((h0 - 7.75) / 0.24)
            scores.append(clamp01(0.7 * ac_gap + 0.3 * ent_signal))
            per_channel.append(
                {
                    "channel": float(c),
                    "lsb_autocorr": round(ac0, 4),
                    "plane1_autocorr": round(ac1, 4),
                    "lsb_entropy_bits": round(h0, 4),
                    "ac_gap": round(ac_gap, 4),
                    "entropy_signal": round(ent_signal, 4),
                }
            )
        score = clamp01(float(np.mean(scores)))
        top = max(per_channel, key=lambda d: d["ac_gap"])
        note = (
            f"LSB-plane autocorrelation {top['lsb_autocorr']:.3f} vs plane-1 "
            f"{top['plane1_autocorr']:.3f} (worst channel); "
            + ("LSB structure is lost relative to plane 1"
               if score > 0.4 else "LSB structure is consistent with natural imagery")
        )
        if ctx.jpeg_blockiness is not None and ctx.jpeg_blockiness > 1.5:
            note += "; JPEG-decompressed cover detected, spatial statistics downweighted"
        return AnalyzerResult(applicable=True, score=score, notes=note, details={"per_channel": per_channel})
