package com.hermesapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 播放提示语音时申请音频焦点（USAGE_MEDIA + 允许闪避）。
 *
 * 为什么需要：不申请焦点时，播语音不会压低正在放的音乐/视频，两条音频叠着响；
 * 申请 TRANSIENT_MAY_DUCK 后系统会让别的 App 自动压低，播完放开。
 *
 * 设计：只保留最近一次请求的 applicationContext，abandon() 无需再传 ctx——
 * 三个播放器的释放路径（完成/出错/停止）散落各处，无参调用最不容易漏。
 */
object AudioFocus {
    @Volatile private var appCtx: Context? = null
    @Volatile private var req: AudioFocusRequest? = null

    fun request(ctx: Context) {
        runCatching {
            appCtx = ctx.applicationContext
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .build()
                req = r
                am.requestAudioFocus(r)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    fun abandon() {
        runCatching {
            val ctx = appCtx ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                req?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            req = null
        }
    }
}
