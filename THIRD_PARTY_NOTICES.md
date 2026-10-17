# Third-party notices

| Component | Licence | Use |
| --- | --- | --- |
| [RF-DETR Nano](https://github.com/roboflow/rf-detr) weights, © Roboflow | Apache-2.0 | Object detection model, exported to ONNX and INT8-quantised by `tools/export_rfdetr.py` (`app/src/main/assets/models/rfdetr-nano-int8.onnx`) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | On-device inference |
| [libsrt](https://github.com/Haivision/srt) via [srtdroid](https://github.com/ThibaultBee/srtdroid) | MPL-2.0 / Apache-2.0 | SRT transport |
| [ZXing](https://github.com/zxing/zxing) | Apache-2.0 | QR decoding |
| AndroidX, Jetpack Compose, Kotlin libraries | Apache-2.0 | App framework |

RF-DETR's XL and 2XL "platform" models use a different licence and are not used. The COCO class
names in `CocoClasses.kt` come from the COCO dataset (CC BY 4.0).
