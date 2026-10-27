# Third-party notices

| Component | Licence | Use |
| --- | --- | --- |
| [YOLO26n](https://github.com/ultralytics/ultralytics) weights, © Ultralytics | AGPL-3.0 | Object detection model, exported to ONNX by `tools/export_yolo26.py` (`app/src/main/assets/models/yolo26n.onnx`) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | On-device inference |
| [libsrt](https://github.com/Haivision/srt) via [srtdroid](https://github.com/ThibaultBee/srtdroid) | MPL-2.0 / Apache-2.0 | SRT transport |
| [ZXing](https://github.com/zxing/zxing) | Apache-2.0 | QR decoding |
| AndroidX, Jetpack Compose, Kotlin libraries | Apache-2.0 | App framework |

The YOLO26 weights are AGPL-3.0, so the app as distributed is too. The COCO class
names in `CocoClasses.kt` come from the COCO dataset (CC BY 4.0).
