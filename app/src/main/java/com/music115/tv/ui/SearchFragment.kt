package com.music115.tv.ui

import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import com.music115.tv.R
import com.music115.tv.data.Listing

/** 搜索页：遥控器/键盘输入 + IME 搜索动作触发 */
class SearchFragment : ListBaseFragment() {

    private var keyword = ""

    override val layoutId: Int = R.layout.fragment_search

    override suspend fun fetchPage(offset: Int): Listing {
        if (keyword.isEmpty()) return Listing("0", emptyList(), emptyList(), 0)
        return locator.listingRepository.search(keyword, offset)
    }

    override fun onBaseViewCreated(view: View) {
        val input = view.findViewById<EditText>(R.id.search_input)
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                doSearch(input.text.toString())
                true
            } else {
                false
            }
        }
    }

    fun doSearch(raw: String) {
        keyword = raw.trim()
        if (keyword.isNotEmpty()) {
            loadInitial(refresh = true)
        }
    }

    override fun queueTitle(): String = "搜索：$keyword"
}
