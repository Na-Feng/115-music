package com.music115.tv.ui

import android.content.Context
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.music115.tv.R

/**
 * 歌词列表：一行一项。切换当前行时只重绑新旧两行（旧实现每 0.5s 重建整段 Spannable，
 * 长歌词在电视盒子上明显掉帧）。当前行高亮放大，其余行半透明。
 */
class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private var lines: List<String> = emptyList()
    /** 当前行；-1 = 无高亮（静态歌词/加载提示） */
    var current = -1
        private set

    fun submit(newLines: List<String>) {
        lines = newLines
        current = -1
        notifyDataSetChanged()
    }

    fun setCurrent(index: Int) {
        if (index == current) return
        val old = current
        current = index
        if (old < 0 || index < 0) {
            // 有/无高亮切换会改变所有行的透明度
            notifyItemRangeChanged(0, lines.size, PAYLOAD_HIGHLIGHT)
            return
        }
        if (old in lines.indices) notifyItemChanged(old, PAYLOAD_HIGHLIGHT)
        if (index in lines.indices) notifyItemChanged(index, PAYLOAD_HIGHLIGHT)
    }

    override fun getItemCount() = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            gravity = Gravity.CENTER
            val padV = (10 * resources.displayMetrics.density).toInt()
            val padH = (36 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setLineSpacing(0f, 1.1f)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = lines[position]
        applyState(holder.text, position, animate = false)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_HIGHLIGHT)) {
            applyState(holder.text, position, animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun applyState(tv: TextView, position: Int, animate: Boolean) {
        val ctx = tv.context
        val active = position == current
        val noHighlight = current < 0
        tv.setTextColor(
            ContextCompat.getColor(ctx, if (active) R.color.accent else R.color.text_primary)
        )
        tv.paint.isFakeBoldText = active
        tv.invalidate()
        val scale = if (active) 1.12f else 1f
        val alpha = when {
            active -> 1f
            noHighlight -> 0.85f
            else -> 0.45f
        }
        // View 默认以自身中心为缩放轴，配合居中文本即「行中心放大」
        tv.animate().cancel()
        if (animate) {
            tv.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(220).start()
        } else {
            tv.scaleX = scale; tv.scaleY = scale; tv.alpha = alpha
        }
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    companion object {
        private const val PAYLOAD_HIGHLIGHT = "highlight"

        /** 平滑滚动，使目标行停在列表垂直居中位置 */
        fun scrollToCenter(list: RecyclerView, position: Int) {
            val lm = list.layoutManager as? LinearLayoutManager ?: return
            val scroller = CenterScroller(list.context).apply { targetPosition = position }
            lm.startSmoothScroll(scroller)
        }
    }

    private class CenterScroller(context: Context) : LinearSmoothScroller(context) {
        override fun calculateDtToFit(
            viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int,
        ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)

        // 比默认稍慢，滚动更柔和
        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
            60f / displayMetrics.densityDpi
    }
}
