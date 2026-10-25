package com.music115.tv.ui

import android.view.View

/** TV 焦点态：聚焦时轻微放大（配合背景描边 selector 形成国产 TV 惯用的焦点观感） */
fun View.bindFocusScale(scale: Float = 1.05f, durationMs: Long = 120L) {
    setOnFocusChangeListener { v, hasFocus ->
        v.animate()
            .scaleX(if (hasFocus) scale else 1f)
            .scaleY(if (hasFocus) scale else 1f)
            .setDuration(durationMs)
            .start()
    }
}
