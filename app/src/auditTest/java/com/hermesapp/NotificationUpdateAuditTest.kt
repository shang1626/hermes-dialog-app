package com.hermesapp

import android.app.NotificationManager
import android.content.Context
import android.os.Vibrator
import com.hermesapp.net.HermesApi
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NotificationUpdateAuditTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun assertNoBypass(configure: (NotificationManager) -> Unit) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Notifier.ensureDoneChannel(ctx)
        configure(nm)
        Notifier.notifyDone(ctx, "audit", "completed", "session")
        @Suppress("DEPRECATION")
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertFalse("notification policy bypassed by direct vibrator", shadowOf(vibrator).isVibrating)
    }

    @Test fun disabledNotificationsDoNotDirectAlert() = assertNoBypass {
        shadowOf(it).setNotificationsEnabled(false)
    }

    @Test fun silentChannelDoesNotDirectAlert() = assertNoBypass {
        val channel = it.getNotificationChannel(Notifier.DONE_CHANNEL_ID)
        channel.setSound(null, null)
        channel.enableVibration(false)
        it.createNotificationChannel(channel)
    }

    @Test fun lowImportanceDoesNotDirectAlert() = assertNoBypass {
        val channel = it.getNotificationChannel(Notifier.DONE_CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_LOW
        it.createNotificationChannel(channel)
    }

    @Test fun normalDoneStillPostsNotification() {
        Notifier.notifyDone(ctx, "audit", "completed", "session", 3)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = shadowOf(nm).getNotification(Notifier.NOTIF_ID)
        assertNotNull(notification)
        assertEquals(Notifier.DONE_CHANNEL_ID, notification.channelId)
        assertNotNull(notification.contentIntent)
        assertEquals(1, notification.actions.size)
    }

    private fun download(bytes: ByteArray, digest: String, chunked: Boolean = false,
                         manifestSize: Long? = null, declaredSize: Long = bytes.size.toLong(),
                         progress: (Long, Long) -> Unit = { _, _ -> }): File? {
        val dir = File(ctx.getExternalFilesDir(null), "apk")
        dir.deleteRecursively()
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().use { out ->
                        val length = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: $declaredSize"
                        out.write("HTTP/1.1 200 OK\r\n$length\r\nConnection: close\r\n\r\n".toByteArray())
                        if (chunked) out.write("${bytes.size.toString(16)}\r\n".toByteArray())
                        out.write(bytes)
                        if (chunked) out.write("\r\n0\r\n\r\n".toByteArray())
                    }
                }
            }
        }
        return try {
            val api = HermesApi("http://127.0.0.1:${server.localPort}", "")
            val url = "http://127.0.0.1:${server.localPort}/update.apk"
            if (manifestSize == null) api.downloadApk(url, ctx, "audit", digest, progress)
            else api.downloadApk(UpdateInfo(1, "audit", url, "", manifestSize, digest), ctx, progress)
        } finally { server.close(); worker.join(2000) }
    }

    private val payload get() = "actual HTTP update payload".toByteArray()
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("MD5")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun missingDigestRejectedEvenWithHttpLength() {
        assertNull(download(payload, ""))
    }

    @Test fun malformedDigestRejected() {
        assertNull(download(payload, "not-md5"))
    }

    @Test fun validDigestDownloadsAndPromotesPart() {
        val file = download(payload, digest(payload).uppercase())!!
        assertArrayEquals(payload, file.readBytes())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test fun chunkedValidDigestStillWorks() {
        assertArrayEquals(payload, download(payload, digest(payload), true)!!.readBytes())
    }

    @Test fun wrongDigestDeletesPart() {
        assertNull(download(payload, "0".repeat(32)))
        assertFalse(File(ctx.getExternalFilesDir(null), "apk/hermes-audit.apk.part").exists())
    }

    @Test fun progressFailureDeletesPart() {
        try {
            download(payload, digest(payload)) { _, _ -> throw IllegalStateException("cancel/progress failure") }
            fail("expected progress failure")
        } catch (_: IllegalStateException) { }
        assertFalse(File(ctx.getExternalFilesDir(null), "apk/hermes-audit.apk.part").exists())
    }

    @Test fun oversizedChunkedDownloadRejected() {
        val bytes = ByteArray(101 * 1024 * 1024)
        assertNull(download(bytes, digest(bytes), true))
        assertFalse(File(ctx.getExternalFilesDir(null), "apk/hermes-audit.apk.part").exists())
    }
    @Test fun manifestSizeMismatchRejected() {
        assertNull(download(payload, digest(payload), manifestSize = payload.size + 1L))
    }

    @Test fun chunkedManifestOversizeRejected() {
        assertNull(download(payload, digest(payload), true, manifestSize = payload.size - 1L))
        assertFalse(File(ctx.getExternalFilesDir(null), "apk/hermes-audit.apk.part").exists())
    }

    @Test fun chunkedManifestUndersizeRejected() {
        assertNull(download(payload, digest(payload), true, manifestSize = payload.size + 1L))
    }

    @Test fun validManifestDownloads() {
        assertArrayEquals(payload, download(payload, digest(payload), manifestSize = payload.size.toLong())!!.readBytes())
    }

    @Test fun truncatedHttpDeletesPart() {
        try {
            assertNull(download(payload, digest(payload), declaredSize = payload.size + 1L))
        } catch (_: java.io.IOException) { }
        assertFalse(File(ctx.getExternalFilesDir(null), "apk/hermes-audit.apk.part").exists())
    }

    @Test fun oversizedDeclaredLengthRejected() {
        assertNull(download(payload, digest(payload), declaredSize = 101L * 1024 * 1024))
    }

    @Test @Config(sdk = [33]) fun deniedAndroid13PermissionDoesNotPostOrVibrate() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Notifier.notifyDone(ctx, "audit", "completed")
        Notifier.notifyAction(ctx, "approval", "waiting", "session")
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(shadowOf(nm).getNotification(Notifier.NOTIF_ID))
        assertNull(shadowOf(nm).getNotification(Notifier.ACTION_NOTIF_ID))
    }

}
