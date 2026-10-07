package com.hermesapp

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 语音重播：点消息气泡里的播放按钮，重放那一条的完成语音。
 *
 * 为什么需要单独一个播放器：流式语音（StreamVoicePlayer）是「边收边播」，播完即释放，
 * 且流式模式下回复正文里不再带音频附件——原来那个靠扫正文找附件的按钮就失效了。
 * 这里改成按消息的 run_id 找音频：本机有留档就直接播，没有就按 run_id 去服务端取回、
 * 落盘缓存再播（第二次点就即时出声）。
 *
 * 与 StreamVoicePlayer 共用同一份文件（voice_replay/<run_id>.mp3）：刚播完的那条
 * 文件已经躺在那里，点重播是零网络、零延迟。
 *
 * 用系统自带 MediaPlayer（与 VoicePlayer 一致）：不引第三方库、不加权限，播完即释放。
 */
object VoiceReplayPlayer {

    @Volatile
    private var player: MediaPlayer? = null

    /** 语速（与其它两个播放器同源，由设置页写入）。 */
    @Volatile
    var rate: Float = 1.0f

    /** 取音频字节：传入 run_id，返回 mp3 字节；取不到返回 null。由 ChatViewModel 挂上。 */
    @Volatile
    var fetcher: ((String) -> ByteArray?)? = null

    /** 正在重播的 run_id；空闲为空串。界面靠它切按钮状态。 */
    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    /** 界面播放按钮：同一个 run 正在播就停掉，否则播它。 */
    fun toggle(ctx: Context, runId: String) {
        if (runId.isEmpty()) return
        if (_nowPlaying.value == runId) {
            stop()
            return
        }
        play(ctx, runId)
    }

    /** 取字节（本机留档优先）→ 落盘 → 播放；先置状态，界面立刻切成「停止」。 */
    private fun play(ctx: Context, runId: String) {
        // 互斥：点重播先让流式自动播报让位（正在播的那条停下、排队的保留）。
        // 然后取消这条 run 自己的流式排队项——用户已经手动放了它，留着会再播一遍。
        StreamVoicePlayer.yieldAndDrop()
        StreamVoicePlayer.cancelRun(runId)
        // 同上：先标「在播」再停对方，防队列抢跑。
        _nowPlaying.value = runId
        VoicePlayer.stop()
        Thread {
            try {
                // 先看本机留档：流式播过的那条已经落盘，这里是纯本地读，即时。
                val f = StreamVoicePlayer.voiceFile(ctx, runId)
                val local = if (f.isFile && f.length() > 0L) f.readBytes() else null
                val bytes = local ?: fetcher?.invoke(runId)
                if (bytes == null || bytes.isEmpty()) {
                    AppLog.log("voice", "重播取音频失败 run=" + runId.take(12) + "（服务端无留档或已淘汰）")
                    _nowPlaying.value = ""
                    return@Thread
                }
                // 取回来的才落盘，供下次离线重播。
                if (local == null) {
                    runCatching { f.writeBytes(bytes) }
                }
                startPlayback(ctx, runId, bytes)
            } catch (e: Exception) {
                AppLog.err("voice", "重播失败 run=" + runId.take(12), e)
                _nowPlaying.value = ""
            }
        }.start()
    }

    private fun startPlayback(ctx: Context, runId: String, bytes: ByteArray) {
        try {
            // 只本地释放旧播放器，**不要**走公开的 stop()：那个会给流式队列发
            // 「让位结束」信号，把刚让位的语音又拉起来从头播——表现就是
            // 「点了别的任务，当前这条停不掉」。详见 StreamVoicePlayer.yieldAndDrop。
            releaseCurrent()
            _nowPlaying.value = runId
            val f = StreamVoicePlayer.voiceFile(ctx, runId)
            if (!f.isFile || f.length() == 0L) f.writeBytes(bytes)
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (player === it) {
                    player = null
                    _nowPlaying.value = ""
                    StreamVoicePlayer.resumeQueue()   // 重播结束，队列接着播
                }
            }
            mp.setOnErrorListener { p, what, extra ->
                AppLog.log("voice", "重播播放器出错 what=" + what + " extra=" + extra)
                runCatching { p.release() }
                if (player === p) {
                    player = null
                    _nowPlaying.value = ""
                    StreamVoicePlayer.resumeQueue()
                }
                true
            }
            mp.prepare()
            val r = rate
            if (r != 1.0f) {
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(r.coerceIn(0.5f, 2.0f)) }
            }
            mp.start()
            AppLog.log("voice", "重播已开始 run=" + runId.take(12) + " " + bytes.size + " 字节")
        } catch (e: Exception) {
            AppLog.err("voice", "重播播放失败 run=" + runId.take(12), e)
            releaseCurrent()
            _nowPlaying.value = ""
            StreamVoicePlayer.resumeQueue()
        }
    }

    /** 只释放本播放器当前实例：不改「在播」标记、不推进流式队列。 */
    private fun releaseCurrent() {
        val p = player
        player = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
    }

    /** 播下一条前先停掉上一条；也用于「停止」按钮。 */
    fun stop() {
        val p = player
        player = null
        _nowPlaying.value = ""
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        // 让位结束：排队的流式语音接着播（「停全部」时队列已清空，这里是空操作）。
        StreamVoicePlayer.resumeQueue()
    }
}
