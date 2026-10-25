package com.music115.tv.ui

import android.graphics.Bitmap

/**
 * 封面 → 高斯模糊背景：先把原图大幅降采样（模糊半径的廉价替代），
 * 对小图做 3 轮可分离盒式模糊（近似高斯），再放大回屏幕尺寸。
 * 全程在小尺寸位图上运算，弱核电视盒子也是毫秒级。
 */
object BitmapBlur {

    /** 降采样倍数：值越大越模糊 */
    private const val DOWN_SAMPLE = 24
    private const val PASSES = 3

    fun blurredBackground(src: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val w = (src.width / DOWN_SAMPLE).coerceAtLeast(8)
        val h = (src.height / DOWN_SAMPLE).coerceAtLeast(8)
        var cur = Bitmap.createScaledBitmap(src, w, h, true)
        repeat(PASSES) { cur = boxBlur(cur) }
        return Bitmap.createScaledBitmap(cur, outWidth, outHeight, true)
    }

    /** 单轮 3x3 邻域均值（水平/垂直分离，边缘取近邻） */
    private fun boxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mid = IntArray(w * h)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                mid[row + x] = avg3(
                    px[row + (x - 1).coerceAtLeast(0)],
                    px[row + x],
                    px[row + (x + 1).coerceAtMost(w - 1)],
                )
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                px[y * w + x] = avg3(
                    mid[(y - 1).coerceAtLeast(0) * w + x],
                    mid[y * w + x],
                    mid[(y + 1).coerceAtMost(h - 1) * w + x],
                )
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun avg3(a: Int, b: Int, c: Int): Int {
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((c shr 16) and 0xFF)) / 3
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((c shr 8) and 0xFF)) / 3
        val bl = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF)) / 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
