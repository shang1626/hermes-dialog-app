package com.hermesapp

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 完成语音播报：服务端在任务收尾时会把整段回复合成语音，作为音频附件随回复下发
 * （正文里是一行 `[📎 名.mp3](data:audio/mpeg;base64,…)` 或 `[📎 名.mp3](hermes-media://token)`）。
 * 这里在收到 run.completed 时把它挑出来自动播一遍；界面上它不再渲染成文件卡片，
 * 而是一个「▶ 播放 / ■ 停止」按钮，点一下就能重播。
 *
 * 用系统自带 MediaPlayer，不引第三方库、不加权限；播完立即释放，不常驻内存。
 * 开关由 Prefs.playCompletionVoice 控制，默认开（关掉只是不自动播，按钮照样能点）。
 */
object VoicePlayer {

    @Volatile
    private var player: MediaPlayer? = null

    /**
     * 播报语速（1.0 = 正常）。由设置页写入、启动时用 Prefs.voiceRate 初始化。
     * 用 PlaybackParams.setSpeed 在播放器上设速，是纯播放层变速，不改语音文件。
     */
    @Volatile
    var rate: Float = 1.0f

    /** 正在播放的音频来源（data URL 或 hermes-media://token）；空闲为空串。界面靠它切按钮状态。 */
    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    /** 播放代际号：起播 / 停止都 +1，作废仍在后台取字节的旧线程（见 VoiceReplayPlayer 同名注释）。 */
    @Volatile
    private var gen: Int = 0

    /**
     * 起播/停止的提交锁（照搬 VoiceReplayPlayer 的 R12/F16 修法）：stop() 与 play() 的
     * 提交点走同一把锁。没有它，用户在「取字节/准备」期间点的停止会被**迟到的起播**盖掉
     * ——停止只清了当时的 player（还是 null），随后 fetch 线程过了代际检查、径直 start()，
     * 音频照样响、界面又亮回 ■，看起来就是「点了停止没用」（2026-10-10 用户报障的另一半）。
     */
    private val lock = Any()

    /** 音频扩展名：这类附件渲染成播放按钮，不显示成文件卡片。 */
    private val AUDIO_EXT = Regex("\\.(mp3|m4a|aac|wav|ogg|opus)$", RegexOption.IGNORE_CASE)

    fun isAudio(name: String): Boolean = AUDIO_EXT.containsMatchIn(name)

