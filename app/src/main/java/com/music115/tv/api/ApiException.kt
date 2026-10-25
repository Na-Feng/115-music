package com.music115.tv.api

/** 会话过期（errNo 990001），UI 层据此触发 WAF 刷新或重扫码 */
class SessionExpiredException : Exception("115 session expired (990001)")

/** 其他 API 错误 */
class ApiException(message: String, val errNo: Int = -1, val detail: String? = null) :
    Exception(if (detail != null) "$message ($detail)" else message)
