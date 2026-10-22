package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryIdentityRegressionTest {
    private fun user(text: String, ts: Long) = Msg("user", text, ts = ts)
    private fun answer(text: String) = Msg("assistant", text)

    private fun assertStable(local: List<Msg>, server: List<Msg>) {
        val once = mergeByUserAnchor(local, server)
        assertEquals(local, once)
        assertEquals(once, mergeByUserAnchor(once, server))
    }

    @Test
    fun quoteUsesOriginalWireTextAndKeepsLocalMetadata() {
        val localUser = user("continue", 1_000).copy(
            quote = "assistant: previous answer",
            receipt = Receipt(Receipt.ACCEPTED, rawText = "> assistant: previous answer\ncontinue"),
            idemKey = "quote-key",
        )
        assertStable(
            listOf(localUser, answer("reply")),
            listOf(user("> assistant: previous answer\ncontinue", 1_800), answer("reply")),
        )
    }

    @Test
    fun legacyQuoteWithoutReceiptCanStillMatch() {
        assertStable(
            listOf(user("continue", 1_000).copy(quote = "assistant: previous"), answer("reply")),
            listOf(user("> assistant: previous\ncontinue", 1_800), answer("reply")),
        )
    }

    @Test
    fun attachmentOnlyWireTextMatchesWithoutChangingEmptyBubble() {
        for ((wire, images, files) in listOf(
            Triple("（图片）", listOf("file:///sent/photo.png"), emptyList()),
            Triple("（文件）", emptyList(), listOf("report.pdf")),
            Triple("（图片和文件）", listOf("file:///sent/photo.png"), listOf("report.pdf")),
        )) {
            val localUser = user("", 1_000).copy(
                images = images, files = files,
                attachments = listOf("sent-original"),
                receipt = Receipt(Receipt.ACCEPTED, rawText = wire),
            )
            assertStable(listOf(localUser, answer("reply")), listOf(user(wire, 1_800), answer("reply")))
        }
    }

    @Test
    fun repeatedQuotedBodyKeepsBothTurnsWithClockSkew() {
        val wire = "> assistant: previous\ncontinue"
        val first = user("continue", 1_000).copy(quote = "assistant: previous",
            receipt = Receipt(Receipt.ACCEPTED, rawText = wire))
        val second = first.copy(id = Msg.nextMsgId(), ts = 2_000)
        assertStable(
            listOf(first, answer("first reply"), second, answer("second reply")),
            listOf(user(wire, 1_800), answer("first reply"), user(wire, 2_800), answer("second reply")),
        )
    }

    @Test
    fun repeatedAttachmentOnlyTurnsDoNotCollapse() {
        val wire = "（文件）"
        val first = user("", 1_000).copy(files = listOf("first.pdf"),
            receipt = Receipt(Receipt.ACCEPTED, rawText = wire))
        val second = first.copy(id = Msg.nextMsgId(), ts = 2_000, files = listOf("second.pdf"))
        assertStable(
            listOf(first, answer("first reply"), second, answer("second reply")),
            listOf(user(wire, 1_800), answer("first reply"), user(wire, 2_800), answer("second reply")),
        )
    }

    @Test
    fun compressedRepeatedQuoteMatchesNearestTurn() {
        val wire = "> assistant: previous\ncontinue"
        val first = user("continue", 1_000).copy(quote = "assistant: previous",
            receipt = Receipt(Receipt.ACCEPTED, rawText = wire))
        val second = first.copy(id = Msg.nextMsgId(), ts = 2_000)
        assertStable(
            listOf(first, answer("first reply"), second, answer("second reply")),
            listOf(user(wire, 2_000), answer("second reply")),
        )
    }

    @Test
    fun plainRepeatedBodyKeepsBothTurns() {
        assertStable(
            listOf(user("continue", 1_000), answer("first reply"), user("continue", 2_000), answer("second reply")),
            listOf(user("continue", 1_800), answer("first reply"), user("continue", 2_800), answer("second reply")),
        )
    }

    @Test
    fun differentQuoteOrOutOfWindowDoesNotMatch() {
        val local = user("continue", 1_000).copy(quote = "assistant: first",
            receipt = Receipt(Receipt.ACCEPTED, rawText = "> assistant: first\ncontinue"))
        for (server in listOf(
            user("> assistant: second\ncontinue", 1_000),
            user("> assistant: first\ncontinue", 1_000 + REWRITTEN_ROW_WINDOW_MS + 1),
        )) {
            assertEquals(2, mergeByUserAnchor(listOf(local), listOf(server)).count { it.role == "user" })
        }
    }
}
