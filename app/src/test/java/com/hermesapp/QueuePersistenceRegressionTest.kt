package com.hermesapp

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueuePersistenceRegressionTest {
    @get:Rule val tmp = TemporaryFolder(File(requireNotNull(System.getProperty("java.io.tmpdir"))))

    private fun store() = SessionStore(tmp.root, "queue-test")
    private val sid = "cold-start"
    private fun queued(id: Long = 900_001L, key: String = "original-key") = Msg(
        role = "user", text = "queued input", id = id, idemKey = key,
        receipt = Receipt(Receipt.QUEUED, rawText = "wire input", priorUserCount = 3,
            idemKey = key, artifactIds = listOf("artifact-original")),
    )
    private fun entry(m: Msg, files: List<File> = emptyList()) =
        QueuedSend("wire input", files, m.id, 3, m.idemKey)
    private fun persist(m: Msg, q: List<QueuedSend> = listOf(entry(m))) {
        assertTrue(store().saveMessages(sid, listOf(m)))
        store().saveQueue(sid, q)
    }

    @Test fun cold_start_keeps_stable_message_id() {
        val m = queued()
        persist(m)
        assertEquals(m.id, store().loadMessages(sid).single().id)
    }

    @Test fun cold_start_restores_queue_eligible_receipt_and_original_payload() {
        val file = tmp.newFile("upload.bin").apply { writeText("attachment") }
        val m = queued()
        persist(m, listOf(entry(m, listOf(file))))
        val reloaded = store().loadMessages(sid).single()
        assertEquals(Receipt.QUEUED, reloaded.receipt?.status)
        assertEquals(m.idemKey, reloaded.idemKey)
        assertEquals(m.receipt, reloaded.receipt)
        // This is the production restoreQueue eligibility gate, not a replacement store.
        val restored = store().loadQueue(sid).filter { item ->
            reloaded.id == item.msgId && reloaded.receipt?.status == Receipt.QUEUED
        }
        assertEquals(listOf(entry(m, listOf(file))), restored)
        assertTrue(store().saveMessages(sid, listOf(reloaded)))
        assertEquals(reloaded, store().loadMessages(sid).single())
    }

    @Test fun restored_ids_are_reserved_before_legacy_rows_get_new_ids() {
        val high = Msg.nextMsgId() + 1_000_000L
        File(tmp.root, "chat_queue-test_$sid.json").writeText(JSONArray()
            .put(JSONObject().put("role", "user").put("text", "legacy row"))
            .put(JSONObject().put("role", "user").put("text", "stored row").put("id", high))
            .toString())
        val rows = store().loadMessages(sid)
        assertEquals(high, rows[1].id)
        assertTrue(rows[0].id > high)
        assertTrue(Msg("user", "new input").id > rows.maxOf { it.id })
    }

    @Test fun orphan_queued_receipt_without_queue_is_not_sent() {
        val m = queued()
        persist(m, emptyList())
        assertEquals(Receipt.NOT_SENT, store().loadMessages(sid).single().receipt?.status)
    }

    @Test fun missing_attachment_or_wrong_key_cannot_reenable_queue_receipt() {
        val m = queued()
        persist(m, listOf(entry(m).copy(idemKey = "other-request")))
        assertEquals(Receipt.NOT_SENT, store().loadMessages(sid).single().receipt?.status)
        store().saveQueue(sid, listOf(entry(m, listOf(File(tmp.root, "missing.bin")))))
        assertEquals(Receipt.NOT_SENT, store().loadMessages(sid).single().receipt?.status)
    }

    @Test fun accepted_or_inflight_receipts_are_not_automatically_requeued() {
        for (status in listOf(Receipt.ACCEPTED, Receipt.SENDING)) {
            val m = queued().let { it.copy(receipt = it.receipt!!.copy(status = status)) }
            persist(m)
            val row = store().loadMessages(sid).single()
            assertEquals(if (status == Receipt.SENDING) Receipt.UNCERTAIN else status, row.receipt?.status)
            assertEquals(m.receipt?.idemKey, row.receipt?.idemKey)
        }
    }

    @Test fun clearing_queue_cannot_resurrect_atomic_backup_on_restart() {
        val m = queued()
        persist(m)
        store().saveQueue(sid, listOf(entry(m))) // Create the normal atomic backup.
        store().saveQueue(sid, emptyList())
        assertTrue(store().loadQueue(sid).isEmpty())
        assertEquals(Receipt.NOT_SENT, store().loadMessages(sid).single().receipt?.status)
    }

    @Test fun pause_survives_restart_queue_mutations_and_empty_queue() {
        val m = queued()
        persist(m)
        store().saveQueuePaused(sid, true)
        assertTrue(store().loadQueuePaused(sid))
        store().saveQueue(sid, listOf(entry(m)))
        assertTrue(store().loadQueuePaused(sid))
        store().saveQueue(sid, emptyList())
        assertTrue(store().loadQueuePaused(sid))
        assertTrue(store().loadQueue(sid).isEmpty())
        store().saveQueuePaused(sid, false)
        assertFalse(store().loadQueuePaused(sid))
        assertTrue(store().loadQueue(sid).isEmpty())
    }

    @Test fun legacy_queue_without_pause_metadata_requires_explicit_resume() {
        val m = queued()
        assertTrue(store().saveMessages(sid, listOf(m)))
        File(tmp.root, "queue_queue-test_$sid.json").writeText(JSONArray()
            .put(JSONObject().put("text", "wire input").put("msgId", m.id)
                .put("prior", 3).put("idemKey", m.idemKey).put("files", JSONArray()))
            .toString())
        assertEquals(listOf(entry(m)), store().loadQueue(sid))
        assertTrue(store().loadQueuePaused(sid))
        store().saveQueue(sid, store().loadQueue(sid))
        assertTrue(store().loadQueuePaused(sid))
    }

    @Test fun legacy_message_without_stable_id_must_not_claim_old_queue_by_guessing() {
        val legacyId = Msg.nextMsgId() + 1
        File(tmp.root, "chat_queue-test_$sid.json").writeText(JSONArray()
            .put(JSONObject().put("role", "user").put("text", "queued input")
                .put("receipt", JSONObject().put("status", Receipt.QUEUED)
                    .put("idemKey", "original-key"))).toString())
        store().saveQueue(sid, listOf(entry(queued(legacyId))))
        val row = store().loadMessages(sid).single()
        assertEquals(Receipt.NOT_SENT, row.receipt?.status)
        assertNotEquals(legacyId, row.id)
        assertEquals("original-key", row.idemKey)
        assertTrue(store().saveMessages(sid, listOf(row)))
        assertEquals(row.id, store().loadMessages(sid).single().id)
    }
}
