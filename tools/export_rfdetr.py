#!/usr/bin/env python3
"""Rebuilds app/src/main/assets/models/rfdetr-nano-int8.onnx.

RF-DETR Nano (Roboflow, Apache-2.0) is exported to ONNX at 384x384 and its MatMul weights are
dynamically quantised to INT8 (34 MB instead of 108 MB). On COCO val2017 samples the INT8 model
keeps about 95% of the float32 detections at a 0.5 threshold.

Usage (CPU only):
    python3 -m venv venv
    venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
    venv/bin/pip install "rfdetr[onnx]==1.11.2" onnxruntime
    venv/bin/python tools/export_rfdetr.py

Model contract used by the app (RfDetrDetector):
    input  "input"  float32 [1, 3, 384, 384]  RGB, bilinear resize (stretched), /255, ImageNet mean/std
    output "dets"   float32 [1, 300, 4]       cx, cy, w, h normalised to the input
    output "labels" float32 [1, 300, 91]      logits; index = COCO category id; sigmoid for scores
"""

import hashlib
import pathlib
import tempfile

from onnxruntime.quantization import QuantType, quantize_dynamic
from rfdetr import RFDETRNano

OUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/models/rfdetr-nano-int8.onnx"


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        fp32 = RFDETRNano().export(output_dir=tmp, format="onnx", verbose=False)
        quantize_dynamic(str(pathlib.Path(tmp) / fp32), str(OUT), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
    print(OUT, OUT.stat().st_size // 1_000_000, "MB", hashlib.sha256(OUT.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
