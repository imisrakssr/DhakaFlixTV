package com.israk.dhakaflix.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Black-on-white QR code for [text] (scan with a phone camera). */
@Composable
fun QrCode(text: String, size: Dp = 180.dp, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        runCatching {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
            val w = m.width; val h = m.height
            val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp)) {
        if (bmp != null) Image(bmp, null, Modifier.size(size), filterQuality = FilterQuality.None)
    }
}
