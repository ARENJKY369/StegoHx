"""Media type sniffing and lightweight structural parsing.

Everything here is dependency-light on purpose: the analyzer service must be
deployable to a plain Python runtime with only numpy/Pillow installed.
"""

from __future__ import annotations

import io
import struct
import wave
from dataclasses import dataclass, field
from typing import Optional

import numpy as np
from PIL import Image

from .schemas import FileInfo, MediaKind

# (fourcc, description) atoms that are legitimately found at the top level of
# an ISO-BMFF container. Anything else at top level is unusual enough to note.
_COMMON_MP4_TOP_LEVEL_ATOMS = {
    b"ftyp", b"moov", b"mdat", b"free", b"skip", b"wide", b"pdin", b"meta",
    b"moof", b"mfra", b"styp", b"sidx", b"tfra", b"uuid", b"udta", b"prft",
}


@dataclass
class MediaContext:
    """Parsed, normalized view of the uploaded file used by the analyzers."""

    kind: MediaKind
    mime: str
    # Image media
    channels: Optional[np.ndarray] = None  # shape (n_channels, height, width), uint8
    # Audio media
    samples: Optional[np.ndarray] = None  # int16 sample array, shape (n,) or (n, n_channels)
    sample_rate: Optional[int] = None
    n_channels_audio: int = 0
    # Container media (mp4/mov/mkv) or raw bytes for 'other'
    raw: Optional[bytes] = None
    top_level_atoms: list[tuple[bytes, int, int]] = field(default_factory=list)  # (fourcc, offset, size)
    # Shared derived metrics
    jpeg_blockiness: Optional[float] = None  # ratio of 8x8 block-boundary energy vs interior

    @property
    def is_image(self) -> bool:
        return self.kind == MediaKind.IMAGE

    @property
    def is_audio(self) -> bool:
        return self.kind == MediaKind.AUDIO

    @property
    def is_video(self) -> bool:
        return self.kind == MediaKind.VIDEO


