#!/usr/bin/env python3
"""StegoHX analyzer self-test.

Generates synthetic cover media (image + audio), produces LSB-embedded
versions, runs the full ensemble on both, and asserts the detectors separate
them. Also (re)writes the sample files under samples/ used by the console
demo and the engine integration tests.

Run:  python scripts/selftest.py [--samples-dir ../samples]
Exit code 0 = all separation assertions passed.
"""

from __future__ import annotations

import argparse
import io
import sys
import wave
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analyzers import Ensemble  # noqa: E402
from app.media import build_context  # noqa: E402
from app.schemas import Verdict  # noqa: E402

RNG = np.random.default_rng(1337)


def make_cover_image(w: int = 512, h: int = 384) -> np.ndarray:
    """A photographic-ish cover: smooth base + fixed-level value clustering.

    Real sensor output has rough, spike-bearing histograms; perfectly smooth
    synthetic gradients produce artificially equal pairs-of-values, which the
    chi-square test would read as embedding. Quantizing values onto fixed
    level clusters (with partial dither) reproduces the histogram roughness
    of camera output.
    """
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    base = (
        96
        + 64 * np.sin(x / 37.0 + y / 53.0)
        + 40 * np.sin(x / 11.0 - y / 17.0)
        + 18 * np.cos((x + y) / 7.0)
    )
    channels = []
    for k, scale in enumerate((1.0, 0.8, 1.15)):
        v = base * scale + 24 * k + RNG.normal(0, 1.0, size=(h, w))
        q = np.round(v / 4.0) * 4.0  # fixed-level clustering
        dither = np.where(RNG.random((h, w)) < 0.18, RNG.integers(-1, 2, (h, w)), 0)
        ch = np.clip(q + dither, 0, 255).astype(np.uint8)
        channels.append(ch)
    return np.stack(channels, axis=-1)  # (H, W, 3)


def embed_lsb_random(cover: np.ndarray, rate: float) -> np.ndarray:
    """LSB-replacement of a random subset of pixels (scattered embedding)."""
    stego = cover.copy()
    mask = RNG.random(cover.shape[:2]) < rate
    bits = RNG.integers(0, 2, size=cover.shape[:2], dtype=np.uint8)
    for c in range(cover.shape[2]):
        plane = stego[:, :, c]
        plane[mask] = (plane[mask] & 0xFE) | bits[mask]
    return stego


def make_cover_audio(rate: int = 8000) -> np.ndarray:
    """Loud passage (3 s) followed by a low-activity tail (1 s).

    The quiet tail mimics noise-gated / faded content where consecutive
    samples differ by only a few quantization steps - the regime in which
    LSB replacement is statistically visible.
    """
    t = np.arange(4 * rate) / rate
    loud = (
        12000 * np.sin(2 * np.pi * 220 * t)
        + 900 * np.sin(2 * np.pi * 587 * t)
        + 1500 * np.sin(2 * np.pi * 0.7 * t)
        + RNG.normal(0, 0.8, size=t.shape)
    )
    tq = t[3 * rate :] - 3.0
    quiet = (
        4.0 * np.sin(2 * np.pi * 0.5 * tq)
        + 1.5 * np.sin(2 * np.pi * 3.0 * tq)
        + RNG.normal(0, 0.15, size=tq.shape)
    )
    signal = loud.copy()
    signal[3 * rate :] = quiet
    return np.clip(signal, -32768, 32767).astype(np.int16)


def embed_audio_lsb(samples: np.ndarray, rate: float) -> np.ndarray:
    stego = samples.astype(np.int32)
    mask = RNG.random(len(stego)) < rate
    bits = RNG.integers(0, 2, size=len(stego), dtype=np.int8)
    stego[mask] = (stego[mask] & ~1) | bits[mask]
    return stego.astype(np.int16)


def wav_bytes(samples: np.ndarray, rate: int) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(samples.astype("<i2").tobytes())
    return buf.getvalue()


def png_bytes(arr: np.ndarray) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(arr).save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def run(ensemble: Ensemble, data: bytes, name: str):
    ctx, info = build_context(data, name)
    return ensemble.analyze(ctx, info)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples-dir", default=str(Path(__file__).resolve().parents[2] / "samples"))
    parser.add_argument("--embed-rate", type=float, default=0.30)
    args = parser.parse_args()

    ensemble = Ensemble()
    failures: list[str] = []
    rows = []

    # ---- image ----
    cover = make_cover_image()
    stego = embed_lsb_random(cover, args.embed_rate)
    clean_png, stego_png = png_bytes(cover), png_bytes(stego)
    r_clean = run(ensemble, clean_png, "clean.png")
    r_stego = run(ensemble, stego_png, "stego.png")
    rows += [("image/clean", r_clean), ("image/stego", r_stego)]

    if not (r_stego.threat_score - r_clean.threat_score > 0.25):
        failures.append(
            f"image separation too weak: clean={r_clean.threat_score} stego={r_stego.threat_score}"
        )
    if r_stego.threat_score < 0.55:
        failures.append(f"stego image not flagged: {r_stego.threat_score} ({r_stego.verdict})")
    if r_clean.threat_score > 0.35:
        failures.append(f"clean image falsely flagged: {r_clean.threat_score} ({r_clean.verdict})")

    # ---- audio ----
    rate = 8000
    a_cover = make_cover_audio(rate=rate)
    a_stego = embed_audio_lsb(a_cover, args.embed_rate)
    clean_wav, stego_wav = wav_bytes(a_cover, rate), wav_bytes(a_stego, rate)
    ra_clean = run(ensemble, clean_wav, "clean.wav")
    ra_stego = run(ensemble, stego_wav, "stego.wav")
    rows += [("audio/clean", ra_clean), ("audio/stego", ra_stego)]

    if not (ra_stego.threat_score - ra_clean.threat_score > 0.25):
        failures.append(
            f"audio separation too weak: clean={ra_clean.threat_score} stego={ra_stego.threat_score}"
        )
    if ra_stego.threat_score < 0.55:
        failures.append(f"stego audio not flagged: {ra_stego.threat_score} ({ra_stego.verdict})")
    if ra_clean.threat_score > 0.35:
        failures.append(f"clean audio falsely flagged: {ra_clean.threat_score} ({ra_clean.verdict})")

    # ---- report ----
    print(f"{'case':<14} {'threat':>7} {'conf':>6}  verdict")
    print("-" * 52)
    for label, r in rows:
        print(f"{label:<14} {r.threat_score:>7.3f} {r.confidence:>6.3f}  {r.verdict.value}")
        for f in r.analyzers:
            if f.applicable:
                print(f"    - {f.name:<16} {f.score:.3f}  {f.notes}")
    print()

    if failures:
        print("SELFTEST FAILED:")
        for f in failures:
            print("  -", f)
        return 1
    print("SELFTEST PASSED: detectors separate clean vs embedded media.")
    print(f"verdicts: image stego -> {r_stego.verdict.value}, audio stego -> {ra_stego.verdict.value}")

    # ---- write demo samples ----
    out = Path(args.samples_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "cover_photo.png").write_bytes(clean_png)
    (out / "stego_photo.png").write_bytes(stego_png)
    (out / "cover_audio.wav").write_bytes(clean_wav)
    (out / "stego_audio.wav").write_bytes(stego_wav)
    print(f"samples written to {out}/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
