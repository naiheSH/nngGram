package com.badnng.moe

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ComposePixelFrameReadTest {
    @Test fun bulkReadPreservesNativeArgbWithTransparentPixelsAndBothChannelOrders() {
        for (type in listOf(ColorType.RGBA_8888, ColorType.BGRA_8888)) {
            for (alphaType in listOf(ColorAlphaType.PREMUL, ColorAlphaType.UNPREMUL)) {
                val bytes = ByteArray(7 * 5 * 4) { offset ->
                    val pixel = offset / 4
                    val alpha = listOf(0, 64, 128, 255)[pixel % 4]
                    val component = when (offset % 4) {
                        0 -> pixel * 37 % 256
                        1 -> pixel * 71 % 256
                        2 -> pixel * 19 % 256
                        else -> alpha
                    }
                    (if (offset % 4 < 3 && alphaType == ColorAlphaType.PREMUL)
                        component * alpha / 255 else component).toByte()
                }
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.installPixels(ImageInfo(7, 5, type, alphaType), bytes, 7 * 4))
                    val frame = bitmap.copyPixelFrame()
                    for (y in 0 until 5) for (x in 0 until 7) {
                        val expected = bitmap.getColor(x, y)
                        val actual = frame[x, y]
                        for (shift in listOf(0, 8, 16, 24)) assertTrue(
                            abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 1,
                            "$type/$alphaType at $x/$y channel $shift: $expected != $actual")
                    }
                }
            }
        }
    }
}
