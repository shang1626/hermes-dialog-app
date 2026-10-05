package com.hermesapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
    /** 本条消息附带的本地图片路径（用户发的图用于气泡回显缩略图）。 */
    val images: List<String> = emptyList(),
    /** 本条消息附带的非图片附件名（气泡里回显成文件卡片）。 */
    val files: List<String> = emptyList(),
    /** 过程轨迹（工具调用等）。与正文分开存，界面上默认折叠，不占屏幕。 */
    val trace: String = "",
    /** 本轮 token 用量（run.completed 的 usage），仅助手消息有。 */
    val usage: Usage? = null,
    /** 审批卡片：服务端在等一个 choice 回执；空表示无待审批。 */
    val approval: ApprovalCard? = null,
    /** 子任务进度（delegate_task 派出的子代理），按开始顺序排列。 */
    val subagents: List<SubagentLine> = emptyList(),
    /** 稳定唯一 id：给 LazyColumn 做 key，避免滑动时整列重组。 */
    val id: Long = nextMsgId(),
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextMsgId(): Long = counter.incrementAndGet()
    }
}

/** 审批卡片：服务端 approval.request 事件下发，用户选一个 choice 回执后置 resolved。 */
data class ApprovalCard(
    val requestId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
    val resolved: String = "",
)

/** 一轮对话的 token 用量（run.completed 的 usage 字段）。 */
data class Usage(
    val input: Int,
    val output: Int,
    val total: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    /** 本轮耗时（毫秒），用来算每秒出多少 token；0 表示没测到。 */
    val durationMs: Long = 0L,
)

/** 一条子任务进度（subagent.start / subagent.complete）。 */
data class SubagentLine(
    val id: String,
    val goal: String,
    val status: String,
    val summary: String = "",
)

/** 待发送的附件：uri 用于展示（图片缩略图），file 是拷进沙盒后的真实文件。 */
data class PendingImage(
    val id: Long,
    val uri: String,
    val file: java.io.File,
    /** 是否图片：决定待发区显示缩略图还是文件卡片。 */
    val isImage: Boolean = true,
)

/** 状态页的一行：标签 + 值。value 为空则该行不显示。 */
data class StatusItem(val label: String, val value: String)

