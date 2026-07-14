package com.cyma.videoloop.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a square QR code [ImageBitmap], or null if encoding fails.
 * [sizePx] is the target edge length in pixels.
 */
fun generateQrBitmap(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if (matrix[x, y]) Color.BLACK else Color.WHITE
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        .asImageBitmap()
}.getOrNull()

/**
 * Builds a `WIFI:` payload that phone camera/QR apps recognise as "join this
 * network", so scanning it connects the phone to the setup hotspot in one tap.
 * Open networks (no [passphrase]) use `T:nopass`.
 */
fun wifiQrPayload(ssid: String, passphrase: String?): String {
    val s = ssid.escapeWifiQr()
    return if (passphrase.isNullOrEmpty()) {
        "WIFI:S:$s;T:nopass;;"
    } else {
        "WIFI:S:$s;T:WPA;P:${passphrase.escapeWifiQr()};;"
    }
}

/** Escapes the characters that are special inside a `WIFI:` payload. */
private fun String.escapeWifiQr(): String = buildString {
    for (c in this@escapeWifiQr) {
        if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
        append(c)
    }
}
