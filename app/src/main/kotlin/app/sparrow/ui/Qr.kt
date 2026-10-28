package app.sparrow.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.sparrow.core.geo.LensFacing
import app.sparrow.stream.CameraInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** Text of the QR code in the image at [uri], or null if there is none. */
fun decodeQrImage(context: Context, uri: Uri): String? = runCatching {
    val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: return null
    val scale = minOf(1.0, 1600.0 / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1.0) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else bitmap
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    val source = RGBLuminanceSource(scaled.width, scaled.height, pixels)
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
}.getOrNull()

/**
 * Back-camera viewfinder that reads QR codes and calls [onResult] once with the first one found.
 * Camera2 and ZXing only, so it works without Play services. Needs the camera permission.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    val info = remember {
        CameraInfo.all(context).let { all -> all.firstOrNull { it.facing == LensFacing.BACK } ?: all.firstOrNull() }
    }
    if (info == null) {
        LaunchedEffect(Unit) { error("No camera available") }
        return
    }
    val previewSize = remember(info) { CameraInfo.chooseSize(info.previewSizes.filter { it.width <= 1920 }, 1280, 960) }
    val scanSize = remember(info) { CameraInfo.chooseSize(info.yuvSizes, 1280, 960) }
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val aspect = previewSize.width.toFloat() / previewSize.height
    var surface by remember { mutableStateOf<Surface?>(null) }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFixedSize(previewSize.width, previewSize.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = Unit
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { surface = h.surface }
                    override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
                })
            }
        },
        modifier = modifier.aspectRatio(if (portrait) 1 / aspect else aspect),
    )

    DisposableEffect(surface) {
        val preview = surface ?: return@DisposableEffect onDispose {}
        val thread = HandlerThread("qr-scanner").apply { start() }
        val handler = Handler(thread.looper)
        val executor = java.util.concurrent.Executor { handler.post(it) }
        val main = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(scanSize.width, scanSize.height, ImageFormat.YUV_420_888, 2)
        val decoder = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
        // Touched only on the scanner thread (the dispose below is posted there too).
        var camera: CameraDevice? = null
        var closed = false
        var found = false
        reader.setOnImageAvailableListener({ r ->
            val text = r.acquireLatestImage()?.use { decodeLuma(decoder, it) }
            if (text != null && !found && !closed) {
                found = true
                main.post { result(text) }
            }
        }, handler)
        val fail = { msg: String -> main.post { error(msg) } }
        try {
            context.getSystemService(CameraManager::class.java).openCamera(info.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (closed) return c.close()
                    camera = c
                    val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface))
                    c.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (closed) return
                                runCatching {
                                    val request = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(preview)
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }.build()
                                    session.setRepeatingRequest(request, null, handler)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { fail("The camera could not start") }
                        }))
                }
                override fun onDisconnected(c: CameraDevice) = c.close()
                override fun onError(c: CameraDevice, e: Int) {
                    c.close()
                    fail("The camera is in use or unavailable")
                }
            })
        } catch (e: Exception) {
            fail("The camera is in use or unavailable")
        }
        onDispose {
            handler.post {
                closed = true
                camera?.close()
                reader.close()
                thread.quitSafely()
            }
        }
    }
}

/** QR text in the luma plane of a YUV frame, or null. QR codes read at any rotation. */
private fun decodeLuma(decoder: MultiFormatReader, image: Image): String? {
    val plane = image.planes[0]
    val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        decoder.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        decoder.reset()
    }
}
