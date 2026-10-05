package com.hermesapp

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesapp.net.HermesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Msg(
    val role: String,
    val text: String,
    var pending: Boolean = false,
    val ts: Long = 0L,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private var store = SessionStore(app, prefs.profile)

    private val _messages = MutableStateFlow<List<Msg>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _currentId = MutableStateFlow("")
    val currentId = _currentId.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online = _online.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _retryNote = MutableStateFlow("")
    val retryNote = _retryNote.asStateFlow()

    private val _statusText = MutableStateFlow("尚未获取")
    val statusText = _statusText.asStateFlow()

    private val _updateNote = MutableStateFlow("")
    val updateNote = _updateNote.asStateFlow()

    /** 有待确认的新版本时为非空。 */
    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()

    /** -1 未下载；0..100 下载中百分比。 */
    private val _downloadPct = MutableStateFlow(-1)
    val downloadPct = _downloadPct.asStateFlow()

    private val _downloadText = MutableStateFlow("")
    val downloadText = _downloadText.asStateFlow()

    private var api: HermesApi? = null
    private var currentCall: Call? = null
    private var currentRunId: String? = null
    private var lastSeq = -1
    private var autoContinue = 0
    private var runFinished = false
    private var pingStarted = false
    private var saveJob: Job? = null

    // ---------- 会话与本地持久化 ----------

    private val maxHistory = 300

    private fun stamp(): Long = System.currentTimeMillis()

    private fun setMsgs(list: List<Msg>) {
        _messages.value = list
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            saveNow()
        }
    }

    private fun saveNow() {
        val id = _currentId.value
        if (id.isEmpty()) return
        store.saveMessages(id, _messages.value, maxHistory)
        store.saveIndex(_sessions.value)
    }

    private fun refreshSessions() {
        _sessions.value = store.loadIndex().sortedByDescending { it.updatedAt }
    }

    /** 切到某个会话（网关 session_id 同步指过去）。 */
    fun switchSession(id: String) {
        if (id == _currentId.value) return
        if (_busy.value) stop()
        saveJob?.cancel()
        saveNow()
        _currentId.value = id
        prefs.sessionId = id
        _retryNote.value = ""
        autoContinue = 0
        _messages.value = store.loadMessages(id)
        refreshSessions()
    }

    fun newConversation() {
        if (_busy.value) stop()
        saveJob?.cancel()
        saveNow()
        val id = UUID.randomUUID().toString()
        val meta = SessionMeta(id, "新对话", stamp(), false)
        _sessions.value = _sessions.value + meta
        store.saveIndex(_sessions.value)
        _currentId.value = id
        prefs.sessionId = id
        _retryNote.value = ""
        autoContinue = 0
        _messages.value = emptyList()
    }

    fun archiveSession(id: String, archived: Boolean) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(archived = archived)
        _sessions.value = list
        store.saveIndex(list)
        if (id == _currentId.value && archived) selectNextOrEmpty()
    }

    fun deleteSession(id: String) {
        store.deleteMessages(id)
        val list = _sessions.value.filter { it.id != id }.toMutableList()
        _sessions.value = list
        store.saveIndex(list)
        if (id == _currentId.value) selectNextOrEmpty()
    }

    /** 当前会话被删/归档后：优先切到下一个未归档会话；没有则清空进入空态（输入即新建）。 */
    private fun selectNextOrEmpty() {
        saveJob?.cancel()
        val next = _sessions.value.firstOrNull { !it.archived }
        if (next != null) {
            _currentId.value = next.id
            prefs.sessionId = next.id
            _messages.value = store.loadMessages(next.id)
        } else {
            _currentId.value = ""
            prefs.sessionId = null
            _messages.value = emptyList()
        }
        _retryNote.value = ""
        autoContinue = 0
    }

    private fun touchSession(firstUserText: String?) {
        val id = _currentId.value
        if (id.isEmpty()) return
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = list[i]
        val title = if (old.title == "新对话" && !firstUserText.isNullOrBlank()) {
            val t = firstUserText.replace(Regex("\\s+"), " ").trim()
            if (t.length > 20) t.take(20) + "…" else t
        } else old.title
        list[i] = old.copy(title = title, updatedAt = stamp())
        _sessions.value = list
    }

    private fun bootstrapSessions(profileSessionId: String?) {
        store = SessionStore(getApplication(), prefs.profile)
        if (store.loadIndex().isEmpty()) {
            val migrated = store.migrateLegacy(profileSessionId ?: "")
            if (migrated == null) {
                // 无历史会话：进入空态，首次输入再建会话
                _currentId.value = ""
                prefs.sessionId = null
                _messages.value = emptyList()
                _sessions.value = emptyList()
                return
            }
            _currentId.value = migrated.id
            prefs.sessionId = migrated.id
            _messages.value = store.loadMessages(migrated.id)
            _sessions.value = listOf(migrated)
            return
        }
        val list = store.loadIndex().sortedByDescending { it.updatedAt }
        val target = list.firstOrNull { !it.archived } ?: list.first()
        _currentId.value = target.id
        prefs.sessionId = target.id
        _messages.value = store.loadMessages(target.id)
        _sessions.value = list
    }

    // ---------- 连接 ----------

    fun onProfileChanged(p: Prefs) {
        val key = if (p.profile == "default") Keys.DEFAULT_KEY else Keys.FRIEND_KEY
        val prefix = if (p.profile == "default") "" else "/p/friend"
        api = HermesApi(p.serverUrl, key, prefix)
        bootstrapSessions(p.sessionId)
        pingLoop()
        refreshStatus()
        refreshFromServer()
    }

    /** 重开 App 时从服务端拉当前会话消息：后台跑完的任务产出据此补回。 */
    fun refreshFromServer() {
        val a = api ?: return
        val id = _currentId.value
        if (id.isEmpty() || _busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.sessionMessages(id)
                val arr = resp.optJSONArray("data") ?: return@launch
                val list = mutableListOf<Msg>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role", "assistant")
                    if (role != "user" && role != "assistant") continue
                    val content = o.optString("content", "")
                    if (content.isEmpty()) continue
                    list.add(Msg(role, content, pending = false, ts = parseTs(o.optString("timestamp", ""))))
                }
                if (list.isNotEmpty()) {
                    _messages.value = list
                    store.saveMessages(id, list, maxHistory)
                }
            } catch (_: Exception) {
                // 服务端无此会话或网络异常：保留本地内容
            }
        }
    }

    private fun parseTs(s: String): Long = runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun pingLoop() {
        if (pingStarted) return
        pingStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val ok = api?.ping() ?: false
                _online.value = ok
                delay(10000)
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = api?.sysinfo() ?: return@launch
                _statusText.value = formatHealth(h)
            } catch (e: Exception) {
                _statusText.value = "获取失败: " + (e.message ?: "?")
            }
        }
    }

    private fun formatHealth(h: JSONObject): String {
        val sb = StringBuilder()
        sb.append("网关: ").append(h.optString("status", "?")).append("\n")
        val cpu = h.optDouble("cpu_percent", -1.0)
        if (cpu >= 0) {
            sb.append("CPU: ").append(String.format("%.1f", cpu)).append("%  ")
            sb.append(h.optInt("cpu_count", 0)).append(" 核\n")
        }
        val mem = h.optDouble("memory_percent", -1.0)
        if (mem >= 0) {
            sb.append("内存: ").append(String.format("%.1f", mem)).append("%  ")
            sb.append(h.optInt("memory_used_mb", 0)).append("/")
            sb.append(h.optInt("memory_total_mb", 0)).append(" MB\n")
        }
        val disk = h.optDouble("disk_percent", -1.0)
        if (disk >= 0) sb.append("磁盘: ").append(String.format("%.1f", disk)).append("%\n")
        val la = h.optJSONArray("load_avg")
        if (la != null && la.length() >= 3) {
            sb.append("负载: ").append(la.optDouble(0, 0.0)).append(" / ")
            sb.append(la.optDouble(1, 0.0)).append(" / ").append(la.optDouble(2, 0.0)).append("\n")
        }
        val up = h.optLong("uptime_seconds", -1)
        if (up >= 0) {
            sb.append("运行: ").append(up / 3600).append(" 小时 ")
            sb.append((up % 3600) / 60).append(" 分\n")
        }
        sb.append("活跃任务: ").append(h.optInt("active_runs", 0)).append("\n")
        val mt = h.optJSONObject("metrics_today")
        if (mt != null) {
            sb.append("今日请求: ").append(mt.optInt("requests", 0))
            sb.append("  消息: ").append(mt.optInt("messages", 0)).append("\n")
        }
        val hb = h.optString("last_heartbeat", "")
        if (hb.isNotEmpty()) sb.append("心跳: ").append(TimeFmt.isoToBj(hb)).append("（北京）")
        return sb.toString().trim()
    }

    // ---------- 发送与流式接收 ----------

    fun send(text: String) {
        val a = api ?: return
        if (text.isBlank() || _busy.value) return
        // 空态（无选中会话）：直接输入即新建对话
        if (_currentId.value.isEmpty()) {
            val id = UUID.randomUUID().toString()
            _currentId.value = id
            prefs.sessionId = id
            _sessions.value = _sessions.value + SessionMeta(id, "新对话", stamp(), false)
            store.saveIndex(_sessions.value)
        }
        val wasEmpty = _messages.value.none { it.role == "user" }
        setMsgs(_messages.value + Msg("user", text, ts = stamp()))
        touchSession(if (wasEmpty) text else null)
        autoContinue = 0
        startRunWith(a, text)
    }

    private fun startRunWith(a: HermesApi, text: String) {
        setMsgs(_messages.value + Msg("assistant", "", pending = true, ts = stamp()))
        _busy.value = true
        runFinished = false
        lastSeq = -1
        RunService.start(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val run = a.startRun(text, _currentId.value)
                currentRunId = run.optString("run_id", run.optString("id", ""))
                streamRun(a)
            } catch (e: Exception) {
                appendDelta("\n[请求失败] " + (e.message ?: "?"))
                failPending()
            }
        }
    }

    private fun toolLine(ev: com.hermesapp.net.SseEvent, failed: Boolean): String {
        val name = ev.data.optString("tool", "")
        if (name.isEmpty() || name.startsWith("_")) return ""
        val sb = StringBuilder("\n· ")
        sb.append(if (failed) "✗ " else "✓ ").append(name)
        val dur = ev.data.optDouble("duration", -1.0)
        if (dur >= 0) sb.append("  ").append(String.format("%.1f", dur)).append("s")
        val pv = ev.data.optString("preview", "").replace(Regex("\\s+"), " ").trim()
        if (pv.isNotEmpty()) {
            sb.append("\n   ").append(if (pv.length > 120) pv.take(120) + "…" else pv)
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun streamRun(a: HermesApi) {
        val rid = currentRunId ?: return
        currentCall = a.streamEvents(
            runId = rid,
            lastSeq = lastSeq,
            onEvent = { ev ->
                if (ev.id != null) lastSeq = ev.id
                val name = ev.event ?: ev.data.optString("event", "")
                when (name) {
                    "message.delta" -> appendDelta(ev.data.optString("delta", ""))
                    "message.interim" -> {}
                    "tool.started" -> {}
                    "tool.completed" -> {
                        val line = toolLine(ev, ev.data.optBoolean("error", false))
                        if (line.isNotEmpty()) appendDelta(line)
                    }
                    "tool.failed" -> {
                        val line = toolLine(ev, true)
                        if (line.isNotEmpty()) appendDelta(line)
                    }
                    "run.completed" -> {
                        runFinished = true
                        val out = ev.data.optString("output", "")
                        if (out.isNotEmpty()) setPendingText(out) else finishPending()
                        doneOk()
                    }
                    "run.failed" -> {
                        runFinished = true
                        appendDelta("\n[失败] " + ev.data.optString("error", "未知错误"))
                        finishPending()
                        maybeContinue()
                    }
                    "run.cancelled", "run.interrupted" -> {
                        runFinished = true
                        appendDelta("\n[已中断]")
                        doneOk()
                    }
                }
            },
            onClosed = { if (_busy.value && !runFinished) maybeContinue() },
            onError = { e ->
                if (!runFinished) appendDelta("\n[连接断开] " + (e.message ?: "?"))
                if (_busy.value && !runFinished) maybeContinue()
            }
        )
    }

    private fun maybeContinue() {
        if (runFinished) return
        if (autoContinue >= 3) {
            _retryNote.value = "已自动重试 3 次，仍未完成"
            failPending()
            return
        }
        autoContinue++
        _retryNote.value = "任务未完成，自动续跑 " + autoContinue + "/3"
        finishPending()
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            delay(1200)
            startRunWith(a, "继续")
        }
    }

    fun stop() {
        val a = api ?: return
        val rid = currentRunId ?: return
        runFinished = true
        viewModelScope.launch(Dispatchers.IO) { a.stopRun(rid) }
        currentCall?.cancel()
        appendDelta("\n[已请求停止]")
        finishPending()
        _busy.value = false
        RunService.stop(getApplication())
    }

    private fun appendDelta(d: String) {
        if (d.isEmpty()) return
        val list = _messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = list[i].text + d)
        else list.add(Msg("assistant", d, pending = true, ts = stamp()))
        setMsgs(list)
    }

    private fun setPendingText(t: String) {
        val list = _messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = t, pending = false)
        setMsgs(list)
    }

    private fun finishPending() {
        val list = _messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(pending = false)
        setMsgs(list)
    }

    private fun failPending() {
        finishPending()
        _busy.value = false
        RunService.stop(getApplication())
    }

    private fun doneOk() {
        autoContinue = 0
        _retryNote.value = ""
        finishPending()
        _busy.value = false
        RunService.stop(getApplication())
    }

    // ---------- 自更新 ----------

    /** 只检查，不下载。有新版本时挂到 pendingUpdate，由界面弹确认框。 */
    fun checkUpdate(currentVersionCode: Int, ctx: Context) {
        val a = api ?: return
        _updateNote.value = "检查中…"
        _pendingUpdate.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate()
                if (info == null) {
                    _updateNote.value = "检查失败：无法获取版本信息"
                    return@launch
                }
                if (info.versionCode <= currentVersionCode) {
                    _updateNote.value = "已是最新版本 " + info.versionName
                    return@launch
                }
                _updateNote.value = ""
                _pendingUpdate.value = info
            } catch (e: Exception) {
                _updateNote.value = "检查失败：" + (e.message ?: "?")
            }
        }
    }

    fun dismissUpdate() {
        _pendingUpdate.value = null
    }

    /** 用户点「下载并安装」后调用：带进度下载，完成后拉起安装。 */
    fun confirmUpdate(ctx: Context) {
        val a = api ?: return
        val info = _pendingUpdate.value ?: return
        _pendingUpdate.value = null
        _updateNote.value = "下载中…"
        _downloadPct.value = 0
        _downloadText.value = "0%"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = a.downloadApk(info.url, ctx) { done, total ->
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        _downloadPct.value = pct
                        _downloadText.value = pct.toString() + "%  " + fmtSize(done) + "/" + fmtSize(total)
                    } else {
                        _downloadText.value = fmtSize(done)
                    }
                }
                if (f == null) {
                    _updateNote.value = "下载失败"
                    _downloadPct.value = -1
                    return@launch
                }
                _downloadPct.value = 100
                _downloadText.value = "100%"
                _updateNote.value = "下载完成，请在弹出的提示中安装"
                launch(Dispatchers.Main) { installApk(ctx, f) }
            } catch (e: Exception) {
                _updateNote.value = "下载失败：" + (e.message ?: "?")
                _downloadPct.value = -1
            }
        }
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
        b >= 1024L -> String.format("%.0f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    private fun installApk(ctx: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            _updateNote.value = "安装失败：" + (e.message ?: "?")
        }
    }
}