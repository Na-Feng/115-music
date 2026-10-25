package com.music115.tv.auth

/** 会话状态：LOGGED_OUT 无登录态；OK 可用；EXPIRED 需刷新挑战 Cookie 或重新扫码 */
enum class SessionState { LOGGED_OUT, OK, EXPIRED }
