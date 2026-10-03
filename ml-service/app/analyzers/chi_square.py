"""Chi-square (Pairs of Values) attack - Westfeld & Pfitzmann.

LSB replacement forces the two members of each pair (2i, 2i+1) toward equal
frequency. This test asks: "are the pair frequencies consistent with a fully
randomized LSB plane in the analyzed region?" A p-value close to 1.0 is
strong evidence of embedding *in that region*.

Reference: A. Westfeld, A. Pfitzmann, "Attacks on Steganographic Systems",
Information Hiding, 1999. Formulas follow Fridrich's review
"Practical Steganalysis of Digital Images - State of the Art" (2001).

The chi-square CDF is evaluated with the Wilson-Hilferty normal
approximation (accurate to ~1e-3 for k >= 30 dof, ample for scoring
purposes) so the service stays scipy-free.
"""

from __future__ import annotations

import math

import numpy as np

from ..media import MediaContext
from .base import Analyzer, AnalyzerResult, clamp01

# Fractions of the raster scan over which the test is repeated. Sequential
# embedding is exposed by a p-value that stays ~1.0 over every prefix that
# covers the message and collapses after its end.
_PREFIX_FRACTIONS = (0.125, 0.25, 0.5, 1.0)


def _chi_square_p_value(counts: np.ndarray) -> tuple[float, int]:
    """Chi-square test of PoV equality over a histogram.

    ``counts`` is the 256-bin histogram of the region. Returns
    (p_value, degrees_of_freedom) where the p-value is P(chi2 >= observed)
    under the "pairs are equalized" hypothesis - i.e. a p-value close to 1.0
    means the region is statistically consistent with a randomized LSB plane
    (embedded), while natural pair asymmetry drives it toward 0.
    """
    expected = counts[0::2] + counts[1::2]  # 2 * n'_i
    observed = counts[0::2]
    mask = expected > 4  # bins with negligible mass are excluded
    if mask.sum() < 8:
        return 0.0, 0
    obs = observed[mask].astype(np.float64)
    exp = (expected[mask] / 2.0).astype(np.float64)
    chi2 = float(((obs - exp) ** 2 / exp).sum())
    k = int(mask.sum()) - 1
    if k < 1:
        return 0.0, 0
    # Wilson-Hilferty approximation of the chi-square CDF:
    # CDF = P(chi2_k <= observed); the p-value of the test is 1 - CDF.
    t = (chi2 / k) ** (1.0 / 3.0) - (1.0 - 2.0 / (9.0 * k))
    t /= math.sqrt(2.0 / (9.0 * k))
    cdf = 0.5 * (1.0 + math.erf(t / math.sqrt(2.0)))
    p = 1.0 - cdf
    return float(min(max(p, 0.0), 1.0)), k


def _score_from_p(p: float) -> float:
    """Map a p-value to a 0..1 suspicion score.

    Under the clean hypothesis p is ~Uniform(0,1), so the mapping is shaped
    to keep the false-positive rate negligible: only p-values above ~0.9
    contribute meaningfully (p^8 keeps P(score > 0.43 | clean) < 1%).
    """
    return clamp01(p ** 8)


class ChiSquareAnalyzer(Analyzer):
    name = "chi_square"
    display_name = "Chi-Square PoV Attack"
    media_kinds = ("image",)
    weight = 0.25
    description = (
        "Westfeld/Pfitzmann pairs-of-values histogram test; strongest against "
        "sequential LSB replacement, evaluated over multiple raster prefixes."
    )

    def run(self, ctx: MediaContext) -> AnalyzerResult:
        if not ctx.is_image or ctx.channels is None:
            return AnalyzerResult(applicable=False, notes="not an image")
        channels = ctx.channels
        per_channel: list[dict[str, float]] = []
        best_p = 0.0
        best_where = "no region reached significance"
        for c in range(channels.shape[0]):
            flat = channels[c].reshape(-1)
            for frac in _PREFIX_FRACTIONS:
                n = max(256, int(flat.size * frac))
                hist = np.bincount(flat[:n], minlength=256)
                p, dof = _chi_square_p_value(hist)
                if p > best_p:
                    best_p = p
                    best_where = f"channel {c}, first {frac:.1%} of raster"
                per_channel.append(
                    {"channel": float(c), "prefix": frac, "p_value": round(p, 6), "dof": float(dof)}
                )
        score = _score_from_p(best_p)
        notes = (
            f"max p-value {best_p:.4f} at {best_where}; "
            + (
                "pair frequencies are consistent with a randomized LSB plane"
                if best_p > 0.9
                else "no pair-of-value equalization detected"
            )
        )
        if ctx.jpeg_blockiness is not None and ctx.jpeg_blockiness > 1.5:
            notes += "; cover appears JPEG-decompressed, spatial statistics downweighted"
        return AnalyzerResult(
            applicable=True,
            score=score,
            notes=notes,
            details={
                "max_p_value": round(best_p, 6),
                "location": best_where,
                "per_region": per_channel[-8:],  # keep the payload small: last channel's prefixes
                "prefix_fractions": list(_PREFIX_FRACTIONS),
            },
        )
