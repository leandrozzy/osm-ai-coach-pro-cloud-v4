package com.osmaicoach.collector

import android.graphics.Bitmap
import android.graphics.Color

object BitmapFingerprint {
    // Hash perceptual simples 8x8: suficiente para descartar telas praticamente idênticas.
    fun aHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val values = IntArray(64)
        var sum = 0L
        var i = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = scaled.getPixel(x, y)
            val gray = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            values[i++] = gray
            sum += gray
        }
        if (scaled !== bitmap) scaled.recycle()
        val avg = sum / 64
        var hash = 0L
        for (bit in values.indices) if (values[bit] >= avg) hash = hash or (1L shl bit)
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
