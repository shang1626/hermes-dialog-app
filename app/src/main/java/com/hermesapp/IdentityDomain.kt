package com.hermesapp

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** A server origin/base path is a trust boundary, not just a display preference. */
internal fun canonicalServer(value: String): String {
    if (value.isBlank()) return ""
    val u = URI(value.trim())
    require(u.scheme.equals("https", true) || u.scheme.equals("http", true))
    require(u.host != null && u.userInfo == null && u.rawQuery == null && u.rawFragment == null)
    val scheme = u.scheme.lowercase()
    val port = if (u.port == -1 || (scheme == "https" && u.port == 443) ||
        (scheme == "http" && u.port == 80)) -1 else u.port
    return URI(scheme, null, u.host.lowercase(), port, u.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
}

internal fun domainHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal data class IdentityDomain(val server: String, val profile: String, val account: String) {
    val key: String get() = domainHash(canonicalServer(server) + "\u0000" + profile + "\u0000" + account)
}

/** Copy, never move: interruption/downgrade must not destroy the only legacy copy. */
internal fun identityStoreDirectory(root: File, identity: IdentityDomain, legacyServer: String): File {
    val target = File(root, "identities/" + identity.key).apply { mkdirs() }
    val marker = File(target, ".legacy-imported")
    if (canonicalServer(identity.server) == canonicalServer(legacyServer) && legacyServer.isNotBlank() && !marker.exists()) {
        synchronized(SessionStore::class.java) {
            if (!marker.exists()) {
                val p = identity.profile
                root.listFiles().orEmpty().filter { it.isFile &&
                    (it.name.startsWith("sessions_$p.json") || it.name.startsWith("chat_${p}_") ||
                        it.name.startsWith("chat_$p.json") || it.name.startsWith("queue_${p}_"))
                }.forEach { source ->
                    val dest = File(target, source.name)
                    if (!dest.exists()) source.copyTo(dest)
                }
                marker.writeText("1")
            }
        }
    }
    return target
}
