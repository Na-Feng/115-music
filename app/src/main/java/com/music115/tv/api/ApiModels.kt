package com.music115.tv.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ApiState {
    val state: Boolean
    val errNo: Int
    val error: String?
}

@Serializable
data class FilesResponse(
    override val state: Boolean = false,
    override val errNo: Int = 0,
    override val error: String? = null,
    val data: List<EntryDto> = emptyList(),
    val count: Int = 0,
    val path: List<PathDto> = emptyList(),
) : ApiState

/**
 * 目录项（fid 为空）与文件项（fid 非空）共用结构。
 * 文件项关键fd字段：n 文件名、s 字节数、pc pickcode（取直链用）、ico 扩展名、
 * play_long 服务端时长（秒）、cid 父目录。
 */
@Serializable
data class EntryDto(
    val fid: String? = null,
    val cid: String? = null,
    val pid: String? = null,
    val n: String = "",
    val s: Long = 0,
    val pc: String = "",
    val ico: String? = null,
    @SerialName("play_long") val playLong: Int = 0,
    val t: String? = null,
) {
    val isFolder: Boolean get() = fid == null
}

@Serializable
data class PathDto(
    val name: String? = null,
    val cid: String? = null,
)

@Serializable
data class DownloadResponse(
    override val state: Boolean = false,
    override val errNo: Int = 0,
    override val error: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
) : ApiState
