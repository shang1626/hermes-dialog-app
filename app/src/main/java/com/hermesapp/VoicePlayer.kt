package com.hermesapp

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File

/**
 * 完成语音播报：服务端在任务收尾时会把整段回复合成语音，作为音频附件随回复下发
 * （正文里是一行 `[📎 名.mp3](data:audio/mpeg;base64,…)` 或 `[📎 名.mp3](hermes-media://token)`）。
 * 这里在收到 run.completed 时把它挑出来自动播一遍。
 *
 * 用系统自带 MediaPlayer，不引第三方库、不加权限；播完立即释放，不常驻内存。
 * 开关由 Prefs.playCompletionVoice 控制，默认关。
 */
object VoicePlayer {

    @Volatile
    private var player: MediaPlayer? = null

    /** 音频附件：data URL 内联，或走网关托管 token。 */
    private val AUDIO_ATT_RE = Regex(
        "\\[\\uD83D\\uDCCE ([^\\]]+\\.(?:mp3|m4a|aac|wav|ogg|opus))\\]\\(((?:data:|hermes-media://)[^)]+)\\)",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从回复正文里挑出第一个音频附件并播放；没有或未开启则什么都不做。
     * 取字节、落临时文件、prepare 全在后台线程，避免卡住界面。
     */
    fun playFromReply(ctx: Context, output: String, enabled: Boolean) {
        if (!enabled || output.isEmpty()) return
        val m = AUDIO_ATT_RE.find(output) ?: return
        val target = m.groupValues[2]
        Thread {
            val bytes = runCatching {
                if (target.startsWith("hermes-media://")) {
                    MediaFetch.download(target.removePrefix("hermes-media://"))
                } else {
                    val comma = target.indexOf(',')
                    if (comma < 0) null else Base64.decode(target.substring(comma + 1), Base64.DEFAULT)
                }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                AppLog.log("voice", "完成语音取字节失败")
                return@Thread
            }
            play(ctx, bytes)
        }.start()
    }

    private fun play(ctx: Context, bytes: ByteArray) {
        try {
            stop()
            val f = File(ctx.cacheDir, "completion_voice.mp3")
            f.writeBytes(bytes)
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (player === it) player = null
            }
            mp.setOnErrorListener { p, _, _ ->
                runCatching { p.release() }
                if (player === p) player = null
                true
            }
            mp.prepare()
            mp.start()
            AppLog.log("voice", "完成语音已开始播放 " + bytes.size + " 字节")
        } catch (e: Exception) {
            AppLog.err("voice", "完成语音播放失败", e)
            stop()
        }
    }

    /** 播下一条前先停掉上一条，避免两条叠着响。 */
    fun stop() {
        val p = player ?: return
        player = null
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }
}