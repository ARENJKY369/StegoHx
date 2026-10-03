"""RS (Regular/Singular groups) steganalysis - Fridrich, Goljan & Du.

Estimates the fraction of pixels whose LSB was flipped by LSB-replacement
embedding. Implementation follows the closed-form estimator from
"Reliable Detection of LSB Steganography in Color and Grayscale Images"
(ACM Workshop on Multimedia and Security, 2001), as reprinted in Fridrich's
"Practical Steganalysis of Digital Images - State of the Art" (2001):

    d0    = R_M(p/2)     - S_M(p/2)
    d1    = R_M(1-p/2)   - S_M(1-p/2)
    d-0   = R_-M(p/2)    - S_-M(p/2)
    d-1   = R_-M(1-p/2)  - S_-M(1-p/2)

    2(d1 + d0) z^2 + (d-0 - d-1 - d1 - 3 d0) z + (d0 - d-0) = 0
    p = z / (z - 1/2)      (root z with the smaller absolute value)

where the "1-p/2" points are measured on a copy of the image in which every
LSB has been flipped. Negative estimates are the documented initial bias of
the method on clean imagery.
"""

from __future__ import annotations

import math

import numpy as np

from ..media import MediaContext
from .base import Analyzer, AnalyzerResult, clamp01

MASK = (0, 1, 1, 0)  # paper's canonical mask; -M applies F-1 at the same positions
MIN_GROUPS = 2048  # below this the group statistics are too noisy

# |estimated rate| -> score calibration curve. Clean covers show an initial
# bias of up to a few percent (Fridrich reports sigma ~= 0.5-2% on camera
# images; synthetic/noisy covers can be worse), so the curve stays low until
# ~4% and then rises steeply through meaningful embedding rates.
_RATE_SCORE_CURVE = (
    (0.00, 0.05),
    (0.04, 0.25),
    (0.07, 0.50),
    (0.15, 0.85),
    (0.30, 0.97),
    (0.50, 1.00),
)


def _rate_to_score(rate: float) -> float:
    xs = [p[0] for p in _RATE_SCORE_CURVE]
    ys = [p[1] for p in _RATE_SCORE_CURVE]
    return clamp01(float(np.interp(abs(rate), xs, ys)))


def _discriminant(groups: np.ndarray) -> np.ndarray:
    """f(G) = sum |x_{i+1} - x_i| over each group of 4 (axis=-1)."""
    d = np.abs(np.diff(groups.astype(np.int32), axis=-1))
    return d.sum(axis=-1)


def _apply_mask(groups: np.ndarray, mask: tuple[int, ...], flipped: bool) -> np.ndarray:
    """Apply F1 / F-1 at masked positions.

    ``flipped=True`` additionally applies F1 to *every* sample first, which
    produces the measurement point at 1 - p/2.
    """
    g = groups.astype(np.int32) ^ 1 if flipped else groups.astype(np.int32)
    out = g.copy()
    for pos, m in enumerate(mask):
        if m == 1:
            out[..., pos] = g[..., pos] ^ 1  # F1
        elif m == -1:
            x = g[..., pos]
            out[..., pos] = np.where(x % 2 == 0, x - 1, x + 1)  # F-1
    return out


def _rs_points(groups: np.ndarray, flipped: bool) -> tuple[float, float, float, float]:
    f_orig = _discriminant(groups)
    r_mask = _apply_mask(groups, MASK, flipped)
    r_negmask = _apply_mask(groups, tuple(-m for m in MASK), flipped)
    f_mask = _discriminant(r_mask)
    f_neg = _discriminant(r_negmask)
    total = f_orig.size
    r_m = float((f_mask > f_orig).sum()) / total
    s_m = float((f_mask < f_orig).sum()) / total
    r_nm = float((f_neg > f_orig).sum()) / total
    s_nm = float((f_neg < f_orig).sum()) / total
    return r_m, s_m, r_nm, s_nm


