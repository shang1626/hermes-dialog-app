package com.hermesapp

import org.junit.Assert.*
import org.junit.Test

class HistoryRestoreTest {
    private fun row(id: String) = SessionMeta(id, "title-$id", 10L)

    @Test fun remoteOnlyCandidatesDeduplicateAndExcludeArchivedLocalToo() {
        val local = listOf(row("a"), row("b").copy(archived = true))
        assertEquals(listOf("c"), HistoryRestore.candidates(
            listOf(row("a"), row("b"), row("c"), row("c"), row(" ")), local).map { it.id })
    }

    @Test fun emptySelectionNeverImportsAnything() {
        val local = listOf(row("a"))
        assertEquals(local, HistoryRestore.merge(local, listOf(row("b")), emptySet()))
    }

    @Test fun selectedMergePreservesEveryLocalFieldAndOrder() {
        val local = listOf(row("b").copy(title = "manual", archived = true, order = 4), row("a"))
        val merged = HistoryRestore.merge(local, listOf(row("b"), row("c"), row("d")), setOf("b", "c", "unknown"))
        assertEquals(local, merged.take(local.size))
        assertSame(local.first(), merged.first())
        assertEquals(listOf("b", "a", "c"), merged.map { it.id })
        assertEquals(merged, HistoryRestore.merge(merged, listOf(row("c")), setOf("c")))
    }

    @Test fun emptyRemoteNeverDeletesLocalAndRemoteIsNotMutated() {
        val local = listOf(row("a"))
        val remote = listOf(row("b"))
        assertEquals(local, HistoryRestore.merge(local, emptyList(), setOf("a")))
        HistoryRestore.merge(local, remote, setOf("b"))
        assertEquals(listOf(row("b")), remote)
    }
}
