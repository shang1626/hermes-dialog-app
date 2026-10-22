package com.hermesapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IdentityStorageTest {
    @get:Rule val tmp = TemporaryFolder()
    private val original = IdentityDomain("https://old.example", "friend", "alice")
    private fun store(id: IdentityDomain, legacy: String = original.server) =
        SessionStore(identityStoreDirectory(tmp.root, id, legacy), id.profile)

    @Test fun serversAndAccountsHaveIndependentProductionStores() {
        val first = store(original)
        first.saveIndex(listOf(SessionMeta("same-id", "private history", 1L)))
        first.saveMessages("same-id", listOf(Msg("user", "private")))
        first.saveQueue("same-id", emptyList(), true)
        for (other in listOf(original.copy(server = "https://new.example"),
            original.copy(account = "bob"), original.copy(profile = "default"))) {
            val second = store(other, "")
            assertTrue(second.loadIndex().isEmpty())
            assertTrue(second.loadMessages("same-id").isEmpty())
            assertTrue(second.loadQueue("same-id").isEmpty())
        }
        assertEquals("private", store(original).loadMessages("same-id").single().text)
    }

    @Test fun migrationCopiesOnlyOriginalServerAndDoesNotRepeatOrDeleteSource() {
        SessionStore(tmp.root, "friend").saveIndex(listOf(SessionMeta("legacy-id", "legacy", 1L)))
        assertTrue(store(original.copy(server = "https://new.example")).loadIndex().isEmpty())
        assertEquals("legacy-id", store(original).loadIndex().single().id)
        assertTrue(File(tmp.root, "sessions_friend.json").exists())
        store(original).saveIndex(emptyList(), allowEmpty = true)
        assertTrue("Legacy must not resurrect after explicit clear", store(original).loadIndex().isEmpty())
    }

    @Test fun canonicalServersShareEquivalentOriginsButNotDifferentPortsOrPaths() {
        assertEquals(original.key, original.copy(server = "HTTPS://OLD.EXAMPLE:443/").key)
        assertNotEquals(original.key, original.copy(server = "https://old.example:444").key)
        assertNotEquals(original.key, original.copy(server = "https://old.example/base").key)
    }

    @Test fun runtimeDomainsDoNotShareSessionObjects() {
        val a = RuntimeHub.runtimes(original.key)
        val b = RuntimeHub.runtimes(original.copy(server = "https://new.example").key)
        val r = SessionRuntime("same-id")
        a["same-id"] = r
        assertNull(b["same-id"])
        assertSame(r, RuntimeHub.runtimes(original.key)["same-id"])
        a.clear()
    }
}