def sniff_mime(data: bytes, filename: str) -> str:
    """Best-effort MIME sniffing from magic bytes with extension fallback."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:2] == b"BM":
        return "image/bmp"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "audio/wav"
    if data[4:8] == b"ftyp":
        return "video/mp4"
    if data[:4] == b"\x1a\x45\xdf\xa3":
        return "video/x-matroska"
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    return {
        "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg",
        "bmp": "image/bmp", "gif": "image/gif", "webp": "image/webp",
        "wav": "audio/wav", "mp4": "video/mp4", "m4a": "video/mp4",
        "mov": "video/quicktime", "mkv": "video/x-matroska", "webm": "video/x-matroska",
    }.get(ext, "application/octet-stream")


def mime_to_kind(mime: str) -> MediaKind:
    if mime.startswith("image/"):
        return MediaKind.IMAGE
    if mime.startswith("audio/"):
        return MediaKind.AUDIO
    if mime.startswith("video/"):
        return MediaKind.VIDEO
    return MediaKind.OTHER


def decode_image(data: bytes) -> tuple[np.ndarray, str, int, int]:
    """Decode an image to a (channels, height, width) uint8 array.

    Returns (channels, mode, height, width). Alpha channels are dropped for
    analysis purposes (alpha planes are a separate embedding surface that the
    current statistical analyzers do not model).
    """
    img = Image.open(io.BytesIO(data))
    img.load()
    mode = img.mode
    if img.mode not in ("L", "RGB"):
        img = img.convert("RGB")
        mode = "RGB"
    arr = np.asarray(img, dtype=np.uint8)  # (H, W) or (H, W, 3)
    if arr.ndim == 2:
        channels = arr[np.newaxis, ...]
    else:
        channels = np.moveaxis(arr, -1, 0)  # (C, H, W)
    return channels, mode, img.height, img.width


def decode_wav(data: bytes) -> tuple[np.ndarray, int, int]:
    """Decode 16-bit PCM WAV to an int16 array (n_samples, n_channels)."""
    with wave.open(io.BytesIO(data), "rb") as wav:
        if wav.getsampwidth() != 2:
            raise ValueError("only 16-bit PCM WAV is supported by the audio analyzers")
        n_channels = wav.getnchannels()
        sample_rate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
    samples = np.frombuffer(frames, dtype="<i2").reshape(-1, n_channels)
    return samples, sample_rate, n_channels


def parse_mp4_atoms(data: bytes, max_atoms: int = 64) -> list[tuple[bytes, int, int]]:
    """Walk top-level ISO-BMFF atoms. Returns [(fourcc, offset, size), ...]."""
    atoms: list[tuple[bytes, int, int]] = []
    offset = 0
    total = len(data)
    while offset + 8 <= total and len(atoms) < max_atoms:
        size = struct.unpack_from(">I", data, offset)[0]
        fourcc = data[offset + 4 : offset + 8]
        header = 8
        if size == 1:  # 64-bit extended size
            if offset + 16 > total:
                break
            size = struct.unpack_from(">Q", data, offset + 8)[0]
            header = 16
        elif size == 0:  # extends to end of file
            size = total - offset
        if size < header or offset + size > total:
            break  # malformed; stop walking
        atoms.append((fourcc, offset, int(size)))
        offset += size
    return atoms


def blockiness_ratio(channels: np.ndarray) -> float:
    """Estimate how 'JPEG-like' an image is via 8x8 block boundary energy.

    Spatial LSB statistics are unreliable on decompressed JPEG covers
    (Fridrich et al.); the ensemble downweights spatial analyzers when this
    ratio is high.
    """
    ch = channels[0].astype(np.float32)
    h, w = ch.shape
    if h < 16 or w < 16:
        return 0.0
    dx = np.abs(np.diff(ch, axis=1))
    dy = np.abs(np.diff(ch, axis=0))
    col_boundary = dx[:, 7::8].mean() if dx.shape[1] > 8 else 0.0
    row_boundary = dy[7::8, :].mean() if dy.shape[0] > 8 else 0.0
    col_interior = np.delete(dx, np.s_[7::8], axis=1).mean() if dx.shape[1] > 8 else 0.0
    row_interior = np.delete(dy, np.s_[7::8], axis=0).mean() if dy.shape[0] > 8 else 0.0
    boundary = (col_boundary + row_boundary) / 2.0
    interior = (col_interior + row_interior) / 2.0
    if interior <= 1e-6:
        return 0.0
    return float(np.clip(boundary / interior, 0.0, 4.0))


def build_context(data: bytes, filename: str) -> tuple[MediaContext, FileInfo]:
    """Parse an uploaded file into a MediaContext plus basic file metadata."""
    mime = sniff_mime(data, filename)
    kind = mime_to_kind(mime)
    ctx = MediaContext(kind=kind, mime=mime)
    info = FileInfo(name=filename, size_bytes=len(data), mime=mime, kind=kind)

    if kind == MediaKind.IMAGE:
        try:
            channels, mode, height, width = decode_image(data)
            ctx.channels = channels
            ctx.jpeg_blockiness = blockiness_ratio(channels)
            info.width, info.height = width, height
        except Exception as exc:  # noqa: BLE001 - surface as a degraded analysis, not a crash
            ctx.kind = kind = MediaKind.OTHER
            ctx.raw = data
            info.kind = kind
            info.mime = mime
    elif kind == MediaKind.AUDIO:
        try:
            samples, sample_rate, n_channels = decode_wav(data)
            ctx.samples = samples
            ctx.sample_rate = sample_rate
            ctx.n_channels_audio = n_channels
            info.duration_samples = samples.shape[0]
            info.sample_rate = sample_rate
        except Exception:  # noqa: BLE001
            ctx.kind = kind = MediaKind.OTHER
            ctx.raw = data
            info.kind = kind
    elif kind == MediaKind.VIDEO:
        ctx.raw = data
        ctx.top_level_atoms = parse_mp4_atoms(data)
    else:
        ctx.raw = data
    return ctx, info
