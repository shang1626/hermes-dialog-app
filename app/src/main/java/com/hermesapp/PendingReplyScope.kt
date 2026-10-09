package com.hermesapp

/**
 * 通知栏回复的身份闸门（F18）。
 *
 * 问题：通知栏直接回复的暂存里只有「会话 id + 文本」，**没有身份**。切到另一个身份
 * （default ↔ friend）之后，旧身份下打的那句话仍会被当成当前身份的用户消息发出去——
 * 发错人、进错会话的账，而且用户完全看不出来（他在通知栏里打字的那个入口已经过期了）。
 *
 * [storedProfile] 为空表示升级前写下的旧数据（那时还没有这个字段），放行一次，不折腾用户。
 * 纯函数，便于单测钉住三种情形。
 */
internal fun pendingReplyAllowed(storedProfile: String, currentProfile: String): Boolean =
    storedProfile.isEmpty() || storedProfile == currentProfile
