package com.hermesapp

/** Pure selection/merge policy shared by production and JVM tests. */
object HistoryRestore {
    fun candidates(remote: List<SessionMeta>, local: List<SessionMeta>): List<SessionMeta> {
        val known = local.mapTo(HashSet()) { it.id }
        return remote.filter { it.id.isNotBlank() && it.id !in known }.distinctBy { it.id }
    }

    fun merge(local: List<SessionMeta>, remote: List<SessionMeta>, selected: Set<String>): List<SessionMeta> {
        val additions = candidates(remote, local).filter { it.id in selected }
        // Keep every local row, title, archive flag and manual order unchanged.
        return local + additions
    }
}

data class HistoryRestoreState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val candidates: List<SessionMeta> = emptyList(),
    val error: String = "",
)
