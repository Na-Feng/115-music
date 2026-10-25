package com.music115.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.music115.tv.R
import com.music115.tv.api.ApiLog

/** 日志页：全屏查看诊断日志，进页自动滚到底部 */
class LogFragment : Fragment(R.layout.fragment_log) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val text = view.findViewById<TextView>(R.id.log_text)
        val scroll = view.findViewById<ScrollView>(R.id.log_scroll)

        fun refresh() {
            text.text = ApiLog.dump()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        view.findViewById<Button>(R.id.button_log_refresh).setOnClickListener { refresh() }
        view.findViewById<Button>(R.id.button_log_clear).setOnClickListener {
            ApiLog.clear()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val text = view?.findViewById<TextView>(R.id.log_text) ?: return
        val scroll = view?.findViewById<ScrollView>(R.id.log_scroll) ?: return
        text.text = ApiLog.dump()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
