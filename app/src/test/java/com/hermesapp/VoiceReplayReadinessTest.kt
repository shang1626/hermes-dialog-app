package com.hermesapp

import org.junit.Assert.*
import org.junit.Test

class VoiceReplayReadinessTest {
    @Test fun repro_old_click_removes_queue_before_failed_fetch() {
        val queue = mutableListOf("run")
        val downloaded: ByteArray? = null
        queue.remove("run")
        assertNull(downloaded)
        assertTrue(queue.isEmpty())
    }

    @Test fun generating_voice_keeps_automatic_queue() {
        val queue = mutableListOf("run")
        if (!VoiceReplayReadiness.generating(true, false)) queue.remove("run")
        assertEquals(listOf("run"), queue)
    }

    @Test fun completed_or_absent_stream_allows_replay() {
        assertFalse(VoiceReplayReadiness.generating(true, true))
        assertFalse(VoiceReplayReadiness.generating(false, false))
        assertFalse(VoiceReplayReadiness.generating(false, true))
    }
}
