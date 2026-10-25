package com.music115.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.music115.tv.App
import com.music115.tv.R
import com.music115.tv.data.FolderInfo
import com.music115.tv.data.FolderItem
import com.music115.tv.data.Listing
import kotlinx.coroutines.launch

/**
 * 音乐文件夹引导选择页：登录后未设定曲库目录时弹出。
 * 列出网盘根目录下的文件夹，遥控器选中即保存（建议行是「音乐」）。
 */
class FolderPickerFragment : Fragment(R.layout.fragment_folder_picker) {

    private val locator by lazy { (requireActivity().application as App).locator }
    private val adapter by lazy {
        FolderAdapter { folder -> onPicked(folder) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.button_skip_setup).setOnClickListener {
            (activity as? MainHost)?.hideFolderPicker()
        }
        loadRoot()
    }

    private fun loadRoot() {
        val view = view ?: return
        view.findViewById<ProgressBar>(R.id.progress).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.error_text).visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val listing: Listing = locator.listingRepository.folder("0", 0)
                val folders = listing.items.mapNotNull { (it as? FolderItem)?.folder }
                if (folders.isEmpty()) {
                    showError("根目录下没有文件夹，请先在 115 网盘里整理好目录")
                } else {
                    adapter.submit(folders)
                }
            } catch (e: Exception) {
                showError("读取根目录失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
            }
        }
    }

    private fun showError(message: String) {
        val view = view ?: return
        view.findViewById<TextView>(R.id.error_text).text = message
        view.findViewById<TextView>(R.id.error_text).visibility = View.VISIBLE
    }

    private fun onPicked(folder: FolderInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            locator.playbackPrefs.setMusicFolder(folder)
            locator.listingRepository.onMusicFolderChanged()
            locator.listingRepository.invalidate()
            Toast.makeText(
                requireContext(),
                "音乐文件夹已设为「${folder.name}」",
                Toast.LENGTH_SHORT,
            ).show()
            (activity as? MainHost)?.hideFolderPicker()
        }
    }

    /** 极简根目录文件夹列表适配器 */
    private class FolderAdapter(
        private val onClick: (FolderInfo) -> Unit,
    ) : RecyclerView.Adapter<FolderAdapter.VH>() {

        private val items = mutableListOf<FolderInfo>()

        fun submit(newItems: List<FolderInfo>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onClick)

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val title = view.findViewById<TextView>(R.id.title)
            fun bind(folder: FolderInfo, onClick: (FolderInfo) -> Unit) {
                title.text = folder.name + if (folder.name == "音乐") "（推荐）" else ""
                itemView.setOnClickListener { onClick(folder) }
            }
        }
    }
}