    /** 从一条消息正文里取出第一个音频附件的来源（data URL 或 hermes-media://token）；没有则返回空串。 */
    fun audioTarget(text: String): String {
        val m = AUDIO_ATT_RE.find(text) ?: return ""
        return m.groupValues[2]
    }

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
        playTarget(ctx, m.groupValues[2])
    }

    /** 界面播放按钮：同一个源正在播就停掉，否则播它。 */
    fun toggle(ctx: Context, target: String) {
        if (target.isEmpty()) return
        if (_nowPlaying.value == target) {
            stop()
            return
        }
        playTarget(ctx, target)
    }

    /** 取字节 → 落临时文件 → 播放；先置状态，界面立刻切成「停止」。 */
    private fun playTarget(ctx: Context, target: String) {
        if (target.isEmpty()) return
        // 互斥：同一时刻只允许一条语音在响。
        // 流式那边只让「正在播的那条」让位（yield），排队的其余任务保留，等这里播完接着播；
        // 重播播放器则整个停掉（用户主动切到这条，旧的重播不该再续）。
        StreamVoicePlayer.yieldAndDrop()
        // 先把自己标成「在播」，再停对方——否则 stop() 触发的队列推进会以为没人播、
        // 抢先起播下一条（pump 会检查本播放器的 nowPlaying，非空即让路）。
        _nowPlaying.value = target
        VoiceReplayPlayer.stop()
        val myGen = ++gen
        Thread {
            var fail: Throwable? = null
            val bytes = runCatching {
                if (target.startsWith("hermes-media://")) {
                    MediaFetch.download(target.removePrefix("hermes-media://"))
                } else {
                    val comma = target.indexOf(',')
                    if (comma < 0) null else Base64.decode(target.substring(comma + 1), Base64.DEFAULT)
                }
            }.onFailure { fail = it }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                AppLog.err("voice", "取语音字节失败 源=" +
                    (if (target.startsWith("hermes-media://")) "网关托管" else "内联") +
                    " 长度=" + target.length, fail)
                if (myGen == gen) _nowPlaying.value = ""
                return@Thread
            }
            if (myGen != gen) {
                AppLog.log("voice", "取字节完成但已被停止/切换取代，放弃播放")
                return@Thread
            }
            play(ctx, target, bytes, myGen)
        }.start()
    }

    private fun play(ctx: Context, target: String, bytes: ByteArray, myGen: Int) {
        try {
            // 准备阶段只碰局部变量（照搬 VoiceReplayPlayer 的 R12 修法）：锁外不许
            // 释放旧句柄、改 nowPlaying、发布 player——迟到的线程不许动别人的状态。
            val f = File(ctx.cacheDir, "completion_voice.mp3")
            f.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (player === it) {
                    player = null
                    _nowPlaying.value = ""
                    AudioFocus.abandon()
                    StreamVoicePlayer.resumeQueue()   // 本播放器让位结束，队列接着播
                }
            }
            mp.setOnErrorListener { p, what, extra ->
                AppLog.log("voice", "播放器出错 what=" + what + " extra=" + extra)
                runCatching { p.release() }
                if (player === p) {
                    player = null
                    _nowPlaying.value = ""
                    AudioFocus.abandon()
                    StreamVoicePlayer.resumeQueue()
                }
                true
            }
            mp.prepare()
            // 变速：PlaybackParams.setSpeed（0.5~2.0 之间；越界系统会抛，做钳制与兜底）。
            val r = rate
            if (r != 1.0f) {
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(r.coerceIn(0.5f, 2.0f)) }
            }
            // 提交点：锁内最后一次对号，原子地「释放旧句柄 + 登记播放器 + 请求焦点 + 起播」。
            // stop() 走同一把锁，所以「检查通过之后用户才点停止，旧线程照样出声」不会再发生
            //（这正是「任务结束后的首次语音播报没法立即停止」的竞态一半）。
            synchronized(lock) {
                if (myGen != gen) {
                    runCatching { mp.release() }
                    AppLog.log("voice", "起播放弃（起播前已被停止/切换取代）")
                    return
                }
                // 只本地释放，不走公开 stop()：它会给流式队列发「让位结束」信号，
                // 可能把刚让位的语音又拉起来从头播。详见 StreamVoicePlayer.yieldAndDrop。
                releaseCurrent()
                player = mp
                _nowPlaying.value = target
                AudioFocus.request(ctx)   // 不申请的话，播语音不会压低正在放的音乐/视频
                mp.start()
            }
            AppLog.log("voice", "语音已开始播放 " + bytes.size + " 字节")
        } catch (e: Exception) {
            AppLog.err("voice", "语音播放失败", e)
            // 只有自己那一代才许动共享状态：迟到线程失败不能清掉新播放器的登记。
            synchronized(lock) { if (myGen == gen) { releaseCurrent(); _nowPlaying.value = "" } }
            StreamVoicePlayer.resumeQueue()
        }
    }

    /** 只释放本播放器当前实例：不动「在播」标记、不推进流式队列。 */
    private fun releaseCurrent() {
        val p = player
        player = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
    }

    /** 播下一条前先停掉上一条，避免两条叠着响；也用于「停止」按钮。 */
    fun stop() {
        // 与 play() 的提交点走同一把锁：停止与迟到的起播互斥，先到者生效
        //（否则停止只清掉当时的 player，fetch 线程随后照样 start()，音频又响起来）。
        synchronized(lock) {
            gen++          // 作废正在取字节/准备的线程
            val p = player
            player = null
            _nowPlaying.value = ""
            if (p != null) {
                runCatching { if (p.isPlaying) p.stop() }
                runCatching { p.release() }
            }
        }
        AudioFocus.abandon()
        // 让位结束：排队的流式语音接着播（若本次是「停全部」，队列已被清空，这里是空操作）。
        StreamVoicePlayer.resumeQueue()
    }
}