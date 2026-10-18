package com.hermesapp

/**
 * 语音排队/重播的两条纯逻辑（不碰 Android，可单测）。
 *
 * 为什么单独抽出来：这两条分别对应两个真实报障的判据 ——
 * 「播放按钮点了没声 / 重播少一截」与「任务完成了却没有自动播报」，
 * 必须有可回归的单测钉住，不能只靠真机手感。
 */
object VoiceReplayReadiness {
    fun generating(hasStream: Boolean, ended: Boolean): Boolean = hasStream && !ended
}

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
     * 队列里「已经收到数据、排在别人后面」的条目数 —— 正在响的那条不算（它就是前面那条）。
     *
     * queuedKeys = 队列里的条目（含正在响的那条：它一直留在队里，直到播完才被摘）；
     * activeKey = 正在响的那条；bytesOf = 取某条已收到的字节数（0 表示还没数据，不算在等）。
     */
    fun waitingCount(queuedKeys: List<String>, activeKey: String, bytesOf: (String) -> Long): Int =
        queuedKeys.count { it != activeKey && bytesOf(it) > 0L }

    /**
     * 该不该记「有人在排队等」：有人正在出声 **且** 后面确实有人等着，两条同时成立才算。
     *
     * 判据必须与清理判据是同一个函数（见 nextWaitingSince）。2026-10-13 上报的缺陷就是
     * 「写」看的是「队列里有没有带数据的条目」—— 把正在响的那条自己也算了进去，
     * 于是任意一条超过 30 秒的单条语音都会把提示点亮；而「清」只在「轮到自己起播」与
     * 「按停止」两条路上发生，队列播空走的是另一条 return，没人清：
     * 17:16:23 起播、17:18:01 播完（队列剩=0），到 17:20 顶部仍显示「已等 251 秒」，
     * 而当时已经没有任何可播的语音。
     */
    fun shouldTrackWaiting(someonePlaying: Boolean, waitingCount: Int): Boolean =
        someonePlaying && waitingCount > 0

    /**
     * 等待起点的唯一写入路径：不该等 -> 归零；该等且还没记 -> 记现在；本来就该等 -> 保持原值。
     * 保持原值让「已等 N 秒」在队列前移时连续，不被重置。
     */
    fun nextWaitingSince(current: Long, shouldTrack: Boolean, now: Long): Long = when {
        !shouldTrack -> 0L
        current > 0L -> current
        else -> now
    }

    /**
     * 该不该显示「这条语音还在排队等」。
     *
     * 阈值的意义：前一条是长语音或用户手动重播时，新任务要等它播完才响，
     * 这段时间里用户看到的现象就是「任务完成了但没自动播报」，需要被解释。
     */
    fun shouldShow(waitingSince: Long, now: Long): Boolean =
        waitingSince > 0L && now - waitingSince >= WAIT_NOTICE_MS

    fun text(waitSec: Long): String =
        "上一条语音还在播，这条排在它后面（已等 " + waitSec + " 秒）"
}