def _solve_rate(d0: float, d1: float, dn0: float, dn1: float) -> tuple[float, str]:
    """Solve the RS quadratic and map the root back to an embedding rate."""
    a = 2.0 * (d1 + d0)
    b = dn0 - dn1 - d1 - 3.0 * d0
    c = d0 - dn0
    if abs(a) < 1e-12:
        if abs(b) < 1e-12:
            return 0.0, "degenerate (flat or tiny statistics)"
        z = -c / b
        roots = [z]
    else:
        disc = b * b - 4.0 * a * c
        if disc < 0:
            return 0.0, "no real root (statistically flat response)"
        sq = math.sqrt(disc)
        # numerically stable pairing
        q = -0.5 * (b + math.copysign(sq, b))
        roots = []
        if abs(q) > 1e-12:
            roots.append(q / a)
            roots.append(c / q)
        else:
            roots.append(-b / a)
    # choose the root with the smaller absolute value (per the paper)
    z = min(roots, key=abs)
    if abs(z - 0.5) < 1e-9:
        return 0.0, "root at singularity"
    p = z / (z - 0.5)
    if not math.isfinite(p):
        return 0.0, "non-finite estimate"
    return float(np.clip(p, -1.0, 1.0)), "ok"


def estimate_rate(groups: np.ndarray) -> tuple[float, dict]:
    """RS embedding-rate estimate over pre-shaped groups.

    ``groups`` must be an integer array whose last axis holds the members of
    each pixel/sample group (length 4, matching MASK). Works for 8-bit image
    planes and 16-bit PCM samples alike - LSB replacement has the same
    pair-of-values structure at any word width.

    Returns (rate, details). Rate is the estimated fraction of LSB-flipped
    samples; negative values are the method's clean-image bias.
    """
    r_m, s_m, r_nm, s_nm = _rs_points(groups, flipped=False)
    r_mf, s_mf, r_nmf, s_nmf = _rs_points(groups, flipped=True)
    d0 = r_m - s_m
    d1 = r_mf - s_mf
    dn0 = r_nm - s_nm
    dn1 = r_nmf - s_nmf
    rate, status = _solve_rate(d0, d1, dn0, dn1)
    return rate, {
        "estimated_rate": round(rate, 4),
        "R_M": round(r_m, 4), "S_M": round(s_m, 4),
        "R_-M": round(r_nm, 4), "S_-M": round(s_nm, 4),
        "status": status,
    }


class RSAnalysisAnalyzer(Analyzer):
    name = "rs_analysis"
    display_name = "RS Analysis (Regular/Singular Groups)"
    media_kinds = ("image",)
    weight = 0.55
    description = (
        "Fridrich/Goljan/Du dual-statistics estimator for LSB-replacement "
        "embedding rate; robust against scattered (non-sequential) embedding."
    )

    def run(self, ctx: MediaContext) -> AnalyzerResult:
        if not ctx.is_image or ctx.channels is None:
            return AnalyzerResult(applicable=False, notes="not an image")
        channels = ctx.channels
        per_channel = []
        estimates: list[float] = []
        notes: list[str] = []
        for c in range(channels.shape[0]):
            plane = channels[c]
            h, w = plane.shape
            w4 = (w // 4) * 4
            if w4 < 4 or h < 1 or (h * (w4 // 4)) < MIN_GROUPS:
                notes.append(f"channel {c}: too few pixel groups")
                continue
            groups = plane[:, :w4].reshape(h, w4 // 4, 4)
            rate, details = estimate_rate(groups)
            details["channel"] = float(c)
            estimates.append(rate)
            per_channel.append(details)
        if not estimates:
            return AnalyzerResult(
                applicable=True, score=0.0, notes="insufficient groups for RS statistics",
                details={"per_channel": per_channel},
            )
        best = max(estimates, key=abs)
        score = _rate_to_score(best)
        note = (
            f"max |estimated LSB flip rate| {abs(best):.1%} "
            f"({'above clean-image bias' if best > 0.03 else 'within clean-image bias'})"
        )
        if ctx.jpeg_blockiness is not None and ctx.jpeg_blockiness > 1.5:
            note += "; JPEG-decompressed cover detected, spatial statistics downweighted"
        return AnalyzerResult(
            applicable=True,
            score=score,
            notes=note,
            details={"estimated_rate": round(best, 4), "per_channel": per_channel},
        )
