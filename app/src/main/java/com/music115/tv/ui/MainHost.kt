package com.music115.tv.ui

/** MainActivity 对各 Fragment 暴露的导航能力 */
interface MainHost {
    fun showLogin()
    fun hideLogin()
    fun openPlayerScreen()
    fun showFolderPicker()
    fun hideFolderPicker()
}
