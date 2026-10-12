package com.hermesapp

/**
 * 语音排队/重播的两条纯逻辑（不碰 Android，可单测）。
 *
 * 为什么单独抽出来：这两条分别对应两个真实报障的判据 ——
 * 「播放按钮点了没声 / 重播少一截」与「任务完成了却没有自动播报」，
 * 必须有可回归的单测钉住，不能只靠真机手感。
 */
object VoiceCacheMark {

    /**
     * 本机这份 mp3 能不能直接拿来重播。
     *
     * markerBytes = 语音流正常收尾（收到 audio.end 且这条没被摘除）时记下的字节数；
     * fileLen = 现在文件的实际长度。两者必须相等才敢用本机文件。
     *
     * 为什么不能只看「文件存在且非空」：被「跳过 / 让位 / 手动重播」摘掉的那条，
     * 后续音频块全部写不进去，文件被截断 —— 2026-10-13 实测服务端留档 187 块
     * （1209456 字节 / 201.6 秒），本机只剩 176 块（1140480 字节），
     * 用本机文件重播就少了十几秒尾巴，而服务端留档其实是完整的。
     */
    fun usable(markerBytes: Long, fileLen: Long): Boolean =
        markerBytes > 0L && markerBytes == fileLen
}

object VoiceQueueTip {

    /** 排队等超过这么久（毫秒）就在聊天页顶部说明「在等前一条」。 */
    const val WAIT_NOTICE_MS = 30_000L

    /**
     * 该不该显示「这条语音还在排队等」。
     *
     * waitingSince = 开始等待的墙钟时间（0 = 没在等，或已经轮到自己了）。
     * 阈值的意义：前一条是长语音或用户手动重播时，新任务要等它播完才响，
     * 这段时间里用户看到的现象就是「任务完成了但没自动播报」，需要被解释。
     */
    fun shouldShow(waitingSince: Long, now: Long): Boolean =
        waitingSince > 0L && now - waitingSince >= WAIT_NOTICE_MS

    fun text(waitSec: Long): String =
        "上一条语音还在播，这条排在它后面（已等 " + waitSec + " 秒）"
}
