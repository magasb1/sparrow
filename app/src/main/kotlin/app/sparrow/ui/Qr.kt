package app.sparrow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
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
