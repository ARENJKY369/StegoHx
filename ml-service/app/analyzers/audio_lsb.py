"""Audio LSB steganalysis for 16-bit PCM WAV.

Detection strategy
------------------
LSB replacement is only statistically visible in *low-activity* passages -
segments where consecutive samples differ by a few quantization steps. In
loud, full-scale passages the low byte of natural audio is already
near-uniform, so no statistical test can separate embedded from clean bits
there (a documented limitation, not specific to this tool).

The analyzer therefore:

  1. selects groups of 4 consecutive samples whose internal steps are all
     <= 3 quantization levels ("quiet" groups)
  2. runs RS analysis over those groups (primary signal, works at partial
     embedding rates)
  3. runs the chi-square PoV test on the low byte of quiet-segment samples
     (fires at high embedding rates)
  4. records LSB / bit-1 autocorrelations as report details

If the recording contains too little quiet content, the analyzer falls back
to a full-stream chi-square pass and reports reduced sensitivity.
"""

from __future__ import annotations

import numpy as np

from ..media import MediaContext
from .base import Analyzer, AnalyzerResult, clamp01
from .chi_square import _chi_square_p_value, _score_from_p
from .rs_analysis import _rate_to_score, estimate_rate

MAX_QUIET_STEP = 3  # max |diff| between adjacent samples in a quiet group
MIN_QUIET_GROUPS = 2000


def _autocorr_bit(bits: np.ndarray) -> float:
    x = bits.astype(np.float64)
    x = x - x.mean()
    denom = float((x * x).sum())
    if denom < 1e-9:
        return 0.0
    return float((x[:-1] * x[1:]).sum() / denom)


def _quiet_selection(samples: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Returns (quiet_groups (n,4), quiet_values (m,)) as int32 arrays."""
    d = np.abs(np.diff(samples))
    n = len(samples)
    if n < 8:
        return np.zeros((0, 4), dtype=np.int32), samples.astype(np.int32)
    # group i is eligible when diffs i, i+1, i+2 are all small
    elig = (d[0 : n - 4] <= MAX_QUIET_STEP) & (d[1 : n - 3] <= MAX_QUIET_STEP) & (d[2 : n - 2] <= MAX_QUIET_STEP)
    idx = np.where(elig)[0]
    if len(idx) == 0:
        return np.zeros((0, 4), dtype=np.int32), np.zeros(0, dtype=np.int32)
    groups = samples[idx[:, None] + np.arange(4)[None, :]].astype(np.int32)
    positions = np.unique((idx[:, None] + np.arange(4)[None, :]).ravel())
    return groups, samples[positions].astype(np.int32)


class AudioLSBAnalyzer(Analyzer):
    name = "audio_lsb"
    display_name = "Audio LSB Statistics"
    media_kinds = ("audio",)
    weight = 1.0
    description = (
        "RS analysis and chi-square PoV tests over low-activity PCM segments; "
        "detects LSB replacement in WAV audio at partial embedding rates."
    )

    def run(self, ctx: MediaContext) -> AnalyzerResult:
        if not ctx.is_audio or ctx.samples is None:
            return AnalyzerResult(applicable=False, notes="not a decodable 16-bit PCM WAV")
        samples = ctx.samples
        per_channel = []
        rs_scores = []
        chi_scores = []
        quiet_ratio = 0.0
        for c in range(samples.shape[1]):
            ch = samples[:, c].astype(np.int32)
            groups, quiet_values = _quiet_selection(ch)
            quiet_ratio = max(quiet_ratio, len(quiet_values) / max(1, len(ch)))
            rs_rate = 0.0
            if len(groups) >= MIN_QUIET_GROUPS:
                rs_rate, rs_details = estimate_rate(groups)
                rs_scores.append(_rate_to_score(rs_rate))
            else:
                rs_details = {"status": "insufficient quiet content"}
            # chi-square on the low byte of quiet segments (structure exists
            # there); fall back to the full stream with reduced sensitivity
            if len(quiet_values) >= MIN_QUIET_GROUPS:
                low = (quiet_values & 0xFF).astype(np.uint8)
                region = "quiet segments"
            else:
                low = (ch & 0xFF).astype(np.uint8)
                region = "full stream (fallback)"
            hist = np.bincount(low, minlength=256)
            p, dof = _chi_square_p_value(hist)
            chi_scores.append(_score_from_p(p))
            ac0 = _autocorr_bit((ch & 1).astype(np.int8))
            ac1 = _autocorr_bit(((ch >> 1) & 1).astype(np.int8))
            per_channel.append(
                {
                    "channel": float(c),
                    "rs_estimated_rate": round(rs_rate, 4),
                    "rs_status": rs_details.get("status", "ok"),
                    "chi_region": region,
                    "chi_p_value": round(p, 6),
                    "dof": float(dof),
                    "lsb_autocorr": round(ac0, 4),
                    "bit1_autocorr": round(ac1, 4),
                    "quiet_sample_ratio": round(len(quiet_values) / max(1, len(ch)), 4),
                    **{k: v for k, v in rs_details.items() if k in ("R_M", "S_M", "R_-M", "S_-M")},
                }
            )
        if rs_scores:
            score = clamp01(0.75 * max(rs_scores) + 0.25 * max(chi_scores))
            worst = max(per_channel, key=lambda d: abs(d["rs_estimated_rate"]))
            note = (
                f"worst channel: RS rate estimate {worst['rs_estimated_rate']:.1%} over quiet segments, "
                f"chi p={max(d['chi_p_value'] for d in per_channel):.4f} ({worst['chi_region']}); "
                + ("sample-plane statistics indicate LSB replacement"
                   if score > 0.4 else "sample-plane statistics look natural")
            )
        else:
            score = clamp01(max(chi_scores))
            note = (
                "little low-activity content; RS analysis unavailable, sensitivity reduced "
                f"(full-stream chi p={max(d['chi_p_value'] for d in per_channel):.4f})"
            )
        return AnalyzerResult(
            applicable=True,
            score=score,
            notes=note,
            details={
                "sample_rate": ctx.sample_rate,
                "n_samples": int(samples.shape[0]),
                "quiet_sample_ratio": round(quiet_ratio, 4),
                "per_channel": per_channel,
            },
        )
