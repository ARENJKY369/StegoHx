"""Container metadata heuristics for ISO-BMFF (MP4/MOV) and Matroska files.

Container-level steganography appends or injects data into structures that
demuxers skip: unknown top-level atoms, 'free'/'skip' padding atoms, or
unknown EBML elements. This analyzer:

  * walks top-level MP4 atoms and flags anything outside the common set
  * searches the whole container for StegoHX's own payload magic (``STGX``),
    which also flags files produced by this tool's video module
  * reports the share of the file occupied by padding atoms

It is also applicable to ``other`` files as a generic magic/structure triage.
"""

from __future__ import annotations

from ..media import MediaContext, _COMMON_MP4_TOP_LEVEL_ATOMS
from .base import Analyzer, AnalyzerResult, clamp01

STGX_MAGIC = b"STGX"


class ContainerMetaAnalyzer(Analyzer):
    name = "container_meta"
    display_name = "Container Metadata Heuristics"
    media_kinds = ("video", "other")
    weight = 0.60
    description = (
        "Walks ISO-BMFF top-level atoms and scans containers for embedded "
        "payload markers or unusual padding structures."
    )

    def run(self, ctx: MediaContext) -> AnalyzerResult:
        if ctx.raw is None:
            return AnalyzerResult(applicable=False, notes="no raw bytes available")
        raw = ctx.raw
        details: dict = {"size": len(raw)}

        magic_hits = [i for i in range(len(raw)) if raw.startswith(STGX_MAGIC, i)][:8]
        details["stgx_magic_hits"] = len(magic_hits)

        unknown_atoms = []
        free_bytes = 0
        if ctx.top_level_atoms:
            for fourcc, offset, size in ctx.top_level_atoms:
                if fourcc not in _COMMON_MP4_TOP_LEVEL_ATOMS:
                    unknown_atoms.append(fourcc.decode("latin-1", "replace"))
                if fourcc in (b"free", b"skip"):
                    free_bytes += size
            details["top_level_atoms"] = [
                {"fourcc": fc.decode("latin-1", "replace"), "size": size}
                for fc, _offset, size in ctx.top_level_atoms
            ]
            details["padding_share"] = round(free_bytes / max(1, len(raw)), 4)

        if magic_hits:
            score = 0.95
            note = "StegoHX payload magic found inside the container - embedded data is present"
        elif unknown_atoms:
            score = clamp01(0.3 + 0.15 * min(len(unknown_atoms), 3))
            note = f"uncommon top-level atom(s): {', '.join(unknown_atoms[:5])}"
        elif free_bytes / max(1, len(raw)) > 0.02:
            score = 0.35
            note = "unusually large padding (free/skip) atoms for a media container"
        else:
            score = 0.05
            note = "container structure looks conventional"

        return AnalyzerResult(applicable=True, score=score, notes=note, details=details)