/** 状态页的一个分组：标题 + 若干行。 */
data class StatusSection(val title: String, val items: List<StatusItem>)

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

    private val _statusSections = MutableStateFlow<List<StatusSection>>(emptyList())
    val statusSections = _statusSections.asStateFlow()

    private val _statusErr = MutableStateFlow("")
    val statusErr = _statusErr.asStateFlow()

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

    /** 待发送图片（选好未发送）。 */
    private val _pendingImages = MutableStateFlow<List<PendingImage>>(emptyList())
    val pendingImages = _pendingImages.asStateFlow()

    /** 模型是否支持原生图片（/v1/capabilities features.supports_vision）；未知按 true。 */
    private val _supportsVision = MutableStateFlow(true)
    val supportsVision = _supportsVision.asStateFlow()

    /** 图片上传进度文案（空表示无进行中上传）。 */
    private val _imageNote = MutableStateFlow("")
    val imageNote = _imageNote.asStateFlow()

    private var api: HermesApi? = null
    private var currentCall: Call? = null
    private var currentRunId: String? = null
    private var lastSeq = -1
    private var autoContinue = 0
    private var runFinished = false
    private var pingStarted = false
    private var saveJob: Job? = null
    /** 本轮开始时间：用来算「每秒出多少 token」。 */
    private var runStartedAt = 0L

    init {
        // 通知栏直接回复：先取落盘的（App 被杀过），再收运行中的广播
        drainPendingReply()
        viewModelScope.launch {
            com.hermesapp.PendingReply.flow.collect { drainPendingReply() }
        }
    }

    /** 取出通知栏回复并作为用户消息发出（连接没建好或正忙时留着，下次再取）。 */
    private fun drainPendingReply() {
        val raw = prefs.pendingReply
        if (raw.isEmpty()) return
        val sid = raw.substringBefore('\u0000')
        val text = raw.substringAfter('\u0000')
        if (text.isBlank()) { prefs.pendingReply = ""; return }
        if (api == null || _busy.value) return
        prefs.pendingReply = ""
        if (sid.isNotEmpty() && sid != _currentId.value) switchSession(sid)
        send(text)
    }

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
        fetchCapabilities()
        resumeActiveRun()
        drainPendingReply()
    }

    /**
     * 重开 App 时恢复「正在跑的任务」状态：
     * 本地存了 activeRunId 就查一次 /v1/runs/{id}——还在跑就把按钮恢复成「停止」并继续接流，
     * 已结束则清掉标记并重新从服务端拉会话内容（后台跑完的产出据此补回）。
     */
    fun resumeActiveRun() {
        val a = api ?: return
        val rid = prefs.activeRunId
        if (rid.isEmpty() || _busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val st = try {
                a.getRun(rid).optString("status", "")
            } catch (_: Exception) {
                ""
            }
            val running = st in setOf("started", "running", "waiting_for_approval", "queued")
            if (running) {
                currentRunId = rid
                runFinished = false
                lastSeq = -1
                _busy.value = true
                RunService.start(getApplication())
                streamRun(a)
            } else {
                prefs.activeRunId = ""
                refreshFromServer()
            }
        }
    }

    /** 拉一次服务端能力：是否支持原生图片（决定图片是原生附图还是先转文字）。 */
    fun fetchCapabilities() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val caps = a.capabilities()
                val f = caps.optJSONObject("features")
                if (f != null) _supportsVision.value = f.optBoolean("supports_vision", true)
            } catch (_: Exception) {
            }
        }
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
                _statusSections.value = buildStatus(h)
                _statusErr.value = ""
            } catch (e: Exception) {
                _statusErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    /** 把 /health/sysinfo 的扁平字段整理成「分组 → 行」结构，供状态页排版渲染。 */
    private fun buildStatus(h: JSONObject): List<StatusSection> {
        val out = mutableListOf<StatusSection>()

        fun sec(title: String, items: List<StatusItem>) {
            val keep = items.filter { it.value.isNotEmpty() }
            if (keep.isNotEmpty()) out.add(StatusSection(title, keep))
        }
        fun it(label: String, v: String?): StatusItem =
            StatusItem(label, v.orEmpty())

        val gw = mutableListOf<StatusItem>()
        gw.add(it("运行状态", if (h.optString("status") == "ok") "正常" else h.optString("status", "?")))
        val pid = h.optInt("pid", 0)
        if (pid > 0) gw.add(it("进程 PID", pid.toString()))
        gw.add(it("操作系统", h.optString("platform", "")))
        gw.add(it("Python", h.optString("python", "")))
        sec("网关", gw)

        val cpu = mutableListOf<StatusItem>()
        cpu.add(it("型号", h.optString("cpu_model", "")))
        val cpuPct = h.optDouble("cpu_percent", -1.0)
        if (cpuPct >= 0) cpu.add(it("使用率", String.format("%.1f%%", cpuPct)))
        val cores = h.optInt("cpu_count", 0)
        if (cores > 0) cpu.add(it("核心数", cores.toString() + " 核"))
        val freq = h.optInt("cpu_freq_mhz", 0)
        if (freq > 0) cpu.add(it("主频", freq.toString() + " MHz"))
        sec("CPU", cpu)

        val mem = mutableListOf<StatusItem>()
        val memPct = h.optDouble("memory_percent", -1.0)
        if (memPct >= 0) mem.add(it("使用率", String.format("%.1f%%", memPct)))
        val mUsed = h.optInt("memory_used_mb", 0)
        val mTotal = h.optInt("memory_total_mb", 0)
        if (mTotal > 0) mem.add(it("已用/总量", mUsed.toString() + " MB / " + mTotal.toString() + " MB"))
        val pmem = h.optInt("proc_memory_mb", 0)
        if (pmem > 0) mem.add(it("网关进程", pmem.toString() + " MB"))
        sec("内存", mem)

        val disk = mutableListOf<StatusItem>()
        val dTotal = h.optDouble("disk_total_gb", -1.0)
        if (dTotal >= 0) disk.add(it("总大小", String.format("%.1f GB", dTotal)))
        val dUsed = h.optDouble("disk_used_gb", -1.0)
        if (dUsed >= 0) disk.add(it("已用", String.format("%.1f GB", dUsed)))
        val dFree = h.optDouble("disk_free_gb", -1.0)
        if (dFree >= 0) disk.add(it("可用", String.format("%.1f GB", dFree)))
        val dPct = h.optDouble("disk_percent", -1.0)
        if (dPct >= 0) disk.add(it("使用率", String.format("%.1f%%", dPct)))
        sec("磁盘", disk)

        val run = mutableListOf<StatusItem>()
        val la = h.optJSONArray("load_avg")
        if (la != null && la.length() >= 3) {
            run.add(it("负载 1/5/15", String.format("%.2f / %.2f / %.2f",
                la.optDouble(0, 0.0), la.optDouble(1, 0.0), la.optDouble(2, 0.0))))
        }
        val up = h.optLong("uptime_seconds", -1)
        if (up >= 0) run.add(it("已运行", (up / 86400).toString() + " 天 " + ((up % 86400) / 3600).toString() + " 小时 " + ((up % 3600) / 60).toString() + " 分"))
        sec("运行", run)

        val apiSec = mutableListOf<StatusItem>()
        apiSec.add(it("当前模型", h.optString("model", "").ifEmpty { "未知" }))
        val mt = h.optJSONObject("metrics_today")
        if (mt != null) {
            apiSec.add(it("今日请求", mt.optInt("requests", 0).toString()))
            apiSec.add(it("今日消息", mt.optInt("messages", 0).toString()))
        }
        apiSec.add(it("活跃任务", h.optInt("active_runs", 0).toString()))
        apiSec.add(it("子任务", h.optInt("active_delegations", 0).toString()))
        apiSec.add(it("队列深度", h.optInt("process_queue_depth", 0).toString()))
        val hb = h.optString("last_heartbeat", "")
        if (hb.isNotEmpty()) apiSec.add(it("最后心跳", TimeFmt.isoToBj(hb) + "（北京）"))
        sec("API 与任务", apiSec)

        return out
    }

    // ---------- 发送与流式接收 ----------

    fun send(text: String) {
        val a = api ?: return
        val imgs = _pendingImages.value
        if ((text.isBlank() && imgs.isEmpty()) || _busy.value) return
        // 空态（无选中会话）：直接输入即新建对话
        if (_currentId.value.isEmpty()) {
            val id = UUID.randomUUID().toString()
            _currentId.value = id
            prefs.sessionId = id
            _sessions.value = _sessions.value + SessionMeta(id, "新对话", stamp(), false)
            store.saveIndex(_sessions.value)
        }
        val wasEmpty = _messages.value.none { it.role == "user" }
        setMsgs(
            _messages.value + Msg(
                "user", text, ts = stamp(),
                images = imgs.filter { it.isImage }.map { it.uri },
                files = imgs.filter { !it.isImage }.map { it.file.name },
            )
        )
        touchSession(if (wasEmpty) text else null)
        _pendingImages.value = emptyList()
        autoContinue = 0
        startRunWith(a, text, imgs.map { it.file })
    }

    /** 把选中的图片拷进 App 沙盒，加入待发列表。 */
    fun addImage(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 50L * 1024 * 1024) {
                    _imageNote.value = "图片超过 50MB：" + name
                    return@launch
                }
                val dir = File(app.filesDir, "outbox").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "jpg").lowercase().let {
                    if (it.length in 1..5) it else "jpg"
                }
                val dst = File(dir, "img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    _imageNote.value = "读取图片失败"
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    dst.delete()
                    return@launch
                }
                _imageNote.value = ""
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = true)
            } catch (e: Exception) {
                _imageNote.value = "读取图片失败：" + (e.message ?: "?")
            }
        }
    }

    /** 把任意文件拷进 App 沙盒，加入待发列表（非图片走文件卡片展示）。 */
    fun addFile(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 50L * 1024 * 1024) {
                    _imageNote.value = "文件超过 50MB：" + name
                    return@launch
                }
                val dir = File(app.filesDir, "outbox").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "").lowercase().let {
                    if (it.length in 1..5) it else "bin"
                }
                val dst = File(dir, "file_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    _imageNote.value = "读取文件失败"
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    dst.delete()
                    return@launch
                }
                val isImg = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                _imageNote.value = ""
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = isImg)
            } catch (e: Exception) {
                _imageNote.value = "读取文件失败：" + (e.message ?: "?")
            }
        }
    }

    fun removeImage(id: Long) {
        val list = _pendingImages.value
        list.firstOrNull { it.id == id }?.file?.delete()
        _pendingImages.value = list.filter { it.id != id }
    }

    private fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "image.jpg"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    // 按真实扩展名给 MIME：图片走 image 类，其余按常见类型给，未知一律 octet-stream。
    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "html", "htm" -> "text/html"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun startRunWith(a: HermesApi, text: String, files: List<File> = emptyList()) {
        setMsgs(_messages.value + Msg("assistant", "", pending = true, ts = stamp()))
        _busy.value = true
        runFinished = false
        lastSeq = -1
        runStartedAt = System.currentTimeMillis()
        if (prefs.keepAlive) RunService.start(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = mutableListOf<String>()
                for ((i, f) in files.withIndex()) {
                    _imageNote.value = "上传附件 ${i + 1}/${files.size}…"
                    ids.add(a.uploadImage(f.readBytes(), f.name, mimeOf(f)))
                }
                _imageNote.value = ""
                val run = a.startRun(text, _currentId.value, ids)
                currentRunId = run.optString("run_id", run.optString("id", ""))
                prefs.activeRunId = currentRunId ?: ""
                runStartedAt = System.currentTimeMillis()
                streamRun(a)
            } catch (e: Exception) {
                _imageNote.value = ""
                appendDelta("\n[请求失败] " + (e.message ?: "?"))
                failPending()
            }
        }
    }

    /** 审批请求：挂到当前助手气泡上，等用户点按钮回执。 */
    private fun attachApproval(ev: com.hermesapp.net.SseEvent) {
        val rid = ev.data.optString("request_id", "")
        val cmd = ev.data.optString("command", "")
        val desc = ev.data.optString("description", "")
        val chs = mutableListOf<String>()
        ev.data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        if (rid.isEmpty() || chs.isEmpty()) return
        val card = ApprovalCard(rid, cmd, desc, chs)
        val list = _messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(approval = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), approval = card))
        setMsgs(list)
    }

    /** 用户点了审批按钮：回执给服务端，并把卡片置为已选。 */
    fun respondApproval(msgId: Long, choice: String) {
        val a = api ?: return
        val rid = currentRunId ?: return
        val list = _messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].approval ?: return
        if (card.resolved.isNotEmpty()) return
        list[i] = list[i].copy(approval = card.copy(resolved = choice))
        setMsgs(list)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { a.respondApproval(rid, card.requestId, choice) }
        }
    }

    /** 轮末 token 用量：挂到最后一条助手消息上。 */
    private fun attachUsage(ev: com.hermesapp.net.SseEvent) {
        val u = ev.data.optJSONObject("usage") ?: return
        val usage = Usage(
            input = u.optInt("input_tokens", 0),
            output = u.optInt("output_tokens", 0),
            total = u.optInt("total_tokens", 0),
            cacheRead = u.optInt("cache_read_tokens", 0),
            cacheWrite = u.optInt("cache_write_tokens", 0),
            durationMs = if (runStartedAt > 0) System.currentTimeMillis() - runStartedAt else 0L,
        )
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0) return
        val list = _messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(usage = usage)
        setMsgs(list)
    }

    /** 子任务开始/结束：按 subagent_id 或 goal 归并成一行进度。 */
    private fun upsertSubagent(ev: com.hermesapp.net.SseEvent, running: Boolean) {
        val id = ev.data.optString("subagent_id", "").ifEmpty { ev.data.optString("delegation_id", "") }
        val goal = ev.data.optString("goal", "")
        val summary = ev.data.optString("summary", "")
        val statusRaw = ev.data.optString("status", "")
        val key = id.ifEmpty { goal }
        if (key.isEmpty()) return
        val status = when {
            running -> "running"
            statusRaw.isNotEmpty() -> statusRaw
            else -> "completed"
        }
        val list = _messages.value.toMutableList()
        val mi = list.indexOfLast { it.role == "assistant" && it.pending }
        if (mi < 0) return
        val old = list[mi].subagents
        val idx = old.indexOfFirst { it.id == key }
        val line = SubagentLine(key, goal.ifEmpty { old.getOrNull(idx)?.goal ?: "" }, status, summary)
        val next = if (idx >= 0) old.toMutableList().also { it[idx] = line } else old + line
        list[mi] = list[mi].copy(subagents = next)
        setMsgs(list)
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
                        if (line.isNotEmpty()) appendTrace(line)
                    }
                    "tool.failed" -> {
                        val line = toolLine(ev, true)
                        if (line.isNotEmpty()) appendTrace(line)
                    }
                    "approval.request" -> attachApproval(ev)
                    "subagent.start" -> upsertSubagent(ev, running = true)
                    "subagent.complete" -> upsertSubagent(ev, running = false)
                    "run.completed" -> {
                        runFinished = true
                        val out = ev.data.optString("output", "")
                        if (out.isNotEmpty()) setPendingText(out) else finishPending()
                        attachUsage(ev)
                        doneOk()
                        notifyIfBackground(out)
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

    /**
     * SSE 流提前断开（没收到 run.completed）时的续接：
     * 绝不伪造用户消息——过去这里会 startRunWith(a, "继续")，把「继续」当成用户输入
     * 写进会话（用户看到自己没发过的消息）。改为：先查同一 run 的状态，
     * 仍在跑就续接它的事件流；已结束就从服务端拉回结果。
     */
    private fun maybeContinue() {
        if (runFinished) return
        if (autoContinue >= 3) {
            _retryNote.value = "已自动重连 3 次，仍未完成"
            failPending()
            return
        }
        autoContinue++
        _retryNote.value = "连接中断，正在确认任务状态 " + autoContinue + "/3"
        val a = api ?: return
        val rid = currentRunId
        if (rid.isNullOrEmpty()) { failPending(); return }
        viewModelScope.launch(Dispatchers.IO) {
            delay(1200)
            if (!_busy.value || runFinished) return@launch
            val st = try { a.getRun(rid).optString("status", "") } catch (_: Exception) { "" }
            if (st in setOf("started", "running", "waiting_for_approval", "queued", "stopping")) {
                _retryNote.value = ""
                streamRun(a)   // 续接同一 run，不新增任何用户消息
            } else {
                _retryNote.value = ""
                runFinished = true
                finishPending()
                _busy.value = false
                prefs.activeRunId = ""
                RunService.stop(getApplication())
                refreshFromServer()  // 任务已结束：拉回服务端产出
            }
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
        prefs.activeRunId = ""
        RunService.stop(getApplication())
    }

    /** 工具轨迹追加到当前 assistant 消息的 trace（界面默认折叠，不进正文）。 */
    private fun appendTrace(d: String) {
        if (d.isEmpty()) return
        val list = _messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(trace = list[i].trace + d)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), trace = d))
        setMsgs(list)
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
        prefs.activeRunId = ""
        RunService.stop(getApplication())
    }

    private fun doneOk() {
        autoContinue = 0
        _retryNote.value = ""
        finishPending()
        _busy.value = false
        prefs.activeRunId = ""
        RunService.stop(getApplication())
        drainPendingReply()
    }

    /** App 不在前台时，任务完成弹系统通知（提示音+震动）。前台则静默，界面自己会更新。 */
    private fun notifyIfBackground(output: String) {
        if (AppForeground.isForeground || !prefs.keepAlive) return
        val app = getApplication<Application>()
        val body = output.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "任务已完成" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyMessage(app, "Hermes 回复", body, _currentId.value)
    }

    /** 缓存占用文案（待发图片 + 安装包 + 图片缓存），供设置页显示。 */
    private val _cacheText = MutableStateFlow("")
    val cacheText = _cacheText.asStateFlow()

    fun refreshCache() {
        val app = getApplication<Application>()
        _cacheText.value = fmtSize(CacheUtil.total(app))
    }

    /** 清理临时缓存；会话记录与草稿不动。 */
    fun clearCache() {
        val app = getApplication<Application>()
        CacheUtil.clear(app)
        refreshCache()
    }

    /** 设置页切换「后台运行」时调用：关掉立即停掉前台服务，常驻通知随之消失。 */
    fun setKeepAlive(on: Boolean) {
        prefs.keepAlive = on
        if (!on) RunService.stop(getApplication())
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