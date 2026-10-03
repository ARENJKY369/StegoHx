"""Pluggable ONNX model inference (optional).

The ensemble is fully functional without any trained model. To plug a
deep steganalysis model in (e.g., a CNN trained on cover/stego pairs such as
Ye-Net or Xu-Net style architectures), export it to ONNX with the contract
below and point ``STEGOHX_MODEL_PATH`` at it:

    input : float32 tensor, shape (1, 3, 256, 256), RGB in [0, 1]
    output: float32 tensor, shape (1,) - sigmoid probability of stego

The model's score joins the statistical ensemble with a dominant weight
(see ensemble.py). Errors degrade gracefully back to the statistical
ensemble with a note - the service never fails because of the model.
"""

from __future__ import annotations

import os
from typing import Optional

import numpy as np
from PIL import Image

from ..media import MediaContext


class ModelInference:
    def __init__(self) -> None:
        self.path: Optional[str] = os.environ.get("STEGOHX_MODEL_PATH")
        self.session = None
        self.input_name: Optional[str] = None
        if not self.path or not os.path.isfile(self.path):
            self.path = None if not self.path else self.path
            return
        try:
            import onnxruntime as ort  # optional dependency

            self.session = ort.InferenceSession(self.path, providers=["CPUExecutionProvider"])
            self.input_name = self.session.get_inputs()[0].name
        except Exception:  # noqa: BLE001 - degrade to ensemble
            self.session = None

    @property
    def loaded(self) -> bool:
        return self.session is not None

    def analyze(self, ctx: MediaContext) -> tuple[Optional[float], str]:
        """Returns (score, note). Score is None when the model cannot run."""
        if self.session is None:
            return None, "no ONNX model loaded"
        if not ctx.is_image or ctx.channels is None:
            return None, "model only supports image media"
        try:
            arr = np.moveaxis(ctx.channels, 0, -1)  # (H, W, 3) or (H, W)
            if arr.ndim == 2:
                arr = np.stack([arr] * 3, axis=-1)
            img = Image.fromarray(arr.astype(np.uint8)).resize((256, 256), Image.BILINEAR)
            x = np.asarray(img, dtype=np.float32) / 255.0
            x = np.moveaxis(x, -1, 0)[np.newaxis, ...]  # (1, 3, 256, 256)
            out = self.session.run(None, {self.input_name: x})
            score = float(np.asarray(out[0]).reshape(-1)[0])
            return min(max(score, 0.0), 1.0), "ONNX model inference completed"
        except Exception as exc:  # noqa: BLE001
            return None, f"model inference failed: {exc}"

    def warmup(self) -> None:
        """No-op kept for symmetry; ONNX sessions initialize lazily."""
        return None
