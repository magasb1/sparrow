#!/usr/bin/env python3
"""Rebuilds app/src/main/assets/models/yolo26n.onnx.

YOLO26n (Ultralytics, AGPL-3.0) is exported to ONNX at 512x512 with its end-to-end (NMS-free)
head, so the graph ends in the 300 final detections and the app needs no NMS. Float32: the model
is small (2.4M parameters) and ORT's dynamic INT8 only covers MatMul, which a CNN barely uses.

Usage (CPU only):
    python3 -m venv venv
    venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
    venv/bin/pip install "ultralytics==8.4.191" onnx onnxslim onnxruntime
    venv/bin/python tools/export_yolo26.py

Model contract used by the app (Yolo in core/cv):
    input  "images"  float32 [1, 3, 512, 512]  RGB /255, letterboxed (aspect kept, grey 114 padding)
    output "output0" float32 [1, 300, 6]       x1, y1, x2, y2 in input pixels, score, class (0-79)
"""

import hashlib
import pathlib
import shutil
import tempfile

from ultralytics import YOLO

OUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/models/yolo26n.onnx"


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        model = YOLO(str(pathlib.Path(tmp) / "yolo26n.pt"))
        # nms=False selects the one-to-one (NMS-free) head; the default exports the raw anchor head.
        onnx = model.export(format="onnx", imgsz=512, nms=False, simplify=True, dynamic=False)
        shutil.copyfile(onnx, OUT)
    print(OUT, OUT.stat().st_size // 1_000_000, "MB", hashlib.sha256(OUT.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
