package com.hermesapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesapp.net.HermesApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** SSE 断流后的最大自动重连次数（退避等待，见 ChatViewModel.backoffDelayMs）。 */
private const val MAX_RECONNECT_ATTEMPTS = ReconnectPolicy.MAX_RECONNECT_ATTEMPTS

/**
 * 回前台自动同步的节流窗口：来回快速切前后台时，别每次回前台都打一遍接口。
 * 3 秒足够挡住「切出去看一眼通知再切回来」这类连击。
 */
private const val FOREGROUND_SYNC_THROTTLE_MS = 3_000L

/** 过程轨迹的行数：服务端把每个工具调用也存成一行，用它当「本地原始行」代理的一部分。 */
private fun traceLineCount(trace: String): Int =
    if (trace.isEmpty()) 0 else trace.count { it == '\n' } + (if (trace.endsWith("\n")) 0 else 1)

/**
 * 「服务端还在跑」的状态集合：探测到这些状态就续接事件流，不判结束。
 * 注意必须与 api_server 的 run 状态机一致（含 stopping——已请求停止但还没收尾）。
 */
private val RUNNING_STATES = ReconnectPolicy.RUNNING_STATES

/** 重开 App 恢复活跃任务时，探测状态的尝试次数（开机网络未就绪时多试几次）。 */
private const val RESUME_PROBE_TRIES = 3

/**
 * 历史域名 → 当前域名。App 把服务器地址存在 prefs 里（登录时填的那次），
 * 就地升级不会改；域名一换，老用户的地址就成了死链，表现正是「一直重连连不上」。
 * 命中即静默改写成新地址，用户不用重新登录。
 *
 * 具体域名对不写进源码：由 local.properties 的 HERMES_LEGACY_HOSTS
 * （形如 old.example.com=new.example.com，多个用逗号分隔）在构建期注入。
 */
private val LEGACY_HOSTS: Map<String, String> = BuildConfig.LEGACY_HOSTS
    .split(",")
    .mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }
    .toMap()

/** 断线丢事件时补进正文的提示行。 */
private const val TRUNCATED_NOTICE = "\n[提示] 断线期间有内容未收到，已从服务端补拉最新结果\n"

/**
 * 一次工具调用的开始现场：起始时刻 + 服务端给的入参预览（terminal 就是命令本身）。
 * 完成事件在 canonical turn 链路上既不带 duration 也不带 preview，全靠这里记。
 */
private data class ToolStart(val startMs: Long, val preview: String)

/**
 * 断流翻历史的盯梢窗口：重连退避用尽后，改为轮询服务端会话记录等答案落盘。
 * 首次等 5 秒，逐次翻倍到 30 秒封顶，最多盯 30 分钟。
 */
private const val HISTORY_RECOVERY_WINDOW_MS = 30L * 60_000L

/**
 * 状态轮询等结果（流已接不上、但服务端说这一轮还在跑）的参数。
 *
 * 为什么必须单独有这条路：服务端的 SSE 缓冲有自己的存活期，过期后 /events 永远 404，
 * 而 GET /v1/runs/{id} 仍回 running —— 这时反复重起流只会每次都秒 404（实测一条 run
 * 连撞 24 次），翻历史又需要本地发送锚点（重开 App 恢复的轮次没有）。长任务实测能跑
 * 24 分钟以上，窗口给到 2 小时。
 */
private const val LIVE_STATUS_POLL_INTERVAL_MS = 20_000L
private const val LIVE_STATUS_POLL_WINDOW_MS = 120L * 60_000L
private const val LIVE_STATUS_POLL_MAX_MISSES = 5

/**
 * 落盘检查点（R03）：把「这一份属于哪一轮」一起带出来。
 * 旧实现只带 seq 与正文，提交时不认轮次——写盘期间用户停掉 A 并起了 B，放行后
 * 仍按当前会话把 A 的 seq 提交进 prefs（探针实测 persistedSeq={s:100} 落在 run-B 上），
 * B 断线续接时会按这个序号跳过事件。
 */
private data class SaveCheckpoint(val gen: Int, val runId: String, val seq: Int, val msgs: List<Msg>)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app).bound()
    private val identity = prefs.identity
    private val identityJob = kotlinx.coroutines.SupervisorJob()
    private val identityScope = kotlinx.coroutines.CoroutineScope(identityJob + Dispatchers.Main.immediate)
    @Volatile private var detached = false
    private var store = SessionStore(app, identity, prefs.legacyServer)

    /**
     * 会话索引是否已经读进内存（bootstrapSessions 结束前置位）。
     *
     * 为什么要这道门：启动时 UI 会先把「上次停留的会话」恢复出来，走的是 switchSession →
     * saveCurrentAsync → saveIndexAsync；而索引是异步读的（Dispatchers.IO）。读盘慢过 300ms
     * 去抖窗口时，落盘那一刻 `_sessions.value` 还是空的，索引文件就被写成空数组 ——
     * 2026-10-14 事故：4 条会话从侧边栏消失，索引只剩 26 字节。
     * 索引没读完之前一次都不许写盘（那时也没有「变更」可写），读完才放开。
     */
    @Volatile private var indexReady = false

    init {
        // 留一行证据：同一个进程里 ViewModel 重建会打多行，
        // 用于确认「切后台回来进度丢」是不是被系统重建引起。
        AppLog.log("vm", "ChatViewModel 新建 pid=" + android.os.Process.myPid())
    }

    // 会话运行态改放进程级单例（见 RuntimeHub.kt）：重建接回同一份，不再分叉。
    private val runtimes get() = RuntimeHub.runtimes(identity.key)
    private fun rt(id: String): SessionRuntime = runtimes.computeIfAbsent(id) { SessionRuntime(it) }

    private val _currentId = MutableStateFlow("")
    val currentId = _currentId.asStateFlow()

    /** 当前会话的消息/忙闲/重连提示：随 _currentId 切换，后台会话互不影响。 */
    val messages: StateFlow<List<Msg>> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(emptyList<Msg>()) else rt(id).messages }
        .stateIn(identityScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前会话里的全部子任务（跨气泡汇总、按 id 去重取最新状态），供对话窗口顶部的面板用。
     *
     * 为什么要在顶部汇总：子任务原来只在它那一条气泡里显示，对话一长就得往回翻半天才能找到
     * 「那个子任务跑到哪了」。汇总到顶部后，不管对话多长都在同一处看。
     * 排序：正在跑的排前面，同组内按开始时间倒序（新的在上）。
     */
    val sessionSubagents: StateFlow<List<SubagentLine>> = _currentId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(emptyList())
            else rt(id).messages.map { msgs ->
                val byId = LinkedHashMap<String, SubagentLine>()
                for (m in msgs) {
                    for (s in m.subagents) byId[s.id] = s   // 后出现的覆盖先前的 = 取最新状态
                }
                byId.values.sortedWith(
                    compareBy({ it.status != "running" }, { -it.startedAt })
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(identityScope, SharingStarted.Eagerly, emptyList())

    val busy: StateFlow<Boolean> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(false) else rt(id).busy }
        .stateIn(identityScope, SharingStarted.Eagerly, false)

    val retryNote: StateFlow<String> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf("") else rt(id).retryNote }
        .stateIn(identityScope, SharingStarted.Eagerly, "")

    /**
     * 当前会话里「在等我点头」的卡片（审批或澄清），供对话窗口顶部一条醒目提示。
     *
     * 为什么要置顶：卡片原来是嵌在助手气泡里的（和正文同一块），对话一长就埋在中间，
     * 得滚半天才看得到 —— 用户报「长文本上面看不到」。置顶后不管滚到哪都在眼前，
     * 点一下直接跳到那张卡片。数据源就是本地已有的消息，不额外发请求。
     */
    data class PendingAction(val msgId: Long, val title: String, val summary: String, val isApproval: Boolean)

    val pendingAction: StateFlow<PendingAction?> = _currentId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(null)
            else rt(id).messages.map { msgs ->
                // 取最后一条未处理的卡片；审批优先（同会话极少两者同时有）。
                // 失效（expired）的不算「在等我点头」——它们只是留痕，不能再置顶提示。
                val ap = msgs.lastOrNull {
                    it.approval?.resolved?.isEmpty() == true && it.approval?.expired != true
                }
                val cl = msgs.lastOrNull {
                    it.clarify?.resolved?.isEmpty() == true && it.clarify?.expired != true
                }
                when {
                    ap != null -> PendingAction(
                        ap.id, "需要你确认",
                        approvalDescZh(ap.approval?.description?.ifEmpty { ap.approval?.command.orEmpty() }
                            .orEmpty()), true)
                    cl != null -> PendingAction(
                        cl.id, "需要你选一下", cl.clarify?.question.orEmpty(), false)
                    else -> null
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(identityScope, SharingStarted.Eagerly, null)

    /** 当前会话排队待发的条数：输入栏显示「排队 N 条」。 */
    val queuedCount: StateFlow<Int> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(0) else rt(id).queued }
        .stateIn(identityScope, SharingStarted.Eagerly, 0)

    /** 当前会话的待发队列是否被「停止」按住（界面显示「继续」按钮）。 */
    val queuedPaused: StateFlow<Boolean> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(false) else rt(id).queuePaused }
        .stateIn(identityScope, SharingStarted.Eagerly, false)

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online = _online.asStateFlow()

    /**
     * 顶栏显示的服务器 CPU 使用率（百分比；-1 = 还没取到，界面不显示）。
     * 与在线心跳同拍（每 5 秒）拉一次 /health/sysinfo，失败保留上一次的值——
     * 偶发一次超时不该让数字跳成空白。颜色由界面按阈值上色（低绿/中黄/高红）。
     */
    private val _cpuPercent = MutableStateFlow(-1.0)
    val cpuPercent = _cpuPercent.asStateFlow()
    /** 顶栏 CPU% 的刷新周期（用户定版 2026-10-11：8 秒一次，且只要前台就刷新，与所在页面无关）。 */
    private val CPU_REFRESH_MS = 8_000L
    private var cpuTicker: Job? = null
    /** 主线程卡顿看门狗：每秒打点（见 startStallWatch）。 */
    private val STALL_TICK_MS = 1_000L
    /** 两次打点间隔超过它就认为主线程被拖住了（正常主线程空闲时几乎为 0）。 */
    private val STALL_WARN_MS = 2_500L
    /** 同一个卡顿别刷屏：两条之间至少隔这么久。 */
    private val STALL_LOG_GAP_MS = 10_000L
    private var stallWatch: Job? = null

    private val _statusSections = MutableStateFlow<List<StatusSection>>(emptyList())
    val statusSections = _statusSections.asStateFlow()

    /** 顶部概览卡 + 进度条数据（与 _statusSections 同一次 /health/sysinfo 拉取产出）。 */
    private val _statusHero = MutableStateFlow<StatusHero?>(null)
    val statusHero = _statusHero.asStateFlow()

    private val _statusMetrics = MutableStateFlow<List<StatusMetric>>(emptyList())
    val statusMetrics = _statusMetrics.asStateFlow()

    private val _statusErr = MutableStateFlow("")
    val statusErr = _statusErr.asStateFlow()

    private val _updateNote = MutableStateFlow("")
    val updateNote = _updateNote.asStateFlow()

    /** 有待确认的新版本时为非空。 */
    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()

    /** 服务端存在比本机更新的版本时为 true：抽屉里「设置」右上角显示绿点。 */
    private val _updateBadge = MutableStateFlow(false)
    val updateBadge = _updateBadge.asStateFlow()

    /** 正在跑任务的会话 id 集合：会话列表里给它们显示「执行中」标识。 */
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds = _runningIds.asStateFlow()

    /**
     * 侧边栏每条会话的状态提示：跑着任务 / 几个子任务在跑 / 排队几条。
     *
     * 为什么单独一份（而不是只有一个 running 集合）：子任务是后台子代理，可能比父轮次活得久
     * （父 run 结束了子代理还在干），只按「本地 busy」打点会漏掉这种情况——用户看着会话列表
     * 一片安静，其实里面还有活。见 refreshRunFlags。
     */
    private val _runFlags = MutableStateFlow<Map<String, SessionRunFlag>>(emptyMap())
    val runFlags = _runFlags.asStateFlow()
    private var runFlagsTicker: Job? = null

    /**
     * 服务端说「这些会话正在跑」：sessionId -> runId（GET /api/sessions 的 active_run）。
     *
     * 为什么必须有：本地 runtimes 只记录本进程发起的轮次。别的端（微信 / CLI / 桌面）起
     * 的轮次，App 一无所知——侧边栏无提示、也不去接流、跑完不通知；App 被杀过再启动同样
     * 如此（发送后立刻被杀那一瞬还没落盘）。这份映射是唯一能覆盖这两种情况的信息源。
     */
    private val _serverRuns = MutableStateFlow<Map<String, String>>(emptyMap())
    // 注：曾暴露过 `val serverRuns = _serverRuns.asStateFlow()`，无人订阅，已删（内部仍用 _serverRuns）。

    /** 服务端每行 last_active（毫秒）。回前台挑「比本地新」的会话对齐时用。 */
    private var serverLastActive: Map<String, Long> = emptyMap()

    /**
     * 服务端每行的 message_count（含工具行）。
     * 用来算「本地落后服务端多少行」，据此放大增量窗口——本地绝不能比服务端少。
     */
    private var serverMsgCounts: Map<String, Int> = emptyMap()

    /** 上次回前台批量对齐其它会话的时间（节流 30 秒，别每次切前后台全拉一遍）。 */
    private var lastAlignAt = 0L

    /** -1 未下载；0..100 下载中百分比。 */
    private val _downloadPct = MutableStateFlow(-1)
    val downloadPct = _downloadPct.asStateFlow()

    private val _downloadText = MutableStateFlow("")
    val downloadText = _downloadText.asStateFlow()

    /** 待发送图片（选好未发送）。 */
    private val _pendingImages = MutableStateFlow<List<PendingImage>>(emptyList())
    val pendingImages = _pendingImages.asStateFlow()

    // ---------- 引用回复 ----------

    /** 当前待引用的一条消息（长按气泡选「引用」后非空，输入栏上方显示引用条）。 */
    private val _quoteTarget = MutableStateFlow<Msg?>(null)
    val quoteTarget = _quoteTarget.asStateFlow()

    /** 长按某条消息选「引用」：记下它，输入栏显示引用条，发送时把片段带上。 */
    fun setQuote(m: Msg) {
        _quoteTarget.value = m
    }

    /** 取消引用。 */
    fun clearQuote() {
        _quoteTarget.value = null
    }

    // ---------- 排队消息编辑回填 ----------

    /** 编辑排队消息时把正文回填到输入框；非空即取走。 */
    private val _queuedEdit = MutableStateFlow<String?>(null)
    val queuedEdit = _queuedEdit.asStateFlow()

    // ---------- 富卡片动作 ----------

    /**
     * 卡片按钮点击分发：
     *   open_url      用浏览器打开 value（不动会话）
     *   send_text     把 value 当一条消息发出去（默认）
     *   slash_command 同 send_text，但原样带上斜杠命令（不走引用拼装）
     */
    fun dispatchCardAction(a: CardAction, ctx: android.content.Context) {
        when (a.action) {
            "open_url" -> {
                if (a.value.isEmpty()) return
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(a.value)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            "slash_command" -> {
                if (a.value.isEmpty()) return
                send(a.value)
            }
            else -> {
                if (a.value.isEmpty()) return
                send(a.value)
            }
        }
    }

    /** 模型是否支持原生图片（/v1/capabilities features.supports_vision）；未知按 true。 */
    private val _supportsVision = MutableStateFlow(true)
    // 注：曾暴露过 `val supportsVision = ...`，无人订阅，已删（内部仍用 _supportsVision）。

    /** 图片上传进度文案（空表示无进行中上传）。 */
    private val _imageNote = MutableStateFlow("")
    val imageNote = _imageNote.asStateFlow()

    /** 诊断上报结果文案（空表示本次未操作过）。 */
    private val _diagNote = MutableStateFlow("")
    val diagNote = _diagNote.asStateFlow()

    /**
     * 工具调用的开始时刻（key = 会话 id + "|" + tool_call_id）。
     *
     * 服务端 `/v1/runs` 走的是 canonical turn 那条链路（`observe_api_turn` 的
     * `_tool_complete`），它给 `tool.completed` **不带 `duration`**——只有
     * `_make_run_event_callback` 那条链才带。于是过程行一律显示 0.0s。
     * 这里按 started→completed 的墙钟差补算；流重放时 started 可能没收到，
     * 那时宁可不显示耗时，也不写 0.0s。
     */
    private val toolStartedAt = java.util.concurrent.ConcurrentHashMap<String, ToolStart>()

    // ---------- 会话内搜索（只搜当前会话的本地消息） ----------

    /** 搜索栏是否展开。 */
    private val _searchActive = MutableStateFlow(false)
    val searchActive = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    /** 命中的消息 id，按会话顺序。 */
    private val _searchIds = MutableStateFlow<List<Long>>(emptyList())
    val searchIds = _searchIds.asStateFlow()

    /** 当前跳到第几条（0 基）；无命中为 -1。 */
    private val _searchIdx = MutableStateFlow(-1)
    val searchIdx = _searchIdx.asStateFlow()

    private var searchJob: Job? = null

    /**
     * 「滚到某条消息」的一次性请求（待处理卡片置顶提示点「查看」用）。
     *
     * 为什么不复用搜索跳转：那条路径要 searchActive=true，会顺手弹出搜索栏。
     * 这里只要滚动，界面消费完立刻清掉，避免后续重组再滚一次。
     */
    private val _scrollToMsg = MutableStateFlow<Long?>(null)
    val scrollToMsg = _scrollToMsg.asStateFlow()

    fun requestScrollToMsg(msgId: Long) { if (msgId > 0) _scrollToMsg.value = msgId }
    fun consumeScrollToMsg() { _scrollToMsg.value = null }

    /**
     * 「切到某一页」的请求（-1 = 无）。
     *
     * 定时任务产出的通知点击后要落在「定时任务」页(tab 3)。走 ViewModel 信号而不是
     * 把 tab 存在 Composable 局部 state 里：MainActivity 在 onResume/onNewIntent 从
     * Intent extra 读到目标页后，经这里通知界面切页。
     */
    private val _openTabReq = MutableStateFlow(-1)
    val openTabReq = _openTabReq.asStateFlow()
    fun requestOpenTab(tab: Int) { if (tab >= 0) _openTabReq.value = tab }
    fun consumeOpenTab() { _openTabReq.value = -1 }

    /**
     * 「滚到最新一条」的显式请求。用户自己发消息时必须无条件贴底——哪怕他此前
     * 往上翻过历史（列表还能继续往下滚）。
     *
     * 为什么单独开一条通道：消息列表那个自动贴底的 LaunchedEffect 带
     * `!canScrollForward` 守卫，本意是「流式输出期间别把翻历史的用户拽回底部」，
     * 但它把「用户主动发送」也一起拦了——往上翻过之后再发消息，列表不跳（用户
     * 2026-10-08 报障）。自发消息是明确的「我要看最新」意图，不能受该守卫影响。
     * 用单调自增的 tick 而非布尔：连发两条也能各触发一次。
     */
    private val _scrollBottomTick = MutableStateFlow(0)
    val scrollBottomTick = _scrollBottomTick.asStateFlow()
    fun requestScrollBottom() { _scrollBottomTick.value = _scrollBottomTick.value + 1 }

    /** 打开/收起搜索栏。收起时清掉全部搜索态。 */
    fun toggleSearch() {
        if (_searchActive.value) clearSearch() else {
            _searchActive.value = true
            _searchQuery.value = ""
            _searchIds.value = emptyList()
            _searchIdx.value = -1
        }
    }

    /**
     * 输入即搜：去抖 150 毫秒（快速连打只算一次扫描），匹配放后台线程算，不卡界面。
     * 只搜当前会话的本地消息（最多 300 条）。
     */
    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        val sid = _currentId.value
        if (q.isBlank()) {
            _searchIds.value = emptyList()
            _searchIdx.value = -1
            return
        }
        searchJob = identityScope.launch {
            delay(150)
            val msgs = rt(sid).messages.value
            val ids = withContext(Dispatchers.Default) { ChatSearch.matchIds(msgs, q) }
            // 结果回来时用户可能已改词或切走会话：过期的结果不落地。
            if (_currentId.value != sid || _searchQuery.value != q) return@launch
            _searchIds.value = ids
            _searchIdx.value = if (ids.isEmpty()) -1 else 0
        }
    }

    /** 上下跳转：dir 为 +1 / -1，到头绕回。 */
    fun searchNavigate(dir: Int) {
        val n = _searchIds.value.size
        if (n == 0) return
        val cur = _searchIdx.value
        _searchIdx.value = (((cur + dir) % n) + n) % n
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchActive.value = false
        _searchQuery.value = ""
        _searchIds.value = emptyList()
        _searchIdx.value = -1
    }

    // ---------- 跨会话搜索（扫本地全部会话） ----------

    /** 全局搜索面板是否展开（抽屉里）。 */
    private val _globalActive = MutableStateFlow(false)
    val globalActive = _globalActive.asStateFlow()

    private val _globalQuery = MutableStateFlow("")
    val globalQuery = _globalQuery.asStateFlow()

    private val _globalHits = MutableStateFlow<List<GlobalHit>>(emptyList())
    val globalHits = _globalHits.asStateFlow()

    private var globalJob: Job? = null

    // ── 归档浏览功能已在 v2.151 移除（用户：「更早 N 条已归档 · 查看」，没啥用）──
    // 原先这里有一套 _archiveActive/_archiveCount/_archiveMsgs/_archiveQuery +
    // openArchive/closeArchive/setArchiveQuery/exportArchive，配合 SessionStore 的
    // appendArchive/loadArchive；入口 UI 与数据写入一并删除。本地只保留最近 maxHistory
    // 条，服务端存全量历史，故无需本机归档。

    fun toggleGlobalSearch() {
        if (_globalActive.value) clearGlobalSearch() else {
            _globalActive.value = true
            _globalQuery.value = ""
            _globalHits.value = emptyList()
        }
    }

    fun clearGlobalSearch() {
        globalJob?.cancel()
        _globalActive.value = false
        _globalQuery.value = ""
        _globalHits.value = emptyList()
    }

    /** 输入即搜（去抖 200 毫秒），扫本地全部会话文件，放后台线程算。 */
    fun setGlobalQuery(q: String) {
        _globalQuery.value = q
        globalJob?.cancel()
        if (q.isBlank()) {
            _globalHits.value = emptyList()
            return
        }
        globalJob = identityScope.launch {
            delay(200)
            val hits = withContext(Dispatchers.IO) { store.searchAll(q) }
            if (_globalQuery.value != q) return@launch
            _globalHits.value = hits
        }
    }

    /**
     * 点一条跨会话命中：切到那个会话，并让本会话内的搜索栏定位到这条消息。
     * 命中靠正文认（本地消息 id 不落盘），取该会话里第一条正文一致的消息。
     */
    fun openGlobalHit(hit: GlobalHit) {
        clearGlobalSearch()
        if (hit.sessionId != _currentId.value) switchSession(hit.sessionId)
        ensureLoaded(hit.sessionId)
        identityScope.launch {
            delay(80)   // 等会话消息装载完
            val msgs = rt(hit.sessionId).messages.value
            val idx = msgs.indexOfFirst { it.text == hit.text }
            if (idx < 0) return@launch
            _searchActive.value = true
            _searchQuery.value = hit.text
            _searchIds.value = listOf(msgs[idx].id)
            _searchIdx.value = 0
        }
    }

    private var api: HermesApi? = null
    /**
     * 探测循环只允许起一个。
     *
     * 原来用普通 Boolean 做防重入，但 onProfileChanged 可能被并发调用两次
     * （登录回调 + MainScaffold 的 LaunchedEffect），两个线程都读到 false，
     * 各自把自己当成第一个 → 起了多个 5 秒轮询循环。症状：同一条「恢复在线」
     * 一次打好几遍、离线判定挤在同一毫秒、探测请求成倍（白耗流量与电）。
     * compareAndSet 是原子操作，只有一个线程能赢。
     */
    private val pingStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 正在恢复探测的会话 id：进前台会频繁调用 resumeActiveRun，防止同一会话叠起多条探测链。 */
    private val resumingSids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 本机版本号：静默检查更新时用来比较（免去每次从界面传进来）。 */
    private val myVersionCode: Int = runCatching {
        val app = getApplication<Application>()
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
    }.getOrDefault(1)

    init {
        // 通知栏直接回复：先取落盘的（App 被杀过），再收运行中的广播
        drainPendingReply()
        identityScope.launch {
            com.hermesapp.PendingReply.flow.collect { drainPendingReply() }
        }
    }

    /** 取出通知栏回复并作为用户消息发出（连接没建好或该会话正忙时留着，下次再取）。 */
    private fun drainPendingReply() {
        val raw = prefs.pendingReply
        if (raw.isEmpty()) return
        // 已退出登录就不再替任何身份发送（R10：「退出即撤销发送能力」那一半）。
        if (!prefs.loggedIn) {
            AppLog.log("ui", "已退出登录，暂存回复不发送")
            return
        }
        // 身份闸门（F18）：这条回复是哪个身份下打的？对不上就丢掉，绝不当成当前身份的消息发出去。
        val stored = prefs.pendingReplyProfile
        if (!pendingReplyAllowed(stored, prefs.profile)) {
            AppLog.log("notify", "丢弃旧身份的通知栏回复（写在 " + stored + "，当前 " + prefs.profile + "）")
            prefs.pendingReply = ""
            prefs.pendingReplyProfile = ""
            return
        }
        val sid = raw.substringBefore('\u0000')
        val text = raw.substringAfter('\u0000')
        if (text.isBlank()) { prefs.pendingReply = ""; prefs.pendingReplyProfile = ""; return }
        if (api == null) return
        val target = if (sid.isNotEmpty()) sid else _currentId.value
        if (target.isEmpty()) return
        if (rt(target).busy.value) return
        prefs.pendingReply = ""
        prefs.pendingReplyProfile = ""
        if (target != _currentId.value) switchSession(target)
        send(text)
    }

    // ---------- 会话与本地持久化 ----------

    private val maxHistory = 300

    private fun stamp(): Long = System.currentTimeMillis()

    /** 新会话的排序键：取现有最小 order − 1，保证新会话永远排第一。 */
    private fun nextTopOrder(): Long {
        val min = _sessions.value.minOfOrNull { it.order } ?: 1L
        return min - 1L
    }

    /** 本轮计时起点：优先会话级的（整轮唯一、不会被气泡重建冲掉），没有才取当下。 */
    private fun turnStart(r: SessionRuntime): Long = if (r.startedAt > 0) r.startedAt else stamp()

    /** 本地最后一条助手气泡记下的计时起点（没有则 0）。 */
    private fun localBubbleStart(r: SessionRuntime): Long =
        r.messages.value.lastOrNull { it.role == "assistant" }?.startedAt ?: 0L

    /**
     * 恢复/接管一条**已在跑**的轮次时，把「本轮计时起点」补回来（2026-10-10）。
     *
     * 为什么必须补：起点的两个本地来源都会丢 —— 进行中的空气泡会被落盘的空壳过滤丢掉、
     * 运行时的 startedAt 只在内存（进程被回收即 0）。旧代码此时直接拿「现在」当起点，
     * 表现就是「切一下后台、耗时从 0 重算」（真机日志实测：App 一天被系统回收 8 次）。
     * 服务端 run 记录一直带 created_at，而且探活时本来就拿到了这个 payload，这里把它接上
     * （起点选择规则见 TurnAnchor，纯函数 + 单测）。
     */
    private fun restoreTurnStart(r: SessionRuntime, payload: org.json.JSONObject?, tag: String) {
        val bubble = localBubbleStart(r)
        val before = r.startedAt
        val now = System.currentTimeMillis()
        r.startedAt = TurnAnchor.resolveTurnStartMs(bubble, before, payload?.opt("created_at"), now)
        AppLog.log("resume", tag + " 耗时锚点=" + TurnAnchor.source(bubble, before, payload?.opt("created_at")) +
            " 已跑=" + ((now - r.startedAt) / 1000) + "秒")
    }

    private fun setMsgs(r: SessionRuntime, list: List<Msg>) {
        r.messages.value = capHistory(list)
        scheduleSave(r)
    }

    /**
     * 内存列表也按 maxHistory 截断（保留最新 maxHistory 条）。
     *
     * 为什么必须做：`saveMessages` 只截**落盘副本**，内存列表一直不截。于是把服务端记录
     * 并进来后（mergeByUserAnchor 会把服务端有、本地没有的行补进来）内存条数会一路超过
     * maxHistory 且**不回退**，每次去抖落盘都重算一遍裁剪、打一行「超上限裁剪」日志
     * （用户 2026-10-09 上报里 46 分钟刷了 87 次，本地到 334 条而文件只有 300 条：看到
     * 的和存下的不一致，长会话内存还会持续增长）。单点截断在这里，所有批量/合并写入
     * （setMsgs）与流式追加都过它，内存与落盘窗口一致。
     */
    private fun capHistory(list: List<Msg>): List<Msg> =
        if (list.size > maxHistory) list.takeLast(maxHistory) else list

    /** 索引落盘的异步去抖任务：切会话/改标题连点也只写一次。 */
    private var indexSaveJob: Job? = null

    private fun scheduleSave(r: SessionRuntime) {
        if (r.dead) return          // 已删除的会话：别再安排写盘，否则文件会复活
        r.saveJob?.cancel()
        r.saveJob = identityScope.launch(Dispatchers.IO) {
            delay(400)
            saveRuntime(r)
        }
    }

    private suspend fun saveRuntime(r: SessionRuntime) {
        // 已删除的会话：一个字都不许再写盘，否则删掉的消息文件会复活。
        // deleteSession 会 cancel 掉去抖任务，但若 400ms 已过、协程已进到这里，cancel 就无效了。
        if (r.dead) return
        // 检查点必须在**主线程**上取，并且要把攒帧缓冲里还没显示出来的增量先并入消息模型（F12）。
        // 为什么：事件处理在主线程、落盘在 IO 线程，两者之间隔着线程边界——原来「先读 lastSeq
        // 再读 messages」只是把跨线程窗口缩小，并不能消掉它；更根本的是流式正文先进攒帧缓冲、
        // 再由 16ms 的放帧循环写进消息模型，所以「序号已推进、正文还没落地」是常态。
        // 那一刻若被杀进程/退后台，重启会按 Last-Event-ID 从该序号之后续接，缓冲里那一小段
        // （用户看到的就是「最后一段答案没了」）永远补不回来。
        // 现在：主线程就是事件处理线程，「放帧 → 取序号 → 取正文」在这里是一个不可分割的检查点。
        val cp = withContext(Dispatchers.Main.immediate) {
            r.coalescer?.flushNow()   // 把已收到、还没显示的增量立刻并入消息模型
            SaveCheckpoint(r.sendGen, r.runId, r.lastSeq, r.messages.value)
        }
        val ok = store.saveMessages(r.id, cp.msgs, maxHistory)
        // 只有消息确实落盘成功、**且这一轮的轮次号与 runId 没变过**，才推进续接序号：
        // 写盘失败仍推进的话，重开 App 会按这个序号做 Last-Event-ID、把没保存的那段永远跳过；
        // 而写盘期间用户停/换轮的话，这份检查点属于旧轮，把它的 seq 写进当前会话会让新轮
        // 续接时跳过事件（R03，探针实测 persistedSeq={s:100} 落在 run-B 上）。
        if (ok && cp.runId.isNotEmpty() && r.sendGen == cp.gen && r.runId == cp.runId) {
            prefs.putLastSeq(r.id, cp.seq)
        } else if (ok && cp.runId.isNotEmpty()) {
            AppLog.log("store", "检查点已过期，不提交序号 sid=" + r.id.take(8) +
                " 本轮run=" + cp.runId.take(12) + " 现存run=" + r.runId.take(12) +
                " gen=" + cp.gen + "/" + r.sendGen)
        }
    }

    /**
     * 读会话正文（异步）。
     *
     * 为什么必须异步：正文最多 300 条含工具轨迹，readText + 全量 JSON 解析在主线程上就是
     * 一次几十到几百毫秒的卡顿；重启后每个会话都要现读一次，最明显。
     *
     * 数据安全：读盘期间若有推送/续接写进了 messages（非空），说明内存里那份更新，
     * 这时按用户消息锚点合并，绝不整份覆盖。
     */
    private fun ensureLoaded(id: String) {
        val r = rt(id)
        if (r.loaded || r.loading || r.dead) return
        r.loading = true
        val t0 = System.currentTimeMillis()
        identityScope.launch(Dispatchers.IO) {
            val msgs = sanitizeLoadedMessages(runCatching { store.loadMessages(id) }.getOrDefault(emptyList()))
            val cost = System.currentTimeMillis() - t0
            withContext(Dispatchers.Main.immediate) {
                if (r.dead) { r.loading = false; return@withContext }
                r.messages.value = capHistory(withTurnMeta(r, if (r.messages.value.isEmpty()) msgs
                                   else mergeByUserAnchor(r.messages.value, msgs)))
                r.loaded = true
                r.loading = false
                // 落盘里可能还有排队没发出去的消息（R05）：重开 App 接着发，别让它卡在「排队中」。
                restoreQueue(id)
                // 落盘里还有「运行中」的子任务（长任务跑一半重开 App）：把进度轮询接回去，
                // 否则界面上那行会一直停在重开前的步数。只对当前会话接线（预热其它会话时
                // 不该替用户去轮询）。
                if (id == _currentId.value &&
                    r.messages.value.any { it.subagents.any { s -> s.status == "running" && s.childSessionId.isNotEmpty() && s.endedAt == 0L } }) {
                    ensureSubagentSweep(id)
                }
                val total = if (r.switchStartedAt > 0) System.currentTimeMillis() - r.switchStartedAt else -1L
                AppLog.log("perf", "读会话正文 " + id.take(8) + " 条数=" + msgs.size + " 读盘=" + cost +
                    "ms" + (if (total >= 0) " 点击到就绪=" + total + "ms" else ""))
            }
        }
    }

    /**
     * 恢复落盘的待发队列（R05）。
     *
     * 只在「该会话当前没有队列」时恢复，并且**逐条按回执状态复核**：只有回执还停在
     * 「排队中」的才恢复——落盘的队列文件可能是旧的，回执才是真相。双保险之外还有
     * 幂等键兜底：即便漏判重发一次，服务端也只会执行一遍。
     */
    private fun restoreQueue(id: String) {
        val r = rt(id)
        if (r.queue.isNotEmpty()) return
        r.queuePaused.value = store.loadQueuePaused(id)
        val saved = runCatching { store.loadQueue(id) }.getOrDefault(emptyList())
        if (saved.isEmpty()) return
        val queued = saved.filter { item ->
            r.messages.value.firstOrNull { it.id == item.msgId }?.receipt?.status == Receipt.QUEUED
        }
        if (queued.isEmpty()) {
            runCatching { store.saveQueue(id, emptyList()) }
            return
        }
        r.queue.addAll(queued)
        r.queued.value = r.queue.size
        AppLog.log("queue", "恢复待发队列 sid=" + id.take(8) + " 条数=" + queued.size)
        // 只对当前会话自动接着发：预热其它会话时不该替用户往外发东西。
        if (id == _currentId.value && !r.busy.value) drainQueue(id)
    }

    /**
     * 启动后预热：后台把最近几个会话的正文读进内存。
     *
     * 为什么需要：重启后除当前会话外一条都没加载，用户切到哪个都要现读现解析。
     * 放在启动 1.2 秒后、IO 线程上，不跟启动期的同步标题/收件箱/更新检查抢，也不挡首帧。
     */
    private fun prewarmRecentSessions() {
        identityScope.launch(Dispatchers.IO) {
            delay(1200L)
            val ids = _sessions.value.filter { !it.archived }.take(3).map { it.id }
            for (id in ids) ensureLoaded(id)   // 内部自带 loaded/loading 守卫与锚点合并
        }
    }

    

    private val _historyRestore = MutableStateFlow(HistoryRestoreState())
    val historyRestore = _historyRestore.asStateFlow()
    private var historyRestoreRequest = 0L
    private var historyRestoreValid: (() -> Boolean)? = null

    private fun historyIdentityActive(): Boolean = !detached && prefs.loggedIn &&
        Prefs(getApplication<Application>()).identity == identity

    /** Explicit user action only: inspecting remote rows never changes the local index. */
    fun openHistoryRestore() {
        if (!historyIdentityActive()) return
        val a = api ?: return
        val targetStore = store
        val request = ++historyRestoreRequest
        val valid = { request == historyRestoreRequest && api === a && store === targetStore && historyIdentityActive() }
        historyRestoreValid = valid
        _historyRestore.value = HistoryRestoreState(visible = true, loading = true)
        identityScope.launch(Dispatchers.IO) {
            try {
                val response = a.listSessions(limit = 200)
                val rows = response.optJSONArray("data") ?: error("服务端未返回会话列表")
                val remote = (0 until rows.length()).mapNotNull { i ->
                    val row = rows.optJSONObject(i) ?: return@mapNotNull null
                    val id = row.optString("id", "").trim()
                    if (id.isEmpty() || row.optBoolean("is_internal_child", false)) return@mapNotNull null
                    SessionMeta(id, row.optString("title", "").trim().ifEmpty { "未命名对话" },
                        (row.optDouble("last_active", 0.0) * 1000).toLong())
                }
                withContext(Dispatchers.Main) {
                    if (!valid()) return@withContext
                    _historyRestore.value = HistoryRestoreState(visible = true,
                        candidates = HistoryRestore.candidates(remote, _sessions.value))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!valid()) return@withContext
                    _historyRestore.value = HistoryRestoreState(visible = true, error = "读取失败，请重试")
                }
                AppLog.err("history-restore", "读取可恢复会话失败", e)
            }
        }
    }

    fun dismissHistoryRestore() {
        historyRestoreRequest++
        historyRestoreValid = null
        _historyRestore.value = HistoryRestoreState()
    }

    fun restoreSelectedHistory(selected: Set<String>) {
        if (historyRestoreValid?.invoke() != true) {
            dismissHistoryRestore()
            return
        }
        val state = _historyRestore.value
        if (!state.visible || state.loading || state.error.isNotEmpty() || !indexReady || !prefs.loggedIn) return
        val merged = HistoryRestore.merge(_sessions.value, state.candidates, selected)
        if (merged.size != _sessions.value.size) {
            _sessions.value = merged
            saveIndexAsync(debounceMs = 0L)
        }
        dismissHistoryRestore()
        // Messages are fetched only when the user opens a restored row, via switchSession.
    }

    /** 上次从服务端同步标题的时间：节流用，避免每个轮末都打一次接口。 */
    private var lastTitleSyncAt = 0L

    /**
     * 只把服务端生成的好标题同步回本地已有行，**不再往列表里增行、也不清理**。
     *
     * 2026-10-07：用户明确要求「历史对话不要再拉取」。此前有自动补行（列表为空时补最近
     * 7 天）与手动「拉取」两条路径，会把服务端几十上百条会话灌进手机列表，实测被反馈
     * 「历史对话太多了」。现两条路径全部下线，本函数只剩一个职责：给本地已有的会话行
     * 覆盖服务端小模型生成的正式标题（否则标题永远停在「新对话」或首句截断）。
     */
    fun syncFromServer(throttleMs: Long = 0L) {
        val a = api ?: return
        val now = System.currentTimeMillis()
        if (throttleMs > 0 && now - lastTitleSyncAt < throttleMs) return
        lastTitleSyncAt = now
        identityScope.launch(Dispatchers.IO) {
            try {
                val resp = a.listSessions()
                val arr = resp.optJSONArray("data") ?: return@launch
                val list = _sessions.value.toMutableList()
                var changed = false
                // 顺手把服务端的「正在跑」与 last_active 收下来：前者决定侧边栏角标与
                // 接流，后者决定回前台该对齐哪几条会话。
                val runs = LinkedHashMap<String, String>()
                val lastActive = HashMap<String, Long>()
                // message_count（含工具行）：算本地落后多少行、增量窗口开多大（见 refreshFromServerFor）。
                val msgCounts = HashMap<String, Int>()
                // 服务端有哪些会话行 —— 待补推的改名要等这一行出现才能推上去。
                val serverIds = HashSet<String>()
                // 本地刚改、还没推上去的标题：别让服务端那个（旧的/自动生成的）标题把用户
                // 刚改的名盖回去，否则用户看到「改名过一会自己变回去」。
                val pending = prefs.pendingTitles()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty() || o.optBoolean("is_internal_child", false)) continue
                    serverIds.add(id)
                    val ar = o.optString("active_run", "")
                    if (ar.isNotEmpty() && ar != "null") runs[id] = ar
                    val la = o.optDouble("last_active", 0.0)
                    if (la > 0) lastActive[id] = (la * 1000).toLong()
                    val mc = o.optInt("message_count", -1)
                    if (mc >= 0) msgCounts[id] = mc
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx < 0) continue      // 本地没有这条：一律不补行
                    if (pending.containsKey(id)) continue   // 待补推：跳过标题覆盖
                    val title = o.optString("title", "").trim()
                    if (title.isNotEmpty() && list[idx].title != title) {
                        list[idx] = list[idx].copy(title = title); changed = true
                    }
                }
                serverLastActive = lastActive
                serverMsgCounts = msgCounts
                if (_serverRuns.value != runs) _serverRuns.value = runs
                AppLog.log("sync", "同步标题：服务端=" + arr.length() + " 条，本地=" + list.size +
                    " 条，覆盖=" + changed + " 服务端在跑=" + runs.size)
                if (changed) {
                    _sessions.value = list
                    store.saveIndex(list)
                }
                flushPendingTitles(a, serverIds)
                // 服务端说当前会话在跑而本地没有 → 别的端发起的（或本地标记丢了），接上去。
                reconcileServerRuns()
                refreshRunFlags()
            } catch (e: Exception) {
                AppLog.err("sync", "同步会话标题失败", e)
                // 离线 / 接口异常：保留本地列表，不影响使用
            }
        }
    }

    /**
     * 把「本地已改、服务端还没推上去」的标题补推（在 syncFromServer 的 IO 线程里顺序执行）。
     *
     * 只推服务端已经有行的那些（[serverIds] 是这次列表里真实存在的会话）——没行的推上去也是
     * 404，留着等它出现。推成功后清掉待办；失败保留，下次同步再试（不静默丢改名）。
     */
    private fun flushPendingTitles(a: HermesApi, serverIds: Set<String>) {
        val pend = prefs.pendingTitles()
        if (pend.isEmpty()) return
        for ((sid, t) in pend) {
            if (sid !in serverIds) continue
            if (t.isEmpty()) { prefs.removePendingTitle(sid); continue }
            val ok = runCatching { a.renameSession(sid, t) }.isSuccess
            AppLog.log("sync", "补推改名 sid=" + sid.take(8) + " 成功=" + ok + " 标题=" + t.take(16))
            if (ok) prefs.removePendingTitle(sid)
        }
    }

    /**
     * 一次性清理历史遗留的空壳会话行（只跑一次，靠 prefs.shellCleanupDone 记）。
     *
     * 背景：上一版的「拉取」把服务端几十条会话灌进了本地索引，用户从没在这些会话里
     * 聊过、本地也没有聊天记录文件。判据 =「本地无聊天记录文件 且 标题不是『新对话』」：
     * - 本地新建、还没发过消息的会话标题就是「新对话」，保留；
     * - 发过消息的会话本地必有记录文件，保留；
     * - 剩下「无记录 + 非新对话」的，只可能是外部灌进来的空壳，移除。
     * 只动本地索引，不删任何消息文件；服务端数据原样保留，误判也不会丢数据。
     */
    private fun cleanupShellSessionsOnce() {
        if (prefs.shellCleanupDone) {
            AppLog.log("session-sync", "空壳清理：本机已执行过，跳过")
            return
        }
        try {
            val list = _sessions.value
            val kept = list.filter { meta ->
                store.hasMessages(meta.id) || meta.title == "新对话"
            }
            val removed = list.size - kept.size
            AppLog.log("session-sync", "空壳清理：清理前=" + list.size +
                " 保留=" + kept.size + " 移除=" + removed)
            if (removed > 0) {
                _sessions.value = kept
                // 清到一条不剩也是合法的「全部删掉」，显式允许写空。
                saveIndexAsync(debounceMs = 0L, allowEmpty = kept.isEmpty())
                if (kept.none { it.id == _currentId.value }) selectNextOrEmpty()
            }
        } catch (e: Exception) {
            AppLog.err("session-sync", "空壳清理失败", e)
        }
        prefs.shellCleanupDone = true
    }
    /** 切到某个会话（网关 session_id 同步指过去）。不再停止任何正在跑的任务。 */
    fun switchSession(id: String) {
        if (id == _currentId.value) return
        val t0 = System.currentTimeMillis()
        AppLog.log("ui", "切会话 -> " + id.take(8))
        clearSearch()   // 搜索只作用于当前会话：切走即收起
        // 切会话必须立刻返回。原来这里在 UI 线程上同步做「写上一会话正文 + 写索引 +
        // 读新会话正文并全量解析 + 再读一次索引」：一次点击 3 写 2 读，重启后没有内存
        // 缓存，每次都等磁盘 —— 这就是侧边栏切换卡顿的来源。现在写盘异步、读盘走 IO，
        // 主线程只改状态（下面那行 perf 日志会把实际耗时记下来）。
        val r = rt(id)
        r.switchStartedAt = t0
        saveCurrentAsync()
        _currentId.value = id
        prefs.sessionId = id
        ensureLoaded(id)
        // 切到哪就与服务端对齐哪。以前这里只从本地磁盘读正文，一条服务端请求都不发，
        // 于是别的端（微信 / CLI / 桌面）在会话 X 里跑过的新轮次，切过去看到的是本地旧
        // 内容，永远对不上（除非重启 App）。已在跑的会话 refreshFromServerFor 内部会
        // 自己跳过（busy），不会把半截内容合进来。
        refreshFromServerFor(id)
        AppLog.log("perf", "切会话 " + id.take(8) + " 主线程耗时=" + (System.currentTimeMillis() - t0) + "ms")
    }

    /**
     * 落盘当前会话的正文与索引（全异步）。
     *
     * 顺序保证：每个会话自己有 saveJob（400ms 去抖）。这里先 cancel 掉待执行的去抖任务，
     * 再挂一个立刻执行的 IO 写 —— 同一会话任何时刻只有一个写在跑，不会互相覆盖。
     */
    private fun saveCurrentAsync() {
        val id = _currentId.value
        if (id.isNotEmpty()) {
            val r = runtimes[id]
            if (r != null && !r.dead) {
                r.saveJob?.cancel()
                r.saveJob = identityScope.launch(Dispatchers.IO) { saveRuntime(r) }
            }
        }
        saveIndexAsync()
    }

    /**
     * 索引落盘：异步 +（默认）300ms 去抖。
     * debounceMs = 0 用于「必须尽快落盘」的场合（新建会话、归档、删除）。
     */
    private fun saveIndexAsync(debounceMs: Long = 300L, allowEmpty: Boolean = false) {
        // 索引还没读完之前一律不落盘（见 indexReady 注释）：此刻内存里的列表是空的，
        // 写下去就是把磁盘上那份索引抹成空数组 —— 2026-10-14 事故的根因路径。
        if (!indexReady) {
            AppLog.log("store", "索引未就绪，跳过落盘（防启动竞态写空）条数=" + _sessions.value.size +
                " allowEmpty=" + allowEmpty)
            return
        }
        indexSaveJob?.cancel()
        indexSaveJob = identityScope.launch(Dispatchers.IO) {
            if (debounceMs > 0) delay(debounceMs)
            // 去抖窗口内列表可能又变过，落盘前再判一次（与 SessionStore 内的闸门同源）。
            if (!indexWriteAllowed(indexReady, _sessions.value.size, store.lastKnownCount, allowEmpty)) {
                AppLog.err("store", "跳过落盘：磁盘上还有 " + store.lastKnownCount + " 条、本次 " +
                    _sessions.value.size + " 条（allowEmpty=" + allowEmpty + "）", null)
                return@launch
            }
            store.saveIndex(_sessions.value, allowEmpty)
        }
    }

    /** 把待写的正文/索引立刻落盘（App 退到后台时调，避免进程被杀丢最后一段）。 */
    fun flushSaves() {
        saveCurrentAsync()
        AppLog.flush()
    }

    fun newConversation() {
        AppLog.log("ui", "新建会话")
        saveCurrentAsync()
        val id = UUID.randomUUID().toString()
        // 新会话永远排第一：手动模式给它最小 order − 1；非手动模式留 0（重启按更新时间排也在最前）。
        val nOrder = if (_sessions.value.any { it.order > 0L }) nextTopOrder() else 0L
        val meta = SessionMeta(id, "新对话", stamp(), false, nOrder)
        _sessions.value = listOf(meta) + _sessions.value
        saveIndexAsync(debounceMs = 0L)
        _currentId.value = id
        prefs.sessionId = id
        rt(id).loaded = true
        rt(id).loading = false
    }

    /**
     * 手动调整会话顺序：dir = -1 上移，+1 下移。
     * 列表按「归档态」过滤展示，跨态移动没有视觉意义，所以只在同态相邻项之间换位；
     * 换完把「位置」写回 order，保证落盘 / 重启后顺序不变。
     */
    fun moveSession(id: String, dir: Int) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val arch = list[i].archived
        var j = i + dir
        while (j >= 0 && j < list.size && list[j].archived != arch) j += dir
        if (j < 0 || j >= list.size) return
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        for (k in list.indices) list[k].order = k.toLong()
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
        AppLog.log("ui", "会话排序 sid=" + id.take(8) + " 方向=" + dir + " 新位置=" + j)
    }

    fun archiveSession(id: String, archived: Boolean) {
        AppLog.log("ui", "归档会话 sid=" + id.take(8) + " 归档=" + archived)
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(archived = archived)
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
        if (id == _currentId.value && archived) selectNextOrEmpty()
    }

    /**
     * 重命名会话：本地先改（立刻可见 + 落索引），随后把标题 PATCH 给服务端。
     *
     * 为什么必须推服务端：服务端存的是 title_source=user 的标题，权重最高——推上去之后
     * 小模型的自动标题不会再覆盖它，[syncFromServer] 的「服务端标题覆盖本地」也只会盖回
     * 同一串，改名才真正稳得住。反过来若只改本地不改服务端，下次同步就被服务端旧标题盖
     * 回去，用户看到的是「改名过一会儿自己变回去了」。
     *
     * 推送失败**回滚本地标题**并明确报错（不静默、不留「本地改了服务端没改」的分裂态）；
     * 成功则采用服务端回执里的标题（服务端会去零宽/控制字符并折叠空白，以它为准）。
     */
    fun renameSession(id: String, rawTitle: String) {
        val t = normalizeTitle(rawTitle)
        titleRejectReason(t)?.let { toast(it); return }
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = list[i].title
        if (t == old) return
        list[i] = list[i].copy(title = t)
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
        AppLog.log("ui", "重命名会话 sid=" + id.take(8) + " 旧=" + old.take(16) + " 新=" + t.take(16))
        val a = api
        if (a == null) {
            applyTitle(id, old, t)
            toast("重命名没同步：未连接服务器")
            return
        }
        identityScope.launch(Dispatchers.IO) {
            val res = runCatching { a.renameSession(id, t) }
            withContext(Dispatchers.Main) {
                val e = res.exceptionOrNull()
                when {
                    e == null -> {
                        prefs.removePendingTitle(id)
                        val srv = res.getOrNull()?.optJSONObject("session")?.optString("title", "").orEmpty()
                        if (srv.isNotEmpty()) applyTitle(id, srv, t)
                        AppLog.log("sync", "重命名已同步 sid=" + id.take(8) + " 服务端标题=" + srv.take(16))
                    }
                    // 服务端还没有这个会话的行（新建但没发过消息）→ 不改回本地，记成待补推：
                    // 该会话在服务端建行后由 syncFromServer 自动推上去。
                    e is IOException && e.message.orEmpty().contains("HTTP 404") -> {
                        prefs.putPendingTitle(id, t)
                        toast("已改名；服务器端还没有这个会话，发出第一条消息后会自动同步")
                        AppLog.log("sync", "改名待补推（服务端无此行）sid=" + id.take(8))
                    }
                    else -> {
                        applyTitle(id, old, t)
                        toast("重命名没同步到服务器：" + diagText(e))
                    }
                }
            }
        }
    }

    /**
     * 把某会话的标题从 [expect] 改成 [want]（只在当前值仍等于 [expect] 时才改）。
     * 用于改名的回滚与服务端回执对齐——窗口期内标题若被别处改过（同步/新会话生成），
     * 就不该拿旧值盖掉它。
     */
    private fun applyTitle(id: String, want: String, expect: String) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        if (list[i].title == want) return
        if (list[i].title != expect) return
        list[i] = list[i].copy(title = want)
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
    }

    /** 一次性提示（抽屉里的改名等操作没有顶部提示条可用，用系统 Toast，不静默）。 */
    private fun toast(text: String) {
        runCatching {
            android.widget.Toast.makeText(getApplication(), text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun deleteSession(id: String) {
        AppLog.log("ui", "删除会话 sid=" + id.take(8))
        // 若该会话有正在跑的任务，先停掉（服务端一并停），再删本地记录。
        stopSession(id)
        // 关键顺序：先把该会话的运行态标死、取消待执行的保存任务，再删文件。
        // 反过来的话，刚挂上去的异步保存会在删除之后把文件又写回来（会话复活）。
        runtimes[id]?.let { r -> r.dead = true; r.saveJob?.cancel() }
        runtimes.remove(id)
        prefs.clearDraft(id)
        // 待补推的改名也要一并清掉，否则这条会话删了还留着一条永远推不出去的待办。
        prefs.removePendingTitle(id)
        val list = _sessions.value.filter { it.id != id }.toMutableList()
        _sessions.value = list
        // 删掉最后一个会话时列表就是空的，这是真·清空，显式放行（否则闸门会拒绝写盘，
        // 磁盘上那条已删的会话会在下次启动时被 .bak 恢复回来）。
        saveIndexAsync(debounceMs = 0L, allowEmpty = list.isEmpty())
        identityScope.launch(Dispatchers.IO) { runCatching { store.deleteMessages(id) } }
        if (id == _currentId.value) selectNextOrEmpty()
    }

    /**
     * 一键清空全部对话（侧边栏「历史对话」标题行右侧的「清空」调用，UI 先弹确认）。
     *
     * 语义与逐条 deleteSession 保持一致 —— 「全部」只是把同一条路径一次性跑完：
     *   1) 正在跑的会话先 stopSession（服务端一并停），再把运行态标死、取消挂着的保存任务。
     *      顺序不能反：反了的话删完文件，刚挂上去的异步保存会把文件又写回来（会话复活）。
     *   2) 落盘清空走 store.clearAllLocal()：按前缀扫目录，索引里已丢行的孤儿文件也带走。
     *   3) prefs 里按会话 id 存的运行时状态（草稿/活跃 run/续接序号/待补推标题）整表扫掉，
     *      否则这些「对话内容」还留在手机上，且没人回收。
     *   4) 列表置空、当前会话清空、索引立刻落盘；本地搜索与跨会话搜索的命中都清掉（那些结果
     *      来自刚删的文件，留着会显示「有结果但点不开」）；运行角标立刻重算，不等 2 秒巡检。
     *
     * 服务端那份会话记录不动 —— 与单条删除的口径完全一致，UI 确认弹窗里也如实这么写。
     * syncFromServer 不会把它们补回来：它只更新本地已有的行，不为服务端独有的会话建行。
     */
    fun clearAllConversations() {
        val ids = _sessions.value.map { it.id }
        AppLog.log("ui", "清空全部对话 本地条数=" + ids.size + " 含归档=" + _sessions.value.count { it.archived })
        for (id in ids) {
            stopSession(id)
            runtimes[id]?.let { r -> r.dead = true; r.saveJob?.cancel() }
            runtimes.remove(id)
        }
        val prefsCleared = prefs.clearAllSessionState()
        _sessions.value = emptyList()
        // 「清空全部对话」是唯一该把索引写成空的用户动作之一 —— 显式放行闸门。
        saveIndexAsync(debounceMs = 0L, allowEmpty = true)
        // 走与单条删除同一条落地路径：_sessions 已空，它会清掉当前会话指针并进入空态。
        selectNextOrEmpty()
        prefs.pendingOpenSession = ""
        // 两个搜索的命中都作废：它们指向刚删掉的消息，留着会出现「有结果但点不开」。
        clearSearch()
        clearGlobalSearch()
        // 列表/角标立刻降下来，不等 2 秒一次的巡检。runFlags 由 runtimes 与 _sessions 重算，
        // 两个来源都已清空，所以这一步的结果就是「没有任何会话在跑」。
        refreshRunFlags()
        identityScope.launch(Dispatchers.IO) {
            val n = runCatching { store.clearAllLocal() }.getOrDefault(-1)
            AppLog.log("ui", "清空全部对话完成 prefs键=" + prefsCleared + " 文件=" + n)
        }
    }

    /**
     * 把一个会话导出成 Markdown 文件并调系统分享面板发出去（发同事/存网盘）。
     *
     * 落在 App 私有目录 exports/（FileProvider 已声明），只读本地记录，不碰服务端。
     * 失败只在日志里记一行，不弹错——导出是附加功能，坏了不该打扰正常对话。
     */
    fun exportSession(ctx: Context, id: String) {
        identityScope.launch(Dispatchers.IO) {
            try {
                val meta = _sessions.value.firstOrNull { it.id == id }
                val title = meta?.title ?: "对话"
                val md = store.exportMarkdown(id, title)
                if (md.isBlank()) {
                    AppLog.log("export", "会话为空，跳过导出 id=" + id.take(8))
                    return@launch
                }
                val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
                val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "对话" }
                val f = File(dir, safe + "_" + TimeFmt.mdhm(System.currentTimeMillis())
                    .replace(Regex("[^0-9]"), "") + ".md")
                f.writeText(md)
                AppLog.log("export", "已导出 " + f.name + " " + md.length + " 字")
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
                    val it = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(Intent.createChooser(it, "导出对话").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                AppLog.err("export", "导出失败", e)
            }
        }
    }

    /** 当前会话被删/归档后：优先切到下一个未归档会话；没有则清空进入空态（输入即新建）。 */
    private fun selectNextOrEmpty() {
        val next = _sessions.value.firstOrNull { !it.archived }
        if (next != null) {
            _currentId.value = next.id
            prefs.sessionId = next.id
            ensureLoaded(next.id)
        } else {
            _currentId.value = ""
            prefs.sessionId = null
        }
    }

    private fun touchSession(firstUserText: String?) {
        val id = _currentId.value
        if (id.isEmpty()) return
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = list[i]
        val title = if (old.title == "新对话" && !firstUserText.isNullOrBlank()) {
            // 标题推导统一走 deriveTitle（与「索引为空时按消息文件重建列表」同一口径）。
            deriveTitle(firstUserText)
        } else old.title
        list[i] = old.copy(title = title, updatedAt = stamp())
        _sessions.value = list
        // 时间戳必须落盘：原来只改内存，重启后 updatedAt 停在旧值，回前台对齐会一直判
        // 「服务端更新」，同一批会话每 30 秒被重拉一次。去抖 300ms，连发也只写一次。
        saveIndexAsync()
    }

    private fun bootstrapSessions(profileSessionId: String?) {
        try {
            store = SessionStore(getApplication<Application>(), identity, prefs.legacyServer)
            // 启动体检：把本机到底存了哪些会话文件钉进日志。
            AppLog.log("store", "启动体检 profile=" + prefs.profile + " " + store.diagSummary() +
                " 上次会话=" + (profileSessionId ?: "无"))
            val idx = store.loadIndex()
            AppLog.log("store", "启动读索引 条数=" + idx.size)
            // ① 索引一条都没有 → 按目录里的正文文件整份重建（事故恢复的第一道）。
            var list = idx.toMutableList()
            if (shouldRebuildIndexFromFiles(idx.size, store.messageFileCount())) {
                val rebuilt = store.rebuildFromMessageFiles()
                if (rebuilt.isNotEmpty()) {
                    AppLog.log("store", "启动分支=按消息文件重建 条数=" + rebuilt.size)
                    list = rebuilt
                }
            }
            // ② 索引里缺行、但那些会话的正文文件还在 → 补回列表。
            //    事故现场的形状就是这一条：索引被写空后 App 又建了当前会话，索引里只剩 1 条，
            //    4 条老会话的正文一直躺在目录里（"索引为空才重建"对非空索引不生效）。
            val orphans = store.recoverOrphanSessions(list.map { it.id }.toSet())
            if (orphans.isNotEmpty()) {
                AppLog.log("store", "启动分支=补回索引外的会话 条数=" + orphans.size +
                    " 合计=" + (list.size + orphans.size))
                list = (list + orphans).toMutableList()
            }
            if (list.isEmpty()) {
                val migrated = store.migrateLegacy(profileSessionId ?: "")
                if (migrated == null) {
                    // 无历史会话：进入空态，首次输入再建会话
                    AppLog.log("store", "启动分支=空态（索引为空且无旧格式文件可迁移）")
                    _currentId.value = ""
                    prefs.sessionId = null
                    _sessions.value = emptyList()
                    return
                }
                AppLog.log("store", "启动分支=迁移旧格式 会话=" + migrated.id.take(8))
                _currentId.value = migrated.id
                prefs.sessionId = migrated.id
                _sessions.value = listOf(migrated)
                ensureLoaded(migrated.id)
                return
            }
            // 排序：只要用户手动排过（存在非 0 的 order），就按 order 升序（手动顺序）；
            // 否则回落到按最近更新时间降序——同时把当前顺序钉成 order（1..N），
            // 作为手动排序的起点，免得第一次上/下移时整表乱跳。
            val manual = list.any { it.order > 0L }
            if (manual) {
                // 补回来的会话没有手排位次：排到现有序列末尾，别插进用户自己排好的顺序里。
                var next = list.maxOfOrNull { it.order } ?: 0L
                for (m in list) if (m.order <= 0L) m.order = ++next
                list.sortBy { it.order }
            } else {
                list.sortByDescending { it.updatedAt }
            }
            // 恢复过（重建 / 补回）就立刻把索引写回去：下次启动直读，也把 .bak 顶成这份正确的。
            if (list.size != idx.size) {
                store.saveIndex(list)
                AppLog.log("store", "启动恢复后写回索引 条数=" + list.size)
            }
            // 优先恢复上次停留的会话（prefs.sessionId）；找不到才回退到最近更新的未归档会话。
            val preferred = profileSessionId?.takeIf { it.isNotEmpty() }
                ?.let { pid -> list.firstOrNull { it.id == pid && !it.archived } }
            val target = preferred ?: list.firstOrNull { !it.archived } ?: list.first()
            AppLog.log("store", "启动分支=恢复列表 条数=" + list.size +
                " 已归档=" + list.count { it.archived } +
                " 命中上次会话=" + (preferred != null) + " 当前=" + target.id.take(8))
            _currentId.value = target.id
            prefs.sessionId = target.id
            _sessions.value = list
            AppLog.log("store", "刷新列表 条数=" + list.size + " 已归档=" + list.count { it.archived } +
                " 当前=" + target.id.take(8))
            ensureLoaded(target.id)
            prewarmRecentSessions()
        } catch (e: Exception) {
            // 启动失败也不能把 indexReady 卡在 false —— 否则这一进程里任何索引变更都写不出去。
            AppLog.err("store", "启动读取会话列表失败（按空列表继续）", e)
        } finally {
            // 只有走到这里才说明「磁盘上的索引已经读进内存」，此时才允许落盘。
            indexReady = true
        }
    }

    // ---------- 连接 ----------

    fun onProfileChanged(p: Prefs) {
        if (detached || p.identity != identity) return
        if (api != null) return

        AppLog.log("ui", "切身份 profile=" + p.profile + " 服务器=" + p.serverUrl)
        // 换身份才作废通知栏暂存回复（F18）；**同身份**（冷启动 / 手动重连）必须保留 ——
        // 原来这里无条件清空，于是「进程被杀 → 通知栏回复 → 重开 App」在恢复落地后又被
        // 这一行抹掉，那条回复静默消失（R11，调用顺序可确定复现）。判据与消费端
        // pendingReplyAllowed 同源：来源身份不一致才作废，旧数据(来源为空)放行。
        if (!pendingReplyAllowed(p.pendingReplyProfile, p.profile)) {
            p.pendingReply = ""
            p.pendingReplyProfile = ""
        }
        runCatching { Notifier.clearBusinessNotifications(getApplication()) }
        // Server changes require a fresh identity-bound ViewModel. Never rewrite trust domains here.
        // R20：密钥不再编进包里，改用该身份的登录凭据（"账号:密码"）；未登录则该身份为空。
        val key = prefs.credential(identity.profile)
        val prefix = if (p.profile == "default") "" else "/p/friend"
        api = HermesApi(identity.server, key, prefix)
        // 网关托管媒体：把带鉴权的取文件函数挂给 Markdown 附件卡片
        val a0 = api
        MediaFetch.handler = { token -> a0?.downloadMedia(token) }
        // 语音重播：点旧消息的播放按钮时按 runId 取长期留档的 mp3
        VoiceReplayPlayer.fetcher = { rid -> a0?.downloadVoice(rid) }
        VoiceReplayPlayer.rate = p.voiceRate
        identityScope.launch(Dispatchers.IO) {
            bootstrapSessions(p.sessionId)
            cleanupShellSessionsOnce()
        }
        syncFromServer()
        // 把设置里存的语速灌进播放器（播放器是单例，重启 App 后要重新初始化）
        VoicePlayer.rate = p.voiceRate
        StreamVoicePlayer.rate = p.voiceRate
        pingLoop()
        startCpuLoop()
        startStallWatch()
        refreshStatus()
        refreshFromServer()
        fetchCapabilities()
        resumeActiveRun()
        // 启动就先看一眼定时任务收件箱：App 这个通道收不到推送，产出只能自己来拉。
        refreshInbox()
        // 侧边栏会话状态提示的心跳（子任务/排队/busy 都算）。
        startRunFlagsTicker()
        drainPendingReply()
        checkUpdateSilently()
    }

    /**
     * 老域名静默迁移：把 prefs 里存的历史域名换成当前域名。
     *
     * 为什么必须做：服务器地址是登录时写进 prefs 的，就地升级（不重新登录）
     * 时不会更新。域名一旦迁移，老用户的地址就指向死链，
     * 表现就是「一直重连连不上、怎么都连不上」。
     * 新老域名对照见 local.properties 的 HERMES_LEGACY_HOSTS（构建期注入，源码不留真值）。
     * 这里只做主机名替换，路径/端口/协议原样保留。
     */
    private fun migrateLegacyHost(p: Prefs) {
        val cur = p.serverUrl
        if (cur.isEmpty()) return
        val hit = LEGACY_HOSTS.entries.firstOrNull { cur.contains(it.key) } ?: return
        val fixed = cur.replace(hit.key, hit.value)
        if (fixed != cur) p.serverUrl = fixed
    }

    /**
     * 静默检查更新：只更新「设置」角标，不弹框（启动 / 切身份时调用）。
     * 用户主动点「检查更新」仍走 checkUpdate()，那时才弹确认框。
     */
    fun checkUpdateSilently() {
        val a = api ?: return
        identityScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate() ?: return@launch
                _updateBadge.value = info.versionCode > myVersionCode
                AppLog.log("update", "静默检查：服务端=" + info.versionName + "(" + info.versionCode + ") 本机=" + myVersionCode)
            } catch (e: Exception) {
                AppLog.err("update", "静默检查更新失败", e)
            }
        }
    }

    /**
     * 重开 App 时恢复「正在跑的任务」：本地按会话存了 run_id，逐个查状态——
     * 还在跑的就恢复「停止」按钮并继续接流（多会话可同时恢复），已结束的清标记。
     */
    fun resumeActiveRun() {
        val a = api ?: return
        val map = prefs.activeRunsMap()
        if (map.isEmpty()) return
        for ((sid, rid) in map) {
            // 已在跑的会话不重复探测：重复接流会把同一条 run 挂两条流、进度翻倍。
            if (rt(sid).busy.value) continue
            // 同一会话的探测不许叠（两个前台事件可能并发进来）。
            if (!resumingSids.add(sid)) continue
            identityScope.launch(Dispatchers.IO) {
              try {
                // 开机时网络往往还没就绪，探测失败不能当成「任务已结束」——
                // 那样会把活跃标记删掉，这条任务就永远回不来了。重试几次，
                // 实在探不出来就保留标记，等下次进前台/切身份再试。
                var last: HermesApi.RunStatus = HermesApi.RunStatus.Unknown
                for (i in 1..RESUME_PROBE_TRIES) {
                    last = a.probeRun(rid)
                    if (last !is HermesApi.RunStatus.Unknown) break
                    delay(if (i == 1) 1_000L else 3_000L)
                }
                // 探测是异步的：等待期间用户可能刚发了新消息（busy 已置真、runId 已换新），
                // 或另一条路径已把这条任务接上。此时绝不能按探测到的旧 run 再起一条流——
                // 同一 run 两条流会重复收事件，正文与语音都执行两遍（实测 2.81 回前台重复播报）。
                val rg = rt(sid)
                if (rg.busy.value || (rg.runId.isNotEmpty() && rg.runId != rid)) {
                    AppLog.log("resume", "恢复探测放弃 sid=" + sid.take(8) + " busy=" + rg.busy.value + " runId换=" + (rg.runId.isNotEmpty() && rg.runId != rid))
                    resumingSids.remove(sid)
                    return@launch
                }
                when (last) {
                    is HermesApi.RunStatus.Known -> {
                        AppLog.log("resume", "恢复探测 sid=" + sid.take(8) + " 状态=" + last.status)
                        if (last.status in RUNNING_STATES) {
                            val r = rt(sid)
                            ensureLoaded(sid)
                            r.runId = rid
                            r.finished = false
                            restoreTurnStart(r, last.payload, "恢复探测")
                            // 冷启动恢复：本地没有这轮的发送上下文（pendingSendText 不落盘），
                            // 翻历史兜底拿不到位置锚点，靠 resumed 标记走「保留气泡继续重连」。
                            r.resumed = true
                            // 续接序号从落盘恢复：只补断线之后的事件，避免从 0 全量重放
                            // 把已经存过的工具轨迹再追加一遍（用户报「过程重复显示」）。
                            r.lastSeq = prefs.lastSeq(sid)
                            // 服务端还在等审批/澄清：把那张卡片重新挂回去（续接流只补
                            // lastSeq 之后的事件，早于断点的 clarify.request 不会重放）。
                            restorePendingCard(r, last.payload)
                            // 本地落盘的消息读回来时 pending 一律是 false，而服务端这条 run
                            // 还没结束。若断线前已收到过事件（lastSeq>=0），本地就有这一轮的
                            // 气泡，把它恢复成「进行中」，续接来的增量才追加到同一条上，不会
                            // 再起一条（否则同一轮正文/轨迹会显示两次）；若还没收到过任何事件，
                            // 本地只有上一轮的回复，绝不能标它 pending（新内容会挂到旧回复上），
                            // 这时新起一个空气泡接续。
                            val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
                            val hasLocalTurnBubble = lastAssistant >= 0 &&
                                lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
                            if (hasLocalTurnBubble) {
                                val m = r.messages.value.toMutableList()
                                m[lastAssistant] = m[lastAssistant].copy(
                                    pending = true,
                                    startedAt = if (m[lastAssistant].startedAt > 0) m[lastAssistant].startedAt
                                    else if (r.startedAt > 0) r.startedAt else System.currentTimeMillis()
                                )
                                r.messages.value = m
                            } else {
                                r.messages.value = capHistory(r.messages.value +
                                    Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r)))
                            }
                            r.busy.value = true
                            updateRunService()
                            streamRun(a, sid)
                        } else {
                            // run 已结束：清掉落盘读回的过期待办卡片，再拉记录。
                            ensureLoaded(sid)
                            clearStaleCards(rt(sid))
                            prefs.removeActiveRun(sid)
                            if (sid == _currentId.value) refreshFromServer()
                        }
                    }
                    // 服务端明确说没这个 run：标记失效，清掉并拉一次记录。
                    HermesApi.RunStatus.Missing -> {
                        ensureLoaded(sid)
                        clearStaleCards(rt(sid))
                        prefs.removeActiveRun(sid)
                        if (sid == _currentId.value) refreshFromServer()
                    }
                    // 探不出来（网还没就绪）：不知道 ≠ 已结束。保留标记，并把它登记成
                    // 「进行中」（busy + 待接续气泡），交给看门狗与退避链继续重连——
                    // 只留个标记干等的话，回前台这条路径以前根本不存在，任务就永远不报了。
                    HermesApi.RunStatus.Unknown -> {
                        val r = rt(sid)
                        ensureLoaded(sid)
                        r.runId = rid
                        r.finished = false
                        r.resumed = true
                        r.lastSeq = prefs.lastSeq(sid)
                        val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
                        val hasLocalTurnBubble = lastAssistant >= 0 &&
                            lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
                        if (hasLocalTurnBubble) {
                            val m = r.messages.value.toMutableList()
                            m[lastAssistant] = m[lastAssistant].copy(pending = true)
                            r.messages.value = m
                        } else {
                            r.messages.value = capHistory(r.messages.value +
                                Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r)))
                        }
                        r.busy.value = true
                        restoreTurnStart(r, null, "恢复探测(未知态)")
                        r.lastEventAt = System.currentTimeMillis()
                        updateRunService()
                        maybeContinue(sid)
                        if (sid == _currentId.value) refreshFromServer()
                    }
                }
              } finally {
                // 无论成功/失败/抛异常都释放单飞位：原来只在末尾 remove，
                // probeRun/ensureLoaded 一旦抛异常，该会话永久无法再恢复探测。
                resumingSids.remove(sid)
              }
            }
        }
    }

    /**
     * 服务端说某些会话正在跑，而本地没有对应的活跃标记 —— 接上去。
     *
     * 覆盖两种此前完全看不见的情况：
     *   1) 轮次是别的端（微信 / CLI / 桌面）发起的，本进程从来没发过这条 run；
     *   2) 本进程发起后立刻被杀/断网，activeRunsMap 还没落盘就被系统收走了。
     *
     * 只处理「本地没在跑」的会话：本地已经在跑的由它自己的流负责，重复接流会把
     * 同一条 run 挂两条流、事件与语音都收两遍（实测过的 2.81 重复播报）。
     */
    private fun reconcileServerRuns() {
        val a = api ?: return
        val runs = _serverRuns.value
        if (runs.isEmpty()) return
        // 只处理本地已有的会话行：与「历史对话不要再拉取」一致，绝不为服务端独有的会话
        // 建本地行（那会把几十条别的端的会话灌进手机列表）。
        val known = _sessions.value.filter { !it.archived }.map { it.id }.toSet()
        AppLog.log("resume", "接管检查 服务端在跑=" + runs.size + " 本地会话=" + known.size)
        for ((sid, rid) in runs) {
            if (rid.isEmpty() || sid !in known) {
                AppLog.log("resume", "接管跳过 sid=" + sid.take(8) + " 不在本地列表")
                continue
            }
            val r = rt(sid)
            // 本地就在跑这一轮：不动（自己的流负责，重复接流会收两遍事件）。
            if (r.busy.value && r.runId == rid) continue
            // 本地在跑「别的」轮次：一个会话同时只该有一条 run，先信本地，但把分歧记下来
            // （2026-10-08：这里原来一句静默 continue，日志里只剩「服务端在跑=N」却看不到
            //  为什么没接管，排查时无从下手）。
            if (r.busy.value) {
                AppLog.log("resume", "接管跳过 sid=" + sid.take(8) + " 本地run=" + r.runId.take(12) +
                    " 服务端run=" + rid.take(12))
                continue
            }
            if (!resumingSids.add(sid)) {
                AppLog.log("resume", "接管跳过 sid=" + sid.take(8) + " 已在接流中")
                continue
            }
            AppLog.log("resume", "服务端报告活跃轮次，接管 sid=" + sid.take(8) + " run=" + rid.take(12))
            identityScope.launch(Dispatchers.IO) {
                try {
                    val st = a.probeRun(rid)
                    if (st !is HermesApi.RunStatus.Known || st.status !in RUNNING_STATES) {
                        // 探到已收尾或探不出来：不接管。收尾的交给下一次对齐拉正文，
                        // 探不出来的下一轮 syncFromServer 再说（不在这里瞎标活跃）。
                        AppLog.log("resume", "接管放弃 sid=" + sid.take(8) + " 状态=" +
                            if (st is HermesApi.RunStatus.Known) st.status else "Unknown/Missing")
                        resumingSids.remove(sid)
                        return@launch
                    }
                    withContext(Dispatchers.Main.immediate) { attachRemoteRun(sid, rid, st.payload) }
                } catch (e: Exception) {
                    AppLog.err("resume", "接管探测失败 sid=" + sid.take(8), e)
                } finally {
                    resumingSids.remove(sid)
                }
            }
        }
    }

    /**
     * 把一条「不是本进程发起」的活跃 run 挂进该会话的运行时，并接上事件流。
     *
     * 与 resumeActiveRun 的冷启动分支同构，区别只在来源：那条靠本地落盘的 run_id，
     * 这条靠服务端会话列表的 active_run。气泡策略一致 —— 本地若已有这一轮的空气泡
     * （lastSeq 有值、末尾就是助手行）就复用它标成进行中，否则新起一个，避免同一轮
     * 正文被追到旧回复上、或显示两遍。
     */
    private fun attachRemoteRun(sid: String, rid: String, payload: org.json.JSONObject?) {
        val r = rt(sid)
        if (r.busy.value) return
        ensureLoaded(sid)
        r.runId = rid
        r.finished = false
        r.resumed = true
        r.lastSeq = prefs.lastSeq(sid)
        prefs.putActiveRun(sid, rid)
        restoreTurnStart(r, payload, "接管轮次")
        if (payload != null) restorePendingCard(r, payload)
        val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
        val hasLocalTurnBubble = lastAssistant >= 0 &&
            lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
        if (hasLocalTurnBubble) {
            val m = r.messages.value.toMutableList()
            m[lastAssistant] = m[lastAssistant].copy(
                pending = true,
                startedAt = if (m[lastAssistant].startedAt > 0) m[lastAssistant].startedAt
                else if (r.startedAt > 0) r.startedAt else System.currentTimeMillis()
            )
            r.messages.value = m
        } else {
            r.messages.value = capHistory(r.messages.value +
                Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r)))
        }
        r.busy.value = true
        r.lastEventAt = System.currentTimeMillis()
        updateRunService()
        AppLog.log("resume", "已接管运行中会话 sid=" + sid.take(8) + " 状态=" + (payload?.optString("status") ?: ""))
        streamRun(api ?: return, sid)
    }

    /**
     * 回到前台时的即时体检：对每个仍在跑、且超过 25 秒没收到任何事件（含心跳帧）的会话，
     * 判定为「流已被链路假死卡住」，主动断开并走一次重连续接。
     *
     * 阈值 25 秒的由来：服务端每 10 秒必发一个 keepalive，25 秒 ≈ 连丢两拍，
     * 正常空闲绝不会误判；不这样做的话，息屏期间假死的流要等 30 秒读超时才断开。
     */
    fun onAppForeground() {
        AppLog.log("ui", "回前台")
        // 回前台立刻补一次 CPU%（平时的 8 秒循环可能刚好在后台睡过一轮，别让顶栏空着）。
        identityScope.launch(Dispatchers.IO) { refreshCpuOnce() }
        // 回前台顺手看一眼定时任务收件箱：App 这个通道收不到推送（服务端
        // supports_async_delivery=False），任务产出只能自己来拉。
        refreshInbox()
        // 冷启动/探活失败遗留的活跃任务没有别的重试入口（onProfileChanged 只在切身份时跑），
        // 进前台先补一次恢复探测。已在跑的会话会被 resumeActiveRun 的守卫跳过，不会重复接流。
        resumeActiveRun()
        val now = System.currentTimeMillis()
        for (r in runtimes.values) {
            if (!r.busy.value || r.finished) continue
            // 回前台是「重新争取流式续接」的机会：把退避计数归零、作废正在跑的历史轮询，
            // 否则后台期间退避用尽的任务会停在「只等最终答案」的历史轮询里，界面不再刷流式进度。
            r.autoContinue = 0
            r.recoveryJob?.cancel()
            val last = r.lastEventAt
            if (last > 0 && now - last > 25_000L) {
                val c = r.call
                if (c != null && !c.isCanceled()) {
                    if (r.retryNote.value.isEmpty()) r.retryNote.value = ReconnectPolicy.noteForegroundReconnect()
                    // 主动掐掉假死连接：它的 onError 回调自己会走一次退避续接。
                    c.cancel()
                }
                // 掐完立刻自驱一次退避链，不再依赖 c.cancel() 引发的 onError：流其实早就
                // 死了（call 对象还在、onError 不会再响）时，只 cancel 而不重探就得再等一次
                // 回前台才会动 —— 真机实测「21:14:26 掐掉、21:17:39 才探到结果」，中间几分钟
                // 界面上什么都没有，用户看到的就是「一直提示、没动静」。
                // 单飞位（probing）保证不会双开退避链、也不会把重试计数加两回。
                maybeContinue(r.id)
            }
        }
        // 回前台补齐前台服务：后台期间被 Android 12+ 限制挡下的启动在这里补上，
        // 保证继续挂着的任务不再被系统冻结。
        updateRunService()
        // 顺带刷一次在线状态，别让角标停在离线
        identityScope.launch(Dispatchers.IO) { refreshStatus() }
        // 回前台顺带同步一次服务端标题（节流 30 秒，避免频繁切前后台狂打接口）
        // 同时把服务端的「正在跑」收下来（syncFromServer 内部会 reconcileServerRuns），
        // 这样别的端刚起的轮次也能被接管。
        syncFromServer(30_000L)
        // 回前台把当前会话与服务端对齐一次（正文，不只是标题）。
        syncOnForeground()
    }

    // ---- 诊断上报（App → 服务端；**仅手动**，2026-10-08 用户要求移除所有自动上传）----------

    /**
     * 造一份「紧凑状态快照」：每个本地会话的 id/标题/条数 + 末尾几条消息的角色+开头几十字+时间。
     * 只要状态摘要，不要正文全文——目的是让 agent 能把「App 看到的顺序/条数」和「服务端真实数据」
     * 直接对上，一眼看出差在哪。
     */
    private fun diagSnapshot(): JSONObject {
        val snap = JSONObject()
        val now = System.currentTimeMillis()
        snap.put("now", now)
        snap.put("profile", prefs.profile)
        snap.put("currentSessionId", _currentId.value ?: "")
        val arr = org.json.JSONArray()
        for (s in _sessions.value) {
            val r = runtimes[s.id] ?: continue
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("updatedAt", s.updatedAt)
            val msgs = r.messages.value
            o.put("localCount", msgs.size)
            o.put("busy", r.busy.value)
            o.put("runId", r.runId)
            val tail = org.json.JSONArray()
            for (m in msgs.takeLast(6)) {
                val mo = JSONObject()
                mo.put("role", m.role)
                mo.put("ts", m.ts)
                mo.put("len", m.text.length)
                mo.put("head", m.text.take(60))
                tail.put(mo)
            }
            o.put("tail", tail)
            arr.put(o)
        }
        snap.put("sessions", arr)
        return snap
    }

    /**
     * 上报一次运行日志 + 状态快照。
     *
     * **只由用户手点「上报诊断」触发**（2026-10-08 用户要求：移除启动/回前台/轮末的自动上传，
     * 以及服务端「请上报」标记的自动轮询——避免不经意间越攒越多）。不走节流：点了就传。
     */
    fun uploadDiagNow() {
        val a = api
        if (a == null) {
            _diagNote.value = "上报失败：还没配置服务器地址"
            return
        }
        val reason = "manual"
        _diagNote.value = "上报中…"
        identityScope.launch(Dispatchers.IO) {
            try {
                val log = AppLog.read(getApplication())
                val ver = AppLog.env(getApplication())
                val snap = runCatching { diagSnapshot() }.getOrNull()
                // 闪退/服务故障单独带上：闪退时 run.log 可能没落盘，堆栈只在这两份里。
                val crash = runCatching { CrashLog.read(getApplication()) }.getOrDefault("")
                val fault = runCatching { CrashLog.readFault(getApplication()) }.getOrDefault("")
                val res = a.uploadAppLog(log, snap, ver, ver, reason, crash, fault)
                val ok = res.optBoolean("ok", false)
                val name = res.optString("name", "")
                val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                _diagNote.value = if (ok) "已上报 ✓ " + now + "（日志 " + log.length + " 字）"
                    else "上报失败：服务端没确认"
                AppLog.log("diag", "上报诊断 reason=" + reason + " log=" + log.length + "字 crash=" +
                    crash.length + "字 fault=" + fault.length + "字 -> " + ok + " name=" + name)
            } catch (e: Exception) {
                _diagNote.value = "上报失败：" + (e.message ?: "?")
                AppLog.err("diag", "上报诊断失败 reason=" + reason, e)
            }
        }
    }

    /**
     * 回前台自动同步：把当前会话与服务端对齐一次。
     *
     * 为什么需要它：后台期间 App 可能没收到任何事件（进程被冻结、流被掐），
     * 或者在别的端（微信 / CLI）产生了新轮次——本地的消息就停在了切出去那一刻。
     * 以前回前台只同步「标题」，正文永远不会补齐，表现就是「切回来信息不同步」。
     *
     * 正在跑的会话不能硬拉：服务端记录此刻是半成品，合并进去会把半截内容写进气泡。
     * 改落一个「待同步」标记，交给本轮收尾（doneOk / failPending）时补拉一次。
     */
    private fun syncOnForeground() {
        val now = System.currentTimeMillis()
        if (now - lastFgSyncAt < FOREGROUND_SYNC_THROTTLE_MS) return
        lastFgSyncAt = now
        val cur = _currentId.value
        if (cur.isNotEmpty()) {
            val r = rt(cur)
            if (r.busy.value) {
                r.needSync = true
                AppLog.log("sync", "回前台：会话在跑，落待同步标记 sid=" + cur.take(8))
            } else {
                AppLog.log("sync", "回前台：同步当前会话 sid=" + cur.take(8))
                refreshFromServerFor(cur)
            }
        }
        // 其它正在跑的会话也各落各的标记，各自收尾时补拉。
        for ((sid, other) in runtimes) {
            if (sid != cur && other.busy.value) other.needSync = true
        }
        alignStaleSessions(cur)
    }

    /**
     * 回前台把「服务端比本地新」的其它会话也对齐一遍（最近 3 条、节流 30 秒）。
     *
     * 为什么以前只对齐当前那一个不够：别的端在会话 X 里跑了新轮次，而 App 自己不知道 X 忙
     * （busy 只来自本进程发起的 run），于是既不对齐、也没有收尾补拉 —— 没被点到的会话内容
     * 就一直漂着，直到用户切过去才追平（切会话修好后又多了一条追平路径，但得先切）。
     * 这里用服务端返回的 last_active 与本地 updatedAt 比对，只挑真的落后了的会话拉。
     */
    private fun alignStaleSessions(cur: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlignAt < 30_000L) return
        lastAlignAt = now
        val la = serverLastActive
        if (la.isEmpty()) return
        val stale = _sessions.value
            .filter { !it.archived && it.id != cur }
            .filter { m ->
                val sv = la[m.id] ?: return@filter false
                sv > m.updatedAt + 2_000L   // 容 2 秒时钟/落盘误差，避免无谓重拉
            }
            .sortedByDescending { la[it.id] ?: 0L }
            .take(3)
        for (m in stale) {
            val r = rt(m.id)
            if (r.busy.value) { r.needSync = true; continue }
            AppLog.log("sync", "回前台对齐落后会话 sid=" + m.id.take(8))
            refreshFromServerFor(m.id)
        }
    }

    /** 上次回前台同步的时间：节流用。 */
    private var lastFgSyncAt = 0L

    /** 拉一次服务端能力：是否支持原生图片（决定图片是原生附图还是先转文字）。 */
    fun fetchCapabilities() {
        val a = api ?: return
        identityScope.launch(Dispatchers.IO) {
            try {
                val caps = a.capabilities()
                val f = caps.optJSONObject("features")
                if (f != null) _supportsVision.value = f.optBoolean("supports_vision", true)
            } catch (_: Exception) {
            }
        }
    }

    /** 重开 App 时从服务端拉当前会话消息：后台跑完的任务产出据此补回。 */
    fun refreshFromServer() = refreshFromServerFor(_currentId.value)

    /** 指定会话的服务端消息拉取与合并（翻历史兜底也复用）。 */
    private fun refreshFromServerFor(id: String) {
        val a = api ?: return
        if (id.isEmpty()) return
        val r = rt(id)
        // 已删除的会话：拉回来的内容一个字都不能写，否则会把删掉的消息文件写复活。
        if (r.dead) return
        // 忙 = 推迟，不是丢弃。
        //
        // 2026-10-08：这里原来是静默 return，冷启动时被踩得很惨——onProfileChanged 先调
        // refreshFromServer()、再调 resumeActiveRun()，请求发出时还没忙，回来时 run 已经
        // 恢复成 busy，撞上函数内第二道守卫被丢掉；而两道守卫都不落 needSync，收尾时
        // doneOk 看 needSync=false 就不补拉。表现就是用户报的「执行中途杀掉 App，重开进
        // 该会话，内容停在退出前那一帧」——服务端 1.9MB 的记录拉回来了却被扔掉。
        if (r.busy.value) {
            r.needSync = true
            AppLog.log("sync", "会话在跑，落待同步标记（入口）sid=" + id.take(8))
            return
        }
        // 挂进程级作用域而不是 identityScope：回前台同步与「收尾补拉」都可能在
        // Activity 已被重建/销毁之后触发，挂 identityScope 会随旧实例一起被取消，
        // 表现就是「该同步的时候没同步」。
        identityScope.launch(Dispatchers.IO) {
            try {
                fun parseRows(a0: org.json.JSONArray?): MutableList<Msg> {
                    val out = mutableListOf<Msg>()
                    if (a0 == null) return out
                    for (i in 0 until a0.length()) {
                        val o = a0.optJSONObject(i) ?: continue
                        val role = o.optString("role", "assistant")
                        if (role != "user" && role != "assistant") continue
                        val content = contentText(o)
                        if (content.isEmpty()) continue
                        // run_id（服务端把本轮 run id 盖在助手消息行上，本地补丁 voice-msg-runid）：
                        // 带上它，从服务端历史同步下来的回复也能出播放按钮（原来只有
                        // App 当场收过 run.completed 的那条才有 runId，所以「有时候按钮不见了」）。
                        out.add(Msg(role, content, pending = false,
                            ts = parseTs(o.optString("timestamp", "")),
                            runId = o.optString("run_id", "")))
                    }
                    return out
                }
                // 增量窗口（2026-10-13 省电改动 + 用户铁律「本地绝不能比服务端少」）：
                // 窗口 = 常规 40 行 + 本地落后服务端的行数 —— 落后多少就把多少补进窗口里，
                // 本地缺、只在服务端的内容一定落在窗口内。整份拉只在三种情况：本地为空、
                // 服务端条数未知、落后超过上限（ReconnectPolicy.syncFetchRows 返回 0）。
                // 实测同一会话：全量 581KB~1.81MB，末尾 40 行 112~196KB（省 87%）；这一项
                // 原来 37.5 MB/小时，是手机上最大的一笔流量。
                val localNow = r.messages.value
                val srvRows = serverMsgCounts[id] ?: -1
                val rows = ReconnectPolicy.syncFetchRows(
                    serverRows = srvRows,
                    localRows = ReconnectPolicy.localRawRows(
                        localNow.size, localNow.sumOf { traceLineCount(it.trace) }),
                    localEmpty = localNow.isEmpty(),
                )
                AppLog.log("sync", "同步窗口 sid=" + id.take(8) + " " +
                    (if (rows <= 0) "整份" else rows.toString() + " 行") +
                    "（本地=" + localNow.size + " 条 服务端=" + srvRows + " 行）")
                var list = if (rows <= 0) parseRows(a.sessionMessages(id).optJSONArray("data"))
                           else parseRows(a.sessionMessagesTail(id, rows).optJSONArray("data"))
                // 兜底：末尾窗口正好落在一轮中间（这一轮原始行超过 40 条）时，窗口里一条用户
                // 消息都没有 —— 服务端那一段会被当成 head 丢掉、补不回最终正文，退回全量再拉一次。
                // 只在极少数长工具轮次里发生，不是常规路径。
                if (localNow.isNotEmpty() && list.isNotEmpty() && list.none { it.role == "user" }) {
                    AppLog.log("sync", "末尾窗口内无用户消息，退回全量 sid=" + id.take(8))
                    list = parseRows(a.sessionMessages(id).optJSONArray("data"))
                }
                // 守卫：该会话此刻已在跑任务则不覆盖（切换会话不影响——只写它自己的缓冲）。
                // 但要落 needSync：请求往返期间 run 可能刚开始/被恢复（冷启动就是这么撞上的），
                // 直接丢就等于把服务端这一份记录白拉了。
                if (r.dead) {
                    // 请求往返期间会话被删了：整份丢掉，绝不落盘（否则删掉的文件复活）。
                    AppLog.log("sync", "会话已删除，丢弃拉回结果 sid=" + id.take(8))
                    return@launch
                }
                if (r.busy.value) {
                    r.needSync = true
                    AppLog.log("sync", "会话在跑，落待同步标记（返回时）sid=" + id.take(8))
                    return@launch
                }
                if (list.isEmpty()) {
                    AppLog.log("sync", "服务端会话无消息（本地保留 " + r.messages.value.size +
                        " 条）sid=" + id.take(8))
                    return@launch
                }
                // 合并而不是覆盖：本地消息正文里带内联图片（data URL），而服务端存的是
                // 原始 MEDIA: 路径——直接覆盖会把图片弄丢（用户报「更新后图片不见了」）。
                // 对齐方式不能用数组下标：服务端会滤掉「内容为空的助手行」与工具行，
                // 本地却保留带工具轨迹的空助手行，两边长度天然不等（实测某会话差 236 行），
                // 按下标对齐会从第一处差异起整体错位，把服务端回复贴到错误的气泡上。
                // 改用两边都完整保留、且有序的「用户消息」做锚点，见 mergeByUserAnchor。
                val local = r.messages.value
                val merged = mergeByUserAnchor(local, list)
                // 合并可能把本轮最终答复换成服务端插入行（那条行没有 usage/runId/过程轨迹），
                // 合并后按会话级收尾元数据补挂一遍：使用量行、过程行、语音按钮都靠它。
                val merged2 = withTurnMeta(r, merged)
                // 统一走 setMsgs（唯一写入口 + 去抖落盘）：原来这里直接赋值 + 另开一次写盘，
                // 与 saveRuntime 并发写同一个消息文件，谁后落盘谁赢，会丢正文。
                setMsgs(r, merged2)
                r.loaded = true
                // 本地基线推进到服务端那一份：不回写的话回前台对齐永远判「服务端更新」，
                // 同一批会话每 30 秒被重拉一次。
                val sv = serverLastActive[id]
                if (sv != null && sv > 0) {
                    val sl = _sessions.value.toMutableList()
                    val si = sl.indexOfFirst { it.id == id }
                    if (si >= 0 && sv > sl[si].updatedAt) {
                        sl[si] = sl[si].copy(updatedAt = sv)
                        _sessions.value = sl
                        saveIndexAsync()
                    }
                }
                AppLog.log("sync", "合并服务端记录 sid=" + id.take(8) +
                    " 本地=" + local.size + " 服务端=" + list.size + " 合并后=" + merged.size)
            } catch (e: Exception) {
                AppLog.err("sync", "拉服务端消息失败 sid=" + id.take(8), e)
                // 服务端无此会话或网络异常：保留本地内容
            }
        }
    }

    private fun pingLoop() {
        if (!pingStarted.compareAndSet(false, true)) return
        identityScope.launch(Dispatchers.IO) {
            var fails = 0
            var tick = 0
            // 最近一次探测成功的时刻：用于「30 秒内成功过就仍算在线」的兜底，
            // 避免单次超时（fails 未达 3 次阈值）也把角标打红。
            var lastOkAt = 0L
            while (true) {
                // 省电（2026-10-11 用户要求）：App 不在前台就一个请求都不发——心跳、更新检查全停，
                // 只留 10 秒醒一次的廉价 sleep（不给网络栈和射频添活动）。CPU% 也停，但**不清值**：
                // 回前台会立刻补一次（onAppForeground），清掉反而让顶栏先空一拍。
                if (!AppForeground.isForeground) {
                    delay(10_000)
                    continue
                }
                val ok = api?.ping() ?: false
                if (ok) {
                    if (!_online.value) AppLog.log("net", "恢复在线")
                    fails = 0
                    lastOkAt = System.currentTimeMillis()
                    _online.value = true   // 恢复立刻生效
                } else {
                    // 2026-10-08：连续 2 次失败就翻「离线」太敏感 —— 移动网络/服务端偶发一次
                    // 超时就闪红，用户实测「一直显示离线」而服务端 6195 次心跳全 200。
                    // 现在：连续 3 次失败才翻，且最近 30 秒内成功过就仍算在线（兜底）。
                    fails++
                    val recentlyOk = lastOkAt > 0 && System.currentTimeMillis() - lastOkAt < 30_000L
                    if (fails >= 3 && !recentlyOk) {
                        if (_online.value) AppLog.log("net", "连续 " + fails + " 次探测失败，标记离线")
                        _online.value = false
                    } else {
                        AppLog.log("net", "探测失败第 " + fails + " 次（最近成功=" + recentlyOk + "），暂不标离线")
                    }
                }
                // 每 10 分钟静默查一次更新（2026-10-13 省电：原 30 秒一次 = 95 次/小时的公网往返，
                // 实测 3 小时 285 次；发新版后角标最多晚 10 分钟亮起，可接受）。仅前台。
                tick++
                if (tick % 120 == 0) checkUpdateSilently()
                // 在线点 5 秒一探就够（CPU% 不在这条循环里，见 startCpuLoop）。
                delay(5_000L)
            }
        }
    }

    /**
     * 顶栏 CPU 百分比：独立的一条 8 秒循环（用户 2026-10-11 定版：把这一项弄回来、单独保留、8 秒一次）。
     *
     * 为什么与心跳分开：心跳（在线点）和 CPU 是两件互不相干的事，之前把 CPU 并进心跳并按「状态页
     * 是否可见」开关，结果用户不在状态页时顶栏就不显示 CPU 了。现在 CPU 归自己这条循环——只要
     * App 在前台就取，跟停在哪一页无关；后台一律不取（省电，用户明确要求过）。
     */
    private fun startCpuLoop() {
        if (cpuTicker?.isActive == true) return
        cpuTicker = identityScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (AppForeground.isForeground) refreshCpuOnce()
                delay(CPU_REFRESH_MS)
            }
        }
    }

    /**
     * 主线程卡顿看门狗（2.215）：每秒打一次点，两次之间超过阈值就记一条。
     *
     * 为什么要它：用户报「App 卡死」时，日志会在卡死那一步**直接断掉**——因为所有周期循环
     * （心跳/CPU/同步）都挂在主线程派发器上，主线程被拖住就什么都写不出来，事后只能靠猜。
     * 这条循环量的是「主线程被占了多久」，卡完立刻落一行（含堆内存与本机堆），下次再卡就
     * 有实据可查，不用再猜是渲染、GC 还是内存杀进程。
     *
     * 只在 App 在前台时记：后台被系统冻结/回收不是 bug，那种几万毫秒的「卡顿」是假信号。
     */
    private fun startStallWatch() {
        if (stallWatch?.isActive == true) return
        stallWatch = identityScope.launch {
            var last = android.os.SystemClock.uptimeMillis()
            var lastLog = 0L
            while (isActive) {
                delay(STALL_TICK_MS)
                val now = android.os.SystemClock.uptimeMillis()
                val gap = now - last
                last = now
                if (!AppForeground.isForeground) continue
                if (gap < STALL_WARN_MS || now - lastLog < STALL_LOG_GAP_MS) continue
                lastLog = now
                val rt = Runtime.getRuntime()
                AppLog.log("perf", "主线程卡顿 " + gap + "ms 堆=" + (rt.totalMemory() - rt.freeMemory()) / 1048576 +
                    "MB 本机堆=" + android.os.Debug.getNativeHeapAllocatedSize() / 1048576 +
                    "MB 会话消息=" + runCatching { rt(_currentId.value).messages.value.size }.getOrDefault(-1))
            }
        }
    }

    /** 取一次 CPU%（失败保留上一次的值，不闪空；后台调用方自己负责不问）。 */
    private fun refreshCpuOnce() {
        runCatching {
            val p = api?.sysinfo()?.optDouble("cpu_percent", -1.0) ?: -1.0
            if (p >= 0) _cpuPercent.value = p
        }
    }

    // ---------- 定时任务（服务端 /api/jobs） ----------

    private val _jobs = MutableStateFlow<List<JobItem>>(emptyList())
    val jobs = _jobs.asStateFlow()

    private val _jobsErr = MutableStateFlow("")
    val jobsErr = _jobsErr.asStateFlow()

    private val _jobsNote = MutableStateFlow("")
    val jobsNote = _jobsNote.asStateFlow()

    /** App 收件箱：定时任务产出（api_server 通道不支持推送，见 HermesApi.inbox）。 */
    private val _inbox = MutableStateFlow<List<CronReport>>(emptyList())
    val inbox = _inbox.asStateFlow()
    private val _inboxUnread = MutableStateFlow(0)
    val inboxUnread = _inboxUnread.asStateFlow()
    private val _inboxErr = MutableStateFlow("")
    val inboxErr = _inboxErr.asStateFlow()

    /** 正在看的报告：非 null 时界面弹「定时任务产出」全文。 */
    private val _cronReport = MutableStateFlow<CronReport?>(null)
    val cronReport = _cronReport.asStateFlow()

    /**
     * 拉一次收件箱。App 打开 / 回前台 / 进定时任务页时调。
     *
     * 拉回来后：新的未读条目弹一条本机通知（App 开着也能看见「任务跑完了」），
     * 同一条只弹一次（按 id 记着）。失败只记错误文案，不打扰。
     */
    fun refreshInbox(notifyNew: Boolean = true) {
        // 必须显式切 IO：identityScope 是 Dispatchers.Main.immediate，而 a.inbox()
        // 是阻塞式 HTTP。挂在主线程上会抛 NetworkOnMainThreadException（在建立连接那一
        // 刻就抛，请求根本没发出去——服务端访问日志里一条都不会有），界面显示
        // 「收件箱获取失败」。而且该异常的 message 是 null，不带上异常类名就只剩一个「?」。
        identityScope.launch(Dispatchers.IO) {
            try {
                val a = api ?: return@launch
                val resp = a.inbox(limit = 50)
                val arr = resp.optJSONArray("items") ?: JSONArray()
                val out = mutableListOf<CronReport>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        CronReport(
                            id = o.optString("id", ""),
                            at = o.optString("at", ""),
                            jobId = o.optString("job_id", ""),
                            jobName = o.optString("job_name", ""),
                            failed = o.optString("status", "ok") == "failure",
                            body = o.optString("body", ""),
                            // 服务端「未读」是 JSON null（不是缺键）：Android 的 optString 会把
                            // JSON null 读成字符串 "null"（非空），所以这里必须先判 isNull。
                            unread = o.isNull("acked_at") || o.optString("acked_at", "").isEmpty(),
                        )
                    )
                }
                _inbox.value = out
                _inboxUnread.value = resp.optInt("unread", out.count { it.unread })
                _inboxErr.value = ""
                if (notifyNew) {
                    // 只弹最新一条未读（一次拉回好几条未读不该连响一串），且**跨重启**只弹一次：
                    // 去重集合落盘在 Prefs（原来只在内存里，重启就空 → 每次开机都把同一条未读
                    // 再响一遍；用户不进定时任务页点掉它就会一直响）。
                    val newestUnread = out.firstOrNull { it.unread }
                    if (newestUnread != null && newestUnread.id !in prefs.notifiedReportIds()) {
                        prefs.markReportNotified(newestUnread.id)
                        Notifier.notifyDone(
                            getApplication(),
                            (if (newestUnread.failed) "定时任务失败：" else "定时任务完成：") +
                                jobDisplayName(newestUnread.jobName, newestUnread.jobId),
                            newestUnread.body.replace(Regex("\\s+"), " ").trim().take(80),
                            // 点这条通知要跳到「定时任务」页(tab 3)，不是某个会话——
                            // 原来不带任何跳转标识，点击只把 App 拉到前台、停在原页（用户报障）。
                            openTab = 3,
                        )
                    }
                }
            } catch (e: Exception) {
                _inboxErr.value = "收件箱获取失败：" + diagText(e)
            }
        }
    }

    /** 打开一条报告看全文（未读则顺手标已读）。 */
    fun openCronReport(report: CronReport) {
        _cronReport.value = report
        if (report.unread) ackInbox(listOf(report.id))
    }

    fun closeCronReport() {
        _cronReport.value = null
    }

    /** 标记已读：ids 为空表示整箱已读。标记完本地状态与未读数一起更新。 */
    fun ackInbox(ids: List<String> = emptyList(), all: Boolean = false) {
        // 同上：a.ackInbox() 也是阻塞 HTTP，不能在主线程上跑。
        identityScope.launch(Dispatchers.IO) {
            try {
                val a = api ?: return@launch
                a.ackInbox(ids, all)
                _inbox.value = if (all) {
                    _inbox.value.map { it.copy(unread = false) }
                } else {
                    val set = ids.toSet()
                    _inbox.value.map { if (it.id in set) it.copy(unread = false) else it }
                }
                _inboxUnread.value = _inbox.value.count { it.unread }
            } catch (e: Exception) {
                _inboxErr.value = "标记已读失败：" + diagText(e)
            }
        }
    }

    /** 删除收件箱条目：ids 非空按 id 删；all=true 整箱清空。删完本地列表与未读数即时更新。 */
    fun deleteInbox(ids: List<String> = emptyList(), all: Boolean = false) {
        identityScope.launch(Dispatchers.IO) {
            try {
                val a = api ?: return@launch
                val resp = a.deleteInbox(ids, all)
                val removed = resp.optInt("removed", 0)
                val set = ids.toSet()
                _inbox.value = if (all) emptyList() else _inbox.value.filter { it.id !in set }
                _inboxUnread.value = _inbox.value.count { it.unread }
                // 删掉的正是当前打开的那条时，顺手收掉全文弹窗
                val cur = _cronReport.value
                if (cur != null && (all || cur.id in set)) _cronReport.value = null
                AppLog.log("job", "收件箱删除 请求=" + (if (all) "全部" else ids.size.toString()) + " 实际=" + removed)
                // 服务端一条都没删掉时，别就此静默。原来「点了没反应」很难判断是入口没触发、
                // 还是服务端没删（用户 2026-10-10 报「收件箱消息没法删除」时，服务端访问日志里
                // 连一条删除请求都没有）。这里补一条提示，并顺手重拉一次，把过期视图对齐。
                if (removed == 0) {
                    _inboxErr.value = "服务端没有删除任何条目（条目可能已被删）。已重新拉取收件箱。"
                    refreshInbox(notifyNew = false)
                } else {
                    _inboxErr.value = ""
                }
            } catch (e: Exception) {
                _inboxErr.value = "删除失败：" + diagText(e)
            }
        }
    }

    /**
     * 列表当前是否包含「已停用」（=被暂停）的任务。
     *
     * 为什么提到 ViewModel 并做成流：界面上的「显示/隐藏已停用」开关与列表口径必须**同源**。
     * 原来开关是界面本地 state，而暂停某个任务后 jobAction 会把口径强制切到「含已停用」
     * （否则那条当场从列表里消失，用户报「点暂停后任务就不见了」，2026-10-10）——
     * 两边一旦不同步，开关文字与实际内容就对不上。
     *
     * 唯一口径来源：refreshJobs 的默认参数也从它取（原来另有一个 private var 同名，
     * 会与该流冲突，已合并成一个）。
     */
    private val _jobsIncludeDisabled = MutableStateFlow(false)
    val jobsIncludeDisabled = _jobsIncludeDisabled.asStateFlow()

    /** 已知定时任务的中文名；不认识的返回空串，界面回落显示原始名。 */
    fun refreshJobs(includeDisabled: Boolean = _jobsIncludeDisabled.value) {
        _jobsIncludeDisabled.value = includeDisabled
        identityScope.launch(Dispatchers.IO) {
            try {
                val out = fetchJobs(includeDisabled) ?: return@launch
                _jobs.value = out
                _jobsErr.value = ""
                AppLog.log("job", "任务列表加载 " + out.size + " 条 includeDisabled=" + includeDisabled)
            } catch (e: Exception) {
                AppLog.err("job", "任务列表加载失败", e)
                _jobsErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    /** 拉一次任务列表并解析成 JobItem（纯读取，不写 _jobs）。失败抛异常，由调用方兜。 */
    private suspend fun fetchJobs(includeDisabled: Boolean): List<JobItem>? {
        val resp = api?.listJobs(includeDisabled) ?: return null
        val arr = resp.optJSONArray("jobs") ?: org.json.JSONArray()
        val out = mutableListOf<JobItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sch = o.optJSONObject("schedule")
            val rawName = jsonStr(o, "name").ifEmpty { "(未命名)" }
            val rawLast = jsonStr(o, "last_status")
            // 最近一次执行明细：服务端每条 job 都带 latest_execution，
            // 以前 App 整个丢掉，于是「已触发执行」之后看不出跑成没成。
            val ex = o.optJSONObject("latest_execution")
            out.add(
                JobItem(
                    id = jsonStr(o, "id"),
                    name = rawName,
                    zhName = jobZhName(rawName),
                    note = jobZhNote(rawName),
                    schedule = jobZhSchedule(sch),
                    enabled = o.optBoolean("enabled", true),
                    state = jobZhState(jsonStr(o, "state")),
                    lastStatus = jobZhStatus(rawLast),
                    lastOk = rawLast == "ok",
                    lastRun = TimeFmt.isoToBj(jsonStr(o, "last_run_at")),
                    nextRun = TimeFmt.isoToBj(jsonStr(o, "next_run_at")),
                    execId = jsonStr(ex, "id"),
                    execStatus = jsonStr(ex, "status"),
                    execDuration = execDurationText(ex),
                    execError = jsonStr(ex, "error"),
                    deliveryError = jsonStr(o, "last_delivery_error"),
                    // 任务详情（展开时才展示）：描述原文 / 投递方式 / 模型 / 创建时间 / 已跑次数。
                    desc = jsonStr(o, "prompt"),
                    deliver = jsonStr(o, "deliver"),
                    model = jsonStr(o, "model"),
                    createdAt = TimeFmt.isoToBj(jsonStr(o, "created_at")),
                    repeatDone = o.optJSONObject("repeat")?.optInt("completed", 0) ?: 0,
                    // 谁停的：user = 用户在界面上按的（服务端会拒绝 agent 的自行恢复）。
                    pausedBy = jsonStr(o, "paused_by"),
                    pausedAt = TimeFmt.isoToBj(jsonStr(o, "paused_at")),
                    pausedReason = jsonStr(o, "paused_reason"),
                )
            )
        }
        return out
    }

    /** 定时任务动作：pause / resume / run。成功后按原过滤口径重拉列表。 */
    fun jobAction(jobId: String, action: String, jobLabel: String = "") {
        if (jobId.isEmpty()) return
        val who = jobLabel.ifEmpty { jobId }
        identityScope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            AppLog.log("job", "操作 " + action + " id=" + jobId.take(12) + " 名=" + who)
            try {
                when (action) {
                    "pause" -> { a.pauseJob(jobId); _jobsNote.value = "已暂停：" + who }
                    "resume" -> { a.resumeJob(jobId); _jobsNote.value = "已恢复：" + who }
                    "delete" -> { a.deleteJob(jobId); _jobsNote.value = "已删除：" + who }
                    "run" -> {
                        // 立即执行：POST 只是「排上队」，服务端返回 {"ok":true} 没有 run_id。
                        // 先记下当前这次执行记录 id，触发后轮询等它变成一次新的执行，
                        // 才能知道触发的是哪条、跑成没成、产出是什么。
                        val before = _jobs.value.firstOrNull { it.id == jobId }?.execId.orEmpty()
                        a.runJob(jobId)
                        _jobsNote.value = "已触发执行：" + who
                        _jobsErr.value = ""
                        followRunToCompletion(a, jobId, who, before)
                        delay(400)
                        refreshJobs()
                        return@launch
                    }
                    else -> _jobsNote.value = "完成"
                }
                _jobsErr.value = ""
            } catch (e: Exception) {
                AppLog.err("job", "操作失败 " + action + " id=" + jobId.take(12), e)
                _jobsNote.value = ""
                _jobsErr.value = "操作失败：" + (e.message ?: "?")
            }
            delay(400)
            // 暂停/恢复后按「含已停用」的口径重拉：暂停会让这条变成「已停用」，而默认口径
            // 不含已停用——照旧口径刷新它当场从列表里消失，用户以为「一点暂停任务就没了」
            //（2026-10-10 报障）。现在让它留在原地显示「已停用」，要收起再点标题行的
            //「隐藏已停用」（开关与口径同源，见 jobsIncludeDisabled）。
            if (action == "pause" || action == "resume") refreshJobs(includeDisabled = true)
            else refreshJobs()
        }
    }

    /**
     * 「立即执行」后盯住这次执行直到结束，并把产出摘要回显到提示行。
     *
     * 为什么要轮询：POST /api/jobs/{id}/run 的语义是「下一轮 tick 排上队」，返回体只有
     * {"ok":true}，没有 run_id，也没有产出。唯一能看结果的途径是列表里那条
     * latest_execution（claimed→running→completed/failed），所以轮询它。
     *
     * 产出摘要：cron 任务跑起来会在服务端建一个会话，id 形如
     * cron_<job.id>_<yyyymmdd_HHMMSS>（job.id 与 latest_execution.job_id 一致）。
     * 跑完去 /api/sessions 里找到这次新出现的那个会话，拉最后一条助手消息即可。
     * 拿不到就只报「已完成」，绝不编造。
     */
    private suspend fun followRunToCompletion(
        a: HermesApi, jobId: String, who: String, beforeExecId: String,
    ) {
        var lastStatus = ""
        for (i in 1..40) {   // 每 3 秒一轮，最多盯 2 分钟
            delay(3_000)
            val out = try { fetchJobs(true) } catch (_: Exception) { null } ?: continue
            _jobs.value = out
            val job = out.firstOrNull { it.id == jobId } ?: continue
            val ex = job.execId
            // 还没出现新的执行记录：可能还在等下一轮 tick，继续等。
            if (ex.isEmpty() || ex == beforeExecId) continue
            lastStatus = job.execStatus
            when (job.execStatus) {
                "completed" -> {
                    val tail = job.execDuration
                    AppLog.log("job", "执行完成 " + who + " 耗时=" + tail)
                    _jobsNote.value = "已完成：" + who + (if (tail.isEmpty()) "" else "（耗时 " + tail + "）")
                    val summary = fetchRunSummary(a, jobId)
                    if (summary.isNotEmpty()) {
                        _jobsNote.value = _jobsNote.value + "\n产出：" + summary
                    }
                    return
                }
                "failed" -> {
                    val why = job.execError.ifEmpty { "未知原因" }
                    _jobsNote.value = ""
                    AppLog.log("job", "执行失败 " + who + " — " + why.take(200))
                    _jobsErr.value = "执行失败：" + who + " — " + why.take(200)
                    return
                }
                "unknown" -> {
                    _jobsNote.value = ""
                    _jobsErr.value = "执行状态未知：" + who + "（进程可能异常退出，请查服务端）"
                    return
                }
                else -> { /* claimed / running：继续等 */ }
            }
        }
        // 盯满 2 分钟还没结束：如实说还在跑，不谎报完成。
        val still = if (lastStatus == "running") "仍在执行中" else "已排入队列，仍在等待"
        _jobsNote.value = who + "：已触发，" + still + "（超过 2 分钟，可稍后刷新查看）"
    }

    /**
     * 取本次 cron 执行的产出摘要：找该任务最新一次运行会话，取最后一条助手消息。
     * 认不出会话或没有正文就返回空串（绝不编造）。
     */

    fun refreshStatus() {
        identityScope.launch(Dispatchers.IO) {
            try {
                val h = api?.sysinfo() ?: return@launch
                _statusHero.value = buildHero(h)
                _statusMetrics.value = buildMetrics(h)
                _statusSections.value = buildStatus(h)
                _statusErr.value = ""
            } catch (e: Exception) {
                AppLog.err("status", "获取系统状态失败", e)
                _statusErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    // ---------- 用量统计（「统计」页） ----------

    /** 统计窗口（天）：1/7/30/90 四档，默认 7；页面按钮与它同源（动作后重拉才不会再错位）。 */
    private val _statsDays = MutableStateFlow(7)
    val statsDays = _statsDays.asStateFlow()

    private val _stats = MutableStateFlow<StatsModel?>(null)
    val stats = _stats.asStateFlow()
    private val _statsErr = MutableStateFlow("")
    val statsErr = _statsErr.asStateFlow()
    private val _statsLoading = MutableStateFlow(false)
    val statsLoading = _statsLoading.asStateFlow()

    /**
     * 拉一次用量统计（服务端只读聚合）。
     *
     * 必须走 Dispatchers.IO：HermesApi 全是阻塞式 okhttp，挂在主线程作用域上会抛
     * NetworkOnMainThreadException（收件箱/子任务轮询都栽过，且该异常 message 为 null，
     * 报障里只留一个「?」）。失败只写 [statsErr]、**不清空已有数据**——切换窗口失败时留着
     * 上一份数据比空屏有用。
     */
    fun refreshStats(days: Int = _statsDays.value) {
        _statsDays.value = days
        _statsLoading.value = true
        identityScope.launch(Dispatchers.IO) {
            try {
                val raw = api?.stats(days) ?: return@launch
                _stats.value = buildStats(raw)
                _statsErr.value = ""
            } catch (e: Exception) {
                AppLog.err("stats", "获取用量统计失败", e)
                _statsErr.value = "获取失败：" + diagText(e)
            } finally {
                _statsLoading.value = false
            }
        }
    }

    // ---------- 发送与流式接收 ----------

    fun send(text: String) {
        val a = api ?: return
        val imgs = _pendingImages.value
        if (text.isBlank() && imgs.isEmpty()) return
        // 空态（无选中会话）：直接输入即新建对话
        if (_currentId.value.isEmpty()) {
            val id = UUID.randomUUID().toString()
            _currentId.value = id
            prefs.sessionId = id
            val nOrder0 = if (_sessions.value.any { it.order > 0L }) nextTopOrder() else 0L
            _sessions.value = listOf(SessionMeta(id, "新对话", stamp(), false, nOrder0)) + _sessions.value
            saveIndexAsync(debounceMs = 0L)
            rt(id).loaded = true
        }
        val sid = _currentId.value
        val r = rt(sid)
        // 同一会话正在跑：不再直接拦下，改为排队（气泡先落下，本轮结束自动发）。
        // 其它会话照发，不受影响。
        val willQueue = r.busy.value
        ensureLoaded(sid)
        val wasEmpty = r.messages.value.none { it.role == "user" }
        // 引用回复：长按选中的那条，压成一行片段。发往服务端的正文前拼上「> 引用」，
        // 让模型知道在回应哪一句；气泡里只回显片段，不重复整段。
        val quoted = _quoteTarget.value
        val quoteSnip = if (quoted != null) quoteSnippet(quoted) else ""
        val sendText = if (quoteSnip.isNotEmpty()) "> " + quoteSnip + "\n" + text else text
        // 只选了附件、一个字没打时，正文是空的。服务端对 input 有非空校验，而且是先查
        // input、后处理附件——空正文直接被 400 挡回（Missing 'input' field），附件白选。
        // 所以发往服务端的正文补一个占位词；气泡里仍然一个字都不显示。
        // 引用/回执的位置锚点也用这一版，重发与「确认送达」才和服务端记录对得上。
        val wireText = sendText.ifBlank { attachmentLabel(imgs) }
        // 记下发送前的用户消息条数（翻历史时的位置锚点）与本轮正文（内容校验）。
        // 锚点用拼好引用的 sendText：服务端记录里存的就是这一版，才能对上。
        val prior = r.messages.value.count { it.role == "user" }
        // 只有「这一条要立刻发」时才安装本轮的锚点、并掐掉上一轮的翻历史恢复任务（F05）。
        // 排队时绝不能动它们：那是**正在跑那一轮**的恢复上下文；被覆盖或取消之后，一旦断流
        // 要翻历史找答案，就会拿错正文与条数去认领（误报「本次发送没落到服务端」），
        // 甚至把正在进行中的恢复直接取消掉。排队条目自己带着 prior 与正文，出队时再装上。
        if (!willQueue) {
            r.priorUserCount = prior
            r.pendingSendText = wireText.trim()
            r.recoveryJob?.cancel()
        }
        // 发出去的图先落进 App 私有「已发送」目录：相册给的 content:// 会被系统回收
        // （换机/清数据/授权到期），outbox 会被「清理缓存」清掉——两者都会让历史里的图变白框。
        // sent/ 不参与清理、又是私有文件，重开、清缓存后都还在。
        //
        // 关键：挪动之后，「上传」和「气泡回显」必须都认挪过去的新文件。老代码在这一步把
        // outbox 原文件删了，可后面发请求时还按老路径读 → 文件已不存在，附件必然发不出去。
        val durableImgs = mutableListOf<String>()
        val uploadFiles = mutableListOf<java.io.File>()
        val durableNames = mutableListOf<String>()
        for (p in imgs) {
            // 图片与非图片**都**挪进「已发送」目录（原来只挪图片）：非图片也要留一份，
            // 否则重发时拼不出与首次一致的请求体，同幂等键会被判「键同体不同」而 409（F07）。
            val (f, uri) = persistOutgoing(p)
            uploadFiles.add(f)
            durableNames.add(f.name)
            if (p.isImage) durableImgs.add(uri)
        }
        // 发送前再核一次总量（F20）：拦住「选的时候没超、文件后来变大或被别的路径塞进来」。
        // 超了就不发、待发区保持原样，让用户自己删几个 —— 比把一堆大文件同时读进内存崩掉好。
        val attachTotal = uploadFiles.sumOf { it.length() }
        if (exceedsAttachBudget(0L, attachTotal)) {
            _imageNote.value = attachBudgetText()
            AppLog.log("attach", "发送前合计超预算 " + attachTotal + "B，已拦下（待发区保留）")
            return
        }
        // 投递状态挂在用户消息自己身上（按 msgId 认领，不靠位置）：POST 没回来前是 sending，
        // 拿到 run_id 才转 accepted，中途断了转 uncertain 等用户处置。
        // 幂等键在发送前一次性生成，写进回执并随请求发出：服务端凭它保证同一句话只执行
        // 一次，重发不会变成两遍。
        val idemKey = UUID.randomUUID().toString().replace("-", "")
        val userMsg = Msg(
            "user", text, ts = stamp(),
            images = durableImgs.toList(),
            files = imgs.filter { !it.isImage }.map { it.file.name },
            attachments = durableNames.toList(),
            receipt = Receipt(
                status = if (willQueue) Receipt.QUEUED else Receipt.SENDING,
                rawText = wireText.trim(),
                priorUserCount = prior,
                idemKey = idemKey,
            ),
            quote = quoteSnip,
            idemKey = idemKey,
        )
        r.lastUserMsgId = userMsg.id
        setMsgs(r, r.messages.value + userMsg)
        // 用户自己发了消息：无条件滚到最新一条（哪怕是排队发出、也先让他看见自己的气泡）。
        requestScrollBottom()
        touchSession(if (wasEmpty) text.ifBlank { attachmentLabel(imgs) } else null)
        _pendingImages.value = emptyList()
        _quoteTarget.value = null
        if (willQueue) {
            // 排队：气泡先落下（回执标「排队中」），本轮一结束由 drainQueue 自动发。
            r.queue.add(QueuedSend(wireText, uploadFiles.toList(), userMsg.id, prior, idemKey))
            r.queued.value = r.queue.size
            // 入队即落盘（R05）：队列原来只在内存里，排队期间进程被杀这条就永远发不出去。
            store.saveQueue(sid, r.queue.toList())
            AppLog.log("queue", "入队 sid=" + sid.take(8) + " msg=" + userMsg.id + " 队列=" + r.queue.size)
            return
        }
        startRunWith(a, sid, wireText, uploadFiles.toList(), receiptMsgId = userMsg.id, idemKey = idemKey)
    }

    /**
     * 排空队列：本会话空闲且队列非空时，取出最早的一条发出去。
     * 在每处「本轮结束」（doneOk / failPending / stopSession / 翻历史收尾）后调用。
     */
    private fun drainQueue(sid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.busy.value || r.queue.isEmpty() || r.queuePaused.value) return
        val next = r.queue.removeAt(0)
        r.queued.value = r.queue.size
        // 出队同步落盘（R05）：保持磁盘与内存一致，重开后不会把已发出的又发一遍。
        store.saveQueue(sid, r.queue.toList())
        AppLog.log("queue", "出队发送 sid=" + sid.take(8) + " msg=" + next.msgId + " 剩余=" + r.queue.size)
        r.priorUserCount = next.priorUserCount
        r.pendingSendText = next.text.trim()
        r.recoveryJob?.cancel()
        advanceReceipt(sid, next.msgId, Receipt.SENDING, note = "")
        startRunWith(a, sid, next.text, next.files, receiptMsgId = next.msgId, idemKey = next.idemKey)
    }

    /** 用户点「继续」：解除「停止」对队列的按住，接着发。 */
    fun resumeQueue() {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        try {
            store.saveQueue(sid, r.queue.toList(), paused = false)
        } catch (e: Exception) {
            r.retryNote.value = "队列状态保存失败，暂不发送，请稍后再试"
            AppLog.err("queue", "继续队列落盘失败", e)
            return
        }
        r.queuePaused.value = false
        AppLog.log("queue", "继续队列 sid=" + sid.take(8) + " 待发=" + r.queue.size)
        drainQueue(sid)
    }

    /**
     * 撤回一条还没发出去的排队消息：从队列摘掉，连那条用户气泡一起收掉。
     * 排队中的消息本轮结束会自动发，此前没有任何入口能把它收回来。
     */
    fun cancelQueued(msgId: Long) {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val i = r.queue.indexOfFirst { it.msgId == msgId }
        if (i >= 0) r.queue.removeAt(i)
        r.queued.value = r.queue.size
        AppLog.log("queue", "撤回排队 sid=" + sid.take(8) + " msg=" + msgId + " 剩余=" + r.queue.size)
        removeUserMsg(r, msgId)
    }

    /**
     * 编辑一条排队消息：从队列摘掉、收掉原气泡，正文回填到输入框由用户改完重发。
     * 附件不回填（待发区依赖 uri，还原不了），需要就重新选。
     */
    fun editQueued(msgId: Long) {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val i = r.queue.indexOfFirst { it.msgId == msgId }
        if (i < 0) return
        val q = r.queue.removeAt(i)
        r.queued.value = r.queue.size
        AppLog.log("queue", "编辑排队 sid=" + sid.take(8) + " msg=" + msgId + " 剩余=" + r.queue.size)
        _queuedEdit.value = q.text
        removeUserMsg(r, msgId)
    }

    /** 编辑回填：输入框取走后清空。 */
    fun clearQueuedEdit() { _queuedEdit.value = null }

    private fun removeUserMsg(r: SessionRuntime, msgId: Long) {
        val list = r.messages.value.toMutableList()
        val k = list.indexOfFirst { it.id == msgId }
        if (k >= 0) { list.removeAt(k); setMsgs(r, list) }
    }

    /**
     * 中途插话（steer）：把这句话注入当前正在跑的这一轮，服务端 agent 会读到。
     * 与「排队」不同——排队是下一轮才发，插话是立刻影响本轮方向。
     */
    fun steerCurrent(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val a = api ?: return
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        // 插话要在聊天界面看得见：直接插一条用户气泡（标「插话」）。
        // 顺带给它一个投递回执：插话失败时才有地方如实标注，否则气泡看着像发出去了（F13）。
        val steerMsg = Msg("user", t, pending = false, ts = stamp(), steer = true,
            receipt = Receipt(Receipt.SENDING))
        run {
            val list = r.messages.value.toMutableList()
            list.add(steerMsg)
            setMsgs(r, list)
        }
        // 插话结果要可见：200 才算送进本轮；409/其它说明本轮已收尾、这句没赶上。
        // 原来 runCatching 把返回整个吞了，用户看不到任何反应，才觉得「插不进去」。
        identityScope.launch(Dispatchers.IO) {
            val ok = a.steer(rid, t)
            AppLog.log("steer", "插话 run=" + rid.take(12) + " 结果=" + (if (ok) "送达" else "未送达") + " len=" + t.length)
            withContext(Dispatchers.Main) {
                if (ok) {
                    advanceReceipt(sid, steerMsg.id, Receipt.ACCEPTED, note = "")
                    r.retryNote.value = "插话已送达，本轮会读到"
                } else {
                    // 没送达就标在这条气泡自己身上：光在顶部闪一行字，气泡本身看着还是
                    // 「发出去了」，用户会以为这句话进了本轮（F13）。
                    advanceReceipt(sid, steerMsg.id, Receipt.STEER_FAILED,
                        note = "插话没赶上下一次工具调用，本轮可能已收尾")
                    r.retryNote.value = "插话没送达：本轮可能已收尾，这句话没赶上"
                }
            }
        }
    }

    /** 把某条用户消息的投递状态推到下一档，其余字段保留。 */
    private fun advanceReceipt(
        sid: String,
        msgId: Long,
        status: String,
        runId: String? = null,
        note: String = "",
        idemKey: String? = null,
        artifactIds: List<String>? = null,
    ) {
        if (msgId <= 0) return
        val r = rt(sid)
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val old = list[i].receipt
        AppLog.log("receipt", "回执 " + (old?.status ?: "无") + " -> " + status +
            " sid=" + sid.take(8) + " msg=" + msgId + (if (note.isNotEmpty()) " note=" + note.take(60) else ""))
        list[i] = list[i].copy(
            receipt = Receipt(
                status = status,
                runId = runId ?: old?.runId.orEmpty(),
                note = note,
                rawText = old?.rawText ?: list[i].text.trim(),
                priorUserCount = old?.priorUserCount ?: -1,
                // 幂等键与附件 id 一旦定下就不再变：传入才覆盖，否则沿用旧的。
                idemKey = idemKey ?: old?.idemKey.orEmpty(),
                artifactIds = artifactIds ?: old?.artifactIds ?: emptyList(),
            )
        )
        setMsgs(r, list)
    }

    /** 发送明确失败时把那个空气泡收掉，别在界面留一个空壳。 */
    private fun dropEmptyPending(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) return
        val m = list[i]
        if (m.text.isEmpty() && m.trace.isEmpty() && m.approval == null && m.clarify == null &&
            m.usage == null && m.subagents.isEmpty() && m.recall.isEmpty()
        ) {
            list.removeAt(i)
            setMsgs(r, list)
        }
    }

    // ---------- 投递回执：服务端到底收下这条没有 ----------

    private val _receiptMenu = MutableStateFlow(0L)
    /** 当前展开处置按钮的用户消息 id；0 表示没有。 */
    val receiptMenu = _receiptMenu.asStateFlow()

    fun openReceiptMenu(msgId: Long) {
        _receiptMenu.value = if (_receiptMenu.value == msgId) 0L else msgId
    }

    /**
     * 「知道了，不重发」：把「不确定」标成已确认忽略。
     *
     * 原来这条消息卡在黄问号时只有「点这里处理」，用户想收掉角标就只能选「重新发送」——
     * 等于拿一句话去赌会不会发两遍。现在确认收到即可，角标与提示一起收起，不动网络。
     */
    fun acknowledgeReceipt(msgId: Long) {
        val sid = _currentId.value
        _receiptMenu.value = 0L
        advanceReceipt(sid, msgId, Receipt.ACKED, note = "")
    }

    /**
     * 「确认送达」：去服务端会话记录里按位置锚点 + 正文核对这条消息在不在。
     * 在 → 标已送达并接着把答案等回来（复用断流翻历史那套轮询）；
     * 不在 → 标失败，提示可重发。认不出锚点就报失败，绝不硬认。
     */
    fun confirmReceipt(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        // 轮次守卫（R21）：确认一条**旧**回执不能接管正在跑的新轮。旧代码进来就把
        // busy/finished/runId/pendingSendText 全覆盖并起历史恢复，B 跑到一半被 A 的确认
        // 顶掉，随后 adoptRecovered 还会清掉 B 的 runId 并放行队列、提前发 C。
        if (r.busy.value) {
            r.retryNote.value = "本会话还在跑另一轮，等它结束再确认这条"
            return
        }
        val genAtEntry = r.sendGen
        val list = r.messages.value
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val rc = list[i].receipt ?: return
        _receiptMenu.value = 0L
        identityScope.launch(Dispatchers.IO) {
            // 「没查到」和「查询失败」必须分开（F21）：网络断了、或历史接口出错时，
            // 旧代码把异常吞成空列表，于是直接宣告「服务端记录里没有这条消息」——
            // 那是把「不知道」说成了「确定没有」，会诱导用户重发一条其实已送达的话。
            val rows = try {
                historyRows(a, sid)
            } catch (_: Exception) {
                null
            }
            if (rows == null) {
                advanceReceipt(sid, msgId, Receipt.UNCERTAIN, note = "查询失败（网络问题），稍后再试")
                return@launch
            }
            // 查询往返期间可能已经开了新轮：这份确认针对的是旧状态，只更新回执，不碰会话（R21）。
            if (r.busy.value || r.sendGen != genAtEntry) {
                advanceReceipt(sid, msgId, Receipt.UNCERTAIN, note = "确认期间本会话开始了新的一轮，稍后再确认")
                return@launch
            }
            // 没有原正文就没法核对（例如插话重启后的回执，rawText 读回是空的）：
            // 这种只能给「不确定」，不能拿位置锚点去猜（R15）。
            if (rc.rawText.isBlank() || rc.priorUserCount < 0) {
                advanceReceipt(sid, msgId, Receipt.UNCERTAIN, note = "这条没有可核对的原正文（如插话），无法定位，稍后可在服务端核对")
                return@launch
            }
            val anchor = if (rows.isEmpty()) -1 else resolveAnchor(rows, rc.rawText, rc.priorUserCount)
            if (anchor < 0) {
                // 锚点对不上有两种原因：① 本地被 300 条窗口裁过，prior 是**窗口内**的相对位置、
                // 与服务端全历史对不上（R06，探针实测 local-prior=150 而真实位置 602）；
                // ② 这条确实没落到服务端。分不出来时给「不确定」，
                // 绝不武断宣称「服务端没有这条」去诱导用户重发（那句话会把已送达的说成没送到）。
                val capped = r.messages.value.size >= maxHistory
                if (capped) {
                    advanceReceipt(sid, msgId, Receipt.UNCERTAIN,
                        note = "本地历史已超出显示窗口，位置对不上，无法确认（未判定为未送达）")
                } else {
                    advanceReceipt(sid, msgId, Receipt.FAILED, note = "服务端记录里没有这条消息，可重新发送")
                }
                return@launch
            }
            advanceReceipt(sid, msgId, Receipt.ACCEPTED, note = "")
            r.pendingSendText = rc.rawText
            r.priorUserCount = rc.priorUserCount
            r.confirmingMsgId = msgId
            r.busy.value = true
            r.finished = false
            r.retryNote.value = "已确认送达，正在取回结果…"
            setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r)))
            updateRunService()
            startHistoryRecovery(sid, "")
        }
    }

    /**
     * 「重新发送」：拿原正文再 POST 一次（图片附件能找回就一并带上）。
     *
     * 复用首次发送时的幂等键与附件 id：服务端凭键认出这是同一条，24 小时内只执行
     * 一次，重发不会变成发两遍；若首次其实已经收下，服务端会把**原来那轮的 run_id**
     * 还回来，这里直接接上那一轮取结果。这就是「不确定」不再需要用户赌一把的原因。
     */
    fun resendReceipt(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        val list = r.messages.value
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val m = list[i]
        val rc = m.receipt ?: return
        _receiptMenu.value = 0L
        if (r.busy.value) {
            r.retryNote.value = "本会话还在跑，等这轮结束再重发"
            return
        }
        r.priorUserCount = list.take(i).count { it.role == "user" }
        r.pendingSendText = rc.rawText
        r.recoveryJob?.cancel()
        // 按「原始附件清单」重建这一轮的附件，顺序与首次一致（F07）。
        // 拼不出原载荷就明确拒绝同键重发——宁可让用户重发一条，也不要发重或发漏。
        val sentDir = File(getApplication<Application>().filesDir, "sent")
        val files = mutableListOf<File>()
        var missing = 0
        if (m.attachments.isNotEmpty()) {
            for (n in m.attachments) {
                val f = File(sentDir, n)
                if (f.exists()) files.add(f) else missing++
            }
        } else {
            // 老消息没有这份清单：只能按图片重建；非图片附件在旧版本没留档。
            for (u in m.images) {
                val f = runCatching { File(Uri.parse(u).path ?: "") }.getOrNull()
                if (f != null && f.exists()) files.add(f) else missing++
            }
            missing += m.files.size
        }
        if (missing > 0) {
            r.retryNote.value = "原附件已不在本机，无法原样重发；请把这条重新发一次（这样不会发重、也不会发漏）"
            return
        }
        // 附件已有 artifact id 就复用，不再重传（重传会换 id、指纹不符会被判冲突）。
        val reuse = rc.artifactIds
        advanceReceipt(sid, msgId, Receipt.SENDING, note = "")
        startRunWith(
            a, sid, rc.rawText, files,
            receiptMsgId = msgId,
            idemKey = rc.idemKey,
            reuseArtifacts = reuse,
        )
    }

    /**
     * 把待发图片从 outbox 挪进「已发送」目录，返回 (挪过去的文件, 气泡要存的 file:// 地址)。
     *
     * 为什么要把文件也返回来：挪动之后上传必须用新路径。老实现只返回地址、却把 outbox
     * 原文件删了，调用方随后仍拿 p.file 去读字节 → 文件不存在，附件发送必然失败。
     * 落盘失败时退回原文件与原地址（至少本次还能发、还能看），不阻断发送。
     */
    private fun persistOutgoing(p: PendingImage): Pair<File, String> {
        val app = getApplication<Application>()
        return try {
            val dir = File(app.filesDir, "sent").apply { mkdirs() }
            val dst = File(dir, p.file.name)
            if (!dst.exists()) p.file.copyTo(dst, overwrite = true)
            if (p.file.absolutePath != dst.absolutePath) p.file.delete()
            dst to android.net.Uri.fromFile(dst).toString()
        } catch (e: Exception) {
            AppLog.err("attach", "落盘失败，退回原文件 name=" + p.file.name, e)
            p.file to p.uri
        }
    }

    /** 把选中的图片拷进 App 沙盒，加入待发列表。 */
    fun addImage(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        identityScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 20L * 1024 * 1024) {
                    _imageNote.value = "图片超过 20MB：" + name
                    AppLog.log("attach", "选图失败：超过 20MB name=" + name + " size=" + size + "B")
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
                    AppLog.log("attach", "选图失败：读不到内容 uri=" + uri)
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    AppLog.log("attach", "选图失败：已达上限 10 个 name=" + name)
                    dst.delete()
                    return@launch
                }
                // 批量总量也要卡（F20）：单文件 20MB × 10 个仍是 200MB，发送时全都会读进内存。
                // 求和用**已拷贝文件的实际长度**，不只看 provider 声明的大小。
                if (exceedsAttachBudget(_pendingImages.value.sumOf { it.file.length() }, dst.length())) {
                    _imageNote.value = attachBudgetText()
                    AppLog.log("attach", "选图失败：合计超预算 name=" + name + " 本文件=" + dst.length() + "B")
                    dst.delete()
                    return@launch
                }
                _imageNote.value = ""
                AppLog.log("attach", "已选图 name=" + name + " size=" + size + "B -> " + dst.name)
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = true)
            } catch (e: Exception) {
                AppLog.err("attach", "读取图片失败 uri=" + uri, e)
                _imageNote.value = "读取图片失败：" + (e.message ?: "?")
            }
        }
    }

    /** 把任意文件拷进 App 沙盒，加入待发列表（非图片走文件卡片展示）。 */
    fun addFile(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        identityScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 50L * 1024 * 1024) {
                    _imageNote.value = "文件超过 50MB：" + name
                    AppLog.log("attach", "选文件失败：超过 50MB name=" + name + " size=" + size + "B")
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
                    AppLog.log("attach", "选文件失败：读不到内容 uri=" + uri)
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    AppLog.log("attach", "选文件失败：已达上限 10 个 name=" + name)
                    dst.delete()
                    return@launch
                }
                // 同选图：总量预算（F20）。10 × 50MB 全收下，发送时峰值是原始量的数倍。
                if (exceedsAttachBudget(_pendingImages.value.sumOf { it.file.length() }, dst.length())) {
                    _imageNote.value = attachBudgetText()
                    AppLog.log("attach", "选文件失败：合计超预算 name=" + name + " 本文件=" + dst.length() + "B")
                    dst.delete()
                    return@launch
                }
                val isImg = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                _imageNote.value = ""
                AppLog.log("attach", "已选文件 name=" + name + " size=" + size + "B ext=" + ext + " -> " + dst.name)
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = isImg)
            } catch (e: Exception) {
                AppLog.err("attach", "读取文件失败 uri=" + uri, e)
                _imageNote.value = "读取文件失败：" + (e.message ?: "?")
            }
        }
    }

    fun removeImage(id: Long) {
        val list = _pendingImages.value
        val gone = list.firstOrNull { it.id == id }
        gone?.file?.delete()
        _pendingImages.value = list.filter { it.id != id }
        AppLog.log("attach", "移除附件 name=" + (gone?.file?.name ?: "?") + " 剩余=" + _pendingImages.value.size)
    }


    private fun startRunWith(
        a: HermesApi,
        sid: String,
        text: String,
        files: List<File> = emptyList(),
        receiptMsgId: Long = 0L,
        idemKey: String = "",
        reuseArtifacts: List<String> = emptyList(),
        autoRetryLeft: Int = 1,
    ) {
        val r = rt(sid)
        // 本轮轮次号：这次发送的「身份证」。之后任何共享状态的写入都要先对号
        // （见 ownsTurn 与 RuntimeHub.SessionRuntime.sendGen）：迟到的旧轮不得接管会话（F04）。
        val gen = ++r.sendGen
        // 上一轮的收尾元数据到此为止：不许挂到本轮的新气泡上（见 withTurnMeta）。
        r.turnUsage = null
        r.turnRunId = ""
        r.turnTrace = ""
        // 本轮计时起点先定好：气泡与最终耗时都锚在它上面，中途气泡被重建也不会漂。
        r.startedAt = stamp()
        setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp(), startedAt = r.startedAt))
        r.busy.value = true
        r.finished = false
        r.stopRequested = false
        // 新轮有自己的停止语义：上一轮「未确认的停止」不再挂着（R08）。
        r.stopIntentRid = ""
        r.lastSeq = -1
        r.autoContinue = 0
        r.resumed = false
        updateRunService()
        if (files.isNotEmpty()) {
            AppLog.log("attach", "本轮附件 " + files.size + " 个: " + files.joinToString(", ") { it.name + "(" + it.length() + "B)" })
        }
        val ids = mutableListOf<String>()
        var uploadsDone = reuseArtifacts.isNotEmpty()
        r.sendJob = identityScope.launch(Dispatchers.IO) {
            try {
                // 起流前先对号：用户已点停止、或又发了新的一轮，本轮直接作废（不发、不写状态）。
                if (!ownsTurn(gen, r.sendGen, r.finished, r.stopRequested)) return@launch
                // 附件 id：重发必须复用首次那份（服务端算指纹含请求体，换了 id 会被判冲突）。
                // Hermes 本地补丁：不再单独 POST /v1/artifacts/upload（实测该接口被边缘按请求
                // 形状拦掉、TCP RST，而 /v1/runs 同样带 body 却正常）。改把文件 gzip+base64
                // 塞进 /v1/runs 的 inline_files 一起发。重发时同样内容 gzip 输出确定（Java
                // GZIPOutputStream 时间戳恒为 0），故 body 一致、幂等指纹不变。
                val inlineFiles = mutableListOf<com.hermesapp.net.InlineFile>()
                for ((i, f) in files.withIndex()) {
                    _imageNote.value = "准备附件 ${i + 1}/${files.size}…"
                    // 单文件也走有界读（R16）：批量预算只在**选择时**按当时长度算过，文件之后
                    // 可能被换大；readBytes() 会先整包进堆再判断大小，等于没有边界。
                    val bytes = readFileBounded(f)
                    if (bytes == null) {
                        _imageNote.value = ""
                        AppLog.log("attach", "附件超限拒绝 name=" + f.name + " " + f.length() + "B")
                        r.retryNote.value = "附件过大（" + f.name + "），未发送"
                        dropEmptyPending(r)
                        advanceReceipt(sid, receiptMsgId, Receipt.FAILED, note = "附件过大，已拒绝")
                        failPending(sid)
                        return@launch
                    }
                    AppLog.log("attach", "内联附件 ${i + 1}/${files.size} name=" + f.name + " mime=" + mimeOf(f) + " " + bytes.size + "B")
                    inlineFiles.add(com.hermesapp.net.InlineFile(f.name, mimeOf(f), bytes))
                }
                uploadsDone = true
                if (!ownsTurn(gen, r.sendGen, r.finished, r.stopRequested)) {
                    AppLog.log("send", "停止排除：上传完成但本轮已作废（停止或已开新轮），不起流 gen=" + gen + " cur=" + r.sendGen)
                    return@launch
                }
                _imageNote.value = ""
                val run = a.startRun(text, sid, emptyList(), idemKey, inlineFiles)
                val myRun = run.optString("run_id", run.optString("id", ""))
                if (detached) {
                    // The POST may complete after leaving this identity. Keep recovery, never stop it.
                    if (myRun.isNotEmpty()) prefs.putActiveRun(sid, myRun)
                    return@launch
                }
                AppLog.log("send", "已建 run=" + myRun.take(12) + " 重放=" + run.optBoolean("replayed", false) + " 内联附件=" + inlineFiles.size)
                // 归属校验必须在写任何共享状态之前：startRun 是同步 HTTP、取消协程拦不住它，
                // 请求已经发出去、服务端这条 run 可能已建好。但这期间用户可能点过停止、或已经
                // 发了下一轮——那时 r.sendGen 已推进，本轮号对不上，绝不能把 myRun 写进
                // r.runId / activeRun、更不能接流（否则会顶掉新轮的 runId、两条流抢同一会话，F04）。
                // 过时的一轮只需用自己那条 runId 补一次 stop 把它收掉即可。
                // 也正因如此这里不再调 finishPending：那个气泡此刻很可能已属于新的一轮。
                if (!ownsTurn(gen, r.sendGen, r.finished, r.stopRequested)) {
                    AppLog.log("stop", "本轮已被取代，补发 stopRun run=" + myRun.take(12) +
                        " gen=" + gen + " cur=" + r.sendGen + " finished=" + r.finished +
                        " stopRequested=" + r.stopRequested)
                    if (myRun.isNotEmpty()) a.stopRun(myRun)
                    return@launch
                }
                r.runId = myRun
                // 计时起点不在这里重设：上传附件+建 run 的往返也算本轮耗时，
                // 重设会把这一段抹掉，最终值比界面实时值小一截。
                prefs.putActiveRun(sid, myRun)
                prefs.putLastSeq(sid, -1)   // 新 run 从 0 开始，清掉上一轮的续接序号
                // 拿到 run_id 才算「服务端已收下」，此时回执才转已送达。
                // 幂等键与附件 id 一并落进回执，供后续重发原样复用。
                advanceReceipt(sid, receiptMsgId, Receipt.ACCEPTED, runId = myRun,
                    idemKey = idemKey, artifactIds = ids)
                streamRun(a, sid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch   // 用户停止主动取消，不算发送失败
            } catch (e: Exception) {
                val msg = e.message ?: "?"
                // 「服务端明确拒绝」只认 4xx（R09）。5xx 可能发生在后台**已经接受**之后
                // （实测网关 500 也可能任务已入库），单凭状态码断言「没执行」会把已收下的
                // 说成没送到；5xx 走「不确定」并允许带幂等键的安全重试。
                val httpCode = Regex("^HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                val httpReject = httpCode != null && httpCode in 400..499
                // 归属校验放在最前（R01）：这条异常可能来自已被取代的旧轮
                // （Stop A → 发 B → A 迟到返回 500）。旧代码在这里无脑 dropEmptyPending +
                // failPending，把 B 的 busy/runId/active 全清掉（探针实测 busy=false、runId=、
                // active={}、drains=2）。旧轮只许更新**自己那条**回执，不许碰会话状态、
                // 不许出队、不许起重试。
                if (!ownsTurn(gen, r.sendGen, r.finished, r.stopRequested)) {
                    AppLog.log("send", "旧轮异常迟到，只更新自己的回执（不碰会话）gen=" + gen +
                        " cur=" + r.sendGen + " 内容=" + msg.take(140))
                    if (httpReject) advanceReceipt(sid, receiptMsgId, Receipt.FAILED, note = HttpNote.friendly(msg))
                    else advanceReceipt(sid, receiptMsgId, Receipt.UNCERTAIN, note = HttpNote.friendly(msg))
                    return@launch
                }
                _imageNote.value = ""
                AppLog.err("send", "发送失败 服务端拒绝=" + httpReject + " 内容=" + msg.take(200), e)
                // 网络中断（收不到回执）、带幂等键、且还没重试过 → 自动安全重试一次。
                // 为什么现在敢自动重试：服务端对同一个键只执行一次，重试要么接上原来那轮
                // （首次其实已收下，只是回包丢了），要么全新执行一次，绝不会变成发两遍。
                // 这正是加幂等键最大的收益——「不确定」不再需要用户赌一把。
                if (!httpReject && idemKey.isNotEmpty() && autoRetryLeft > 0) {
                    dropEmptyPending(r)
                    r.retryNote.value = "发送未确认，正在自动重试…"
                    delay(3500)
                    // 附件已传完就复用那份 id（保证指纹一致、能命中重放）；
                    // 上传阶段就断了则重新上传（此时服务端没记下任何键，不会冲突）。
                    startRunWith(
                        a, sid, text, files, receiptMsgId,
                        idemKey = idemKey,
                        reuseArtifacts = if (uploadsDone) ids.toList() else emptyList(),
                        autoRetryLeft = autoRetryLeft - 1,
                    )
                    return@launch
                }
                // 服务端有明确回执（HTTP 4xx/5xx）= 拒绝，标失败；
                // 重试用尽仍收不到回执 = 标「不确定」等用户处置（此时仍可手动重发，也安全）。
                dropEmptyPending(r)
                if (httpReject) {
                    advanceReceipt(sid, receiptMsgId, Receipt.FAILED, note = HttpNote.friendly(msg))
                } else {
                    // 5xx / 超时 / 网络中断：服务端可能已收下，一律按「不确定」等用户处置（R09）。
                    r.retryNote.value = if (httpCode != null)
                        "服务端返回 " + httpCode + "，结果不确定：可能已收下，可重发（不会重复）"
                    else
                        "发送结果不确定：网络中断，服务端可能已收下，可重发（不会重复）"
                    advanceReceipt(sid, receiptMsgId, Receipt.UNCERTAIN, note = HttpNote.friendly(msg))
                }
                failPending(sid)
            }
        }
    }

    /** 澄清请求：挂到该会话当前助手气泡上，等用户选选项回执。 */
    private fun attachClarify(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        AppLog.log("clarify", "收到澄清请求 sid=" + r.id.take(8) + " id=" + ev.data.optString("clarify_id", "").take(12))
        notifyNeedAction(r.id, "需要你选一下", ev.data.optString("question", ""))
        placeClarify(r, ev.data)
    }

    /**
     * 把澄清卡片挂到该会话。挂载位置按「保守」原则：优先当前进行中的助手气泡
     * （pending），没有就挂最后一条助手消息（重开 App 后本地消息的 pending 已归
     * false），都没有才新建。同一 clarify_id 已挂且未作废的不重复挂。
     */
    private fun placeClarify(r: SessionRuntime, data: org.json.JSONObject) {
        val cid = data.optString("clarify_id", "")
        val q = data.optString("question", "")
        if (cid.isEmpty() || q.isEmpty()) return
        val chs = mutableListOf<String>()
        data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        val cur = r.messages.value
        if (cur.any { it.clarify?.clarifyId == cid && it.clarify?.resolved?.isEmpty() == true }) return
        val card = ClarifyCard(cid, q, chs, data.optBoolean("multi_select", false))
        val list = cur.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(clarify = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), clarify = card))
        setMsgs(r, list)
    }

    /** 用户点了澄清选项：回执给服务端，并把卡片置为已选。 */
    fun respondClarify(msgId: Long, choice: String) {
        val a = api ?: return
        val r = rt(_currentId.value)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].clarify ?: return
        if (card.resolved.isNotEmpty()) return
        // 发成功才标「已选择」：先发请求，成功才把卡片定下来；失败就保持按钮可点，
        // 提示里的「可重试」才真的能重试（原来先置已选、再发，失败后界面已无入口）。
        if (!receiptInFlight.add(msgId)) return
        r.retryNote.value = "正在送达回执…"
        identityScope.launch(Dispatchers.IO) {
            val code = a.respondClarify(rid, card.clarifyId, choice)
            withContext(Dispatchers.Main) {
                receiptInFlight.remove(msgId)
                when {
                    code in 200..299 -> {
                        val cur = r.messages.value.toMutableList()
                        val k = cur.indexOfFirst { it.id == msgId }
                        if (k >= 0) {
                            val cc = cur[k].clarify
                            if (cc != null && cc.resolved.isEmpty()) {
                                cur[k] = cur[k].copy(clarify = cc.copy(resolved = choice))
                                setMsgs(r, cur)
                            }
                        }
                        // 同上：澄清回执的确认也只闪一下。
                        flashNote(r, "已选择「" + choice + "」，已送达服务端")
                    }
                    receiptGone(code) -> {
                        expireCard(r, msgId, false)
                        flashNote(r, "这条选择已失效（本轮已结束或已被处理），已收起")
                    }
                    else -> r.retryNote.value = "回执没送到：本轮可能已收尾，可重试"
                }
            }
        }
    }

    /** 审批请求：挂到该会话当前助手气泡上，等用户点按钮回执。 */
    private fun attachApproval(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        AppLog.log("approval", "收到审批请求 sid=" + r.id.take(8) + " id=" + ev.data.optString("request_id", "").take(12))
        // 通知正文也用中文：服务端 description 是英文 pattern key（如 recursive delete），
        // 只在展示层翻（见 ApprovalZh.kt），不改回执、不改落盘。
        notifyNeedAction(r.id, "需要你确认",
            approvalDescZh(ev.data.optString("description", "").ifEmpty { ev.data.optString("command", "") }))
        placeApproval(r, ev.data)
    }

    /** 把审批卡片挂到该会话。位置与去重规则同 [placeClarify]。 */
    private fun placeApproval(r: SessionRuntime, data: org.json.JSONObject) {
        val rid = data.optString("request_id", "")
        val cmd = data.optString("command", "")
        val desc = data.optString("description", "")
        val chs = mutableListOf<String>()
        data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        if (rid.isEmpty() || chs.isEmpty()) return
        val cur = r.messages.value
        if (cur.any { it.approval?.requestId == rid && it.approval?.resolved?.isEmpty() == true }) return
        // 「本次任务全部批准」开着且就是本轮：不弹卡片等人点，直接回执「允许一次」，
        // 但**留一张灰卡片**说明这条被自动批了（留痕，不是静默放行）。
        val armed = autoApproveRun[r.id]
        if (armed != null) {
            if (r.runId.isEmpty() || armed != r.runId) {
                // 换轮了（或本轮已收尾）：本次任务的口径到此为止，关掉，恢复逐个询问。
                disarmAutoApprove(r.id)
            } else if (cur.none { it.approval?.requestId == rid }) {
                hangApprovalCard(r, ApprovalCard(rid, cmd, desc, chs, resolved = APPROVAL_AUTO))
                autoApproveReply(r, rid)
                return
            }
        }
        hangApprovalCard(r, ApprovalCard(rid, cmd, desc, chs))
    }

    /** 卡片落位：挂到当前助手气泡上，没有就新起一条（位置与去重规则同 [placeClarify]）。 */
    private fun hangApprovalCard(r: SessionRuntime, card: ApprovalCard) {
        val list = r.messages.value.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(approval = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), approval = card))
        setMsgs(r, list)
    }

    /**
     * 「本次任务全部批准」下的自动回执：直接回 choice=once。
     *
     * 为什么是 once 而不是 session/always：once 不落任何白名单——本次任务的放行决定只活在
     * App 这一侧（`autoApproveRun`），服务端不知道有这回事，所以不会留下跨会话/永久的后门，
     * 也不会把 pattern 写进 approvals.allow。
     *
     * 回执没送到（网络/5xx）就把那张留痕卡片改回「等你点」，**绝不装成已批准**；
     * 服务端说 404/409（本轮已收尾）就标失效。
     */
    private fun autoApproveReply(r: SessionRuntime, requestId: String) {
        val a = api ?: return
        val rid = r.runId
        if (rid.isEmpty()) return
        val msgId = r.messages.value.lastOrNull { it.approval?.requestId == requestId }?.id ?: return
        AppLog.log("approval", "自动批准 sid=" + r.id.take(8) + " id=" + requestId.take(12))
        identityScope.launch(Dispatchers.IO) {
            val code = a.respondApproval(rid, requestId, "once")
            if (code in 200..299) return@launch
            withContext(Dispatchers.Main) {
                val cur = r.messages.value.toMutableList()
                val k = cur.indexOfFirst { it.id == msgId }
                if (k < 0) return@withContext
                val cc = cur[k].approval ?: return@withContext
                if (cc.resolved != APPROVAL_AUTO) return@withContext
                val gone = receiptGone(code)
                cur[k] = cur[k].copy(approval = if (gone) cc.copy(expired = true) else cc.copy(resolved = ""))
                setMsgs(r, cur)
                if (!gone) flashNote(r, "自动批准没送出去，这条改回等你点")
            }
        }
    }

    /**
     * 重开 App / 断流恢复：服务端 run 状态里若仍挂着待办卡片（waiting_for_clarify /
     * waiting_for_approval），把卡片重新挂回会话。
     *
     * 这就是「重启后等你点头的卡片消失」的修法：老代码探测 run 只读了 status
     * 字符串，把 run 状态里一并下发的 clarify/approval 载荷丢掉，卡片自然挂不上。
     */
    private fun restorePendingCard(r: SessionRuntime, payload: org.json.JSONObject?) {
        val p = payload ?: return
        when (p.optString("status", "")) {
            "waiting_for_clarify" -> placeClarify(r, p.optJSONObject("clarify") ?: return)
            "waiting_for_approval" -> placeApproval(r, p.optJSONObject("approval") ?: return)
            else -> {
                // 服务端这条 run 没有待审批/待澄清：本地落盘读回、还标着「未处理」的卡片就是过期的。
                //
                // 为什么必须在这里收：审批在服务端是**静默失败关闭**——到点（approvals.timeout）
                // gate 自己放行、run 继续跑，服务端既不会推事件也不会改 run 状态，客户端那张卡片
                // 就成了「点确认/拒绝都 409」的死卡片（2026-10-11 实测 run_ff9eddc7：run 还在
                // running、last_event=tool.completed，approval 早已不在等）。这一条把「run 探活
                // 时 payload 里没有 approval」直接判成卡片失效，不用等用户去点。
                expireAllPendingCards(r, "服务端 payload 无待办（status=" + p.optString("status", "") + "）")
            }
        }
    }

    /**
     * 把该会话里所有「未处理」的卡片一次性标失效（不删，留痕）。
     *
     * 用在三类确知「这条待办已经没人能回答了」的时刻：服务端 payload 说没有待办、用户点了停止、
     * 会话空闲且服务端没有在跑的 run。绝不用在「不确定」的场合——不确定就保持可点。
     */
    private fun expireAllPendingCards(r: SessionRuntime, why: String) {
        val list = r.messages.value.toMutableList()
        var changed = false
        for (k in list.indices) {
            val m = list[k]
            val ap = m.approval
            if (ap != null && ap.resolved.isEmpty() && !ap.expired) {
                list[k] = m.copy(approval = ap.copy(expired = true)); changed = true; continue
            }
            val cl = m.clarify
            if (cl != null && cl.resolved.isEmpty() && !cl.expired) {
                list[k] = m.copy(clarify = cl.copy(expired = true)); changed = true
            }
        }
        if (changed) {
            setMsgs(r, list)
            AppLog.log("approval", "过期待办卡片已收起 sid=" + r.id.take(8) + " 原因=" + why)
        }
    }

    /**
     * 单条卡片标失效（回执收到 404/409、或用户点「忽略」）。
     * 返回是否真的改了（没改就不写盘，避免无意义的重组与落盘）。
     */
    private fun expireCard(r: SessionRuntime, msgId: Long, isApproval: Boolean) {
        val cur = r.messages.value.toMutableList()
        val k = cur.indexOfFirst { it.id == msgId }
        if (k < 0) return
        val m = cur[k]
        val changed = if (isApproval) {
            val c = m.approval ?: return
            if (c.expired) false else { cur[k] = m.copy(approval = c.copy(expired = true)); true }
        } else {
            val c = m.clarify ?: return
            if (c.expired) false else { cur[k] = m.copy(clarify = c.copy(expired = true)); true }
        }
        if (changed) setMsgs(r, cur)
    }

    /**
     * 用户点「忽略」：不再提示这条（不问服务端）。
     *
     * 语义要说清：本轮该问的还是要问——服务端那条审批/澄清会照它自己的超时（或让 agent 继续）
     * 处理，这里只是把「客户端这条提示」收掉。可点是因为：用户可能根本不想回、或者已经错过
     * 时机，而旧版没有任何出口（确认/拒绝都 409，提示条下不去）。
     */
    fun ignoreCard(msgId: Long, isApproval: Boolean) {
        val r = rt(_currentId.value)
        expireCard(r, msgId, isApproval)
        flashNote(r, if (isApproval) "已收起这条审批（本轮仍按超时处理）" else "已收起这条选择（本轮仍按超时处理）")
        AppLog.log("approval", "用户忽略卡片 msgId=" + msgId + " 审批=" + isApproval)
    }

    /**
     * 会话空闲时收起过期卡片：本地不忙、没有 runId、服务端也没有在跑的 run（runFlags 无 remote）。
     * 这类卡片只可能来自「本轮早已结束、审批从未被回答」的历史状态，留着就是一条点不动的假提示。
     */
    private fun expireCardsWhenIdle(sid: String) {
        val r = runtimes[sid] ?: return
        if (r.busy.value || r.runId.isNotEmpty()) return
        val f = runFlags.value[sid]
        if (f != null && (f.remote || f.subagents > 0 || f.queued > 0)) return
        expireAllPendingCards(r, "会话空闲且服务端无在跑 run")
    }

    /**
     * run 已结束或不存在：清掉该会话里未作废的待办卡片（多为重启后从本地落盘读回的
     * 过期卡片）。已作废（用户点过）的保留，让「已选择：…」留痕。
     */
    private fun clearStaleCards(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        var changed = false
        for (k in list.indices) {
            val m = list[k]
            if (m.approval?.resolved?.isEmpty() == true) {
                list[k] = m.copy(approval = null); changed = true
            } else if (m.clarify?.resolved?.isEmpty() == true) {
                list[k] = m.copy(clarify = null); changed = true
            }
        }
        if (changed) setMsgs(r, list)
    }

    /**
     * 短暂提示：写进 retryNote，几秒后自动清掉（只在没人改写它时才清）。
     *
     * 为什么需要：审批/澄清回执成功后的「已选择…已送达服务端」原来一直挂在 retryNote 上，
     * 而 retryNote 只在轮末 doneOk 里清空——长任务一跑几分钟，那行确认就一直钉在消息列表
     * 顶部，看着像还有事没处理（用户 2026-10-08 实测报障：审批后任务跑了许久，顶部提示还在）。
     * 确认类提示是「一闪而过」的信息，不该占着顶部警告条；连接类提示（重试/重连）仍常驻，
     * 直到状态真的恢复。
     */
    private fun flashNote(r: SessionRuntime, text: String, ms: Long = 4000L) {
        r.retryNote.value = text
        val sid = r.id
        identityScope.launch(Dispatchers.Main) {
            delay(ms)
            val cur = runtimes[sid] ?: return@launch
            if (cur.retryNote.value == text) cur.retryNote.value = ""
        }
    }

    /**
     * 「本次任务全部批准」的武装状态：sid → 被批准的 runId。
     *
     * 为什么要绑 runId 而不是只记一个布尔：口径是**本次任务**（这一轮）。绑上 runId 后，
     * 哪怕哪条收尾路径漏了清理，下一轮（新 runId）也不会被自动放行——安全默认。
     * 内存态、不落盘：App 重启即失效，用户重新点一次即可。
     */
    private val autoApproveRun = ConcurrentHashMap<String, String>()

    /**
     * 界面提示条用：哪些会话当前处于「本次任务全部批准」。
     * StateFlow 只为触发重组，真实判据在 [autoApproveRun]。
     */
    private val _autoApproveActive = MutableStateFlow<Set<String>>(emptySet())
    val autoApproveActive: StateFlow<Set<String>> = _autoApproveActive.asStateFlow()

    private fun armAutoApprove(sid: String, rid: String) {
        autoApproveRun[sid] = rid
        _autoApproveActive.value = autoApproveRun.keys.toSet()
        AppLog.log("approval", "本次任务全部批准已开 sid=" + sid.take(8) + " run=" + rid.take(12))
    }

    /**
     * 关掉「本次任务全部批准」。三处会调：用户点「恢复逐个询问」、换轮（runId 变了）、本轮收尾。
     * 没武装过时是空操作，不会重复提示。
     */
    fun disarmAutoApprove(sid: String = _currentId.value, notify: Boolean = false) {
        if (autoApproveRun.remove(sid) == null) return
        _autoApproveActive.value = autoApproveRun.keys.toSet()
        AppLog.log("approval", "本次任务全部批准已关 sid=" + sid.take(8))
        if (notify) flashNote(rt(sid), "已恢复逐个询问：后续敏感操作会再问你")
    }

    /**
     * 卡片上的「全部批准」：① 本条 + 当前还挂着的审批一起解掉（服务端 `all`），
     * ② 武装本轮——本轮后续的审批请求直接在客户端回执「允许一次」，不再弹卡片等人点，
     * 但**每条都会在会话里留一张灰卡片**（`resolved="auto"`），不是静默放行。
     *
     * 口径只到本轮结束：doneOk/failPending/用户点停止都会关掉，下一轮重新问。
     */
    fun approveAllForTask(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].approval ?: return
        if (card.resolved.isNotEmpty()) return
        if (!receiptInFlight.add(msgId)) return
        r.retryNote.value = "正在送达回执…"
        identityScope.launch(Dispatchers.IO) {
            val code = a.respondApproval(rid, card.requestId, "once", resolveAll = true)
            withContext(Dispatchers.Main) {
                receiptInFlight.remove(msgId)
                when {
                    code in 200..299 -> {
                        armAutoApprove(sid, rid)
                        val cur = r.messages.value.toMutableList()
                        val k = cur.indexOfFirst { it.id == msgId }
                        if (k >= 0) {
                            val cc = cur[k].approval
                            if (cc != null && cc.resolved.isEmpty()) {
                                cur[k] = cur[k].copy(approval = cc.copy(resolved = "all"))
                                setMsgs(r, cur)
                            }
                        }
                        flashNote(r, "本次任务已全部批准：本轮后续敏感操作不再问你")
                    }
                    receiptGone(code) -> {
                        expireCard(r, msgId, true)
                        flashNote(r, "这条审批已失效（本轮已结束或已被处理），已收起")
                    }
                    else -> r.retryNote.value = "回执没送到：本轮可能已收尾，可重试"
                }
            }
        }
    }

    /** 用户点了审批按钮：回执给服务端，并把卡片置为已选。 */
    fun respondApproval(msgId: Long, choice: String) {
        val a = api ?: return
        val r = rt(_currentId.value)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].approval ?: return
        if (card.resolved.isNotEmpty()) return
        // 同 respondClarify：发成功才标「已选择」，失败保持可点，让提示里的重试有入口。
        if (!receiptInFlight.add(msgId)) return
        r.retryNote.value = "正在送达回执…"
        identityScope.launch(Dispatchers.IO) {
            val code = a.respondApproval(rid, card.requestId, choice)
            withContext(Dispatchers.Main) {
                receiptInFlight.remove(msgId)
                when {
                    code in 200..299 -> {
                        val cur = r.messages.value.toMutableList()
                        val k = cur.indexOfFirst { it.id == msgId }
                        if (k >= 0) {
                            val cc = cur[k].approval
                            if (cc != null && cc.resolved.isEmpty()) {
                                cur[k] = cur[k].copy(approval = cc.copy(resolved = choice))
                                setMsgs(r, cur)
                            }
                        }
                        // 确认类提示只闪一下：常驻会一直钉在顶部，像还有事没处理。
                        flashNote(r, "已选择「" + choice + "」，已送达服务端")
                    }
                    // 服务端已不在等这条（本轮结束/审批超时放行/别处已答）：终局，把卡片收起。
                    // 不收的话就是用户报的「确认不了、也拒绝不了，提示一直在」。
                    receiptGone(code) -> {
                        expireCard(r, msgId, true)
                        flashNote(r, "这条审批已失效（本轮已结束或已被处理），已收起")
                    }
                    else -> r.retryNote.value = "回执没送到：本轮可能已收尾，可重试"
                }
            }
        }
    }

    /** 轮末 token 用量：挂到该会话最后一条助手消息上。 */
    private fun attachUsage(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        attachUsageJson(r, ev.data.optJSONObject("usage"))
    }

    /**
     * 把一份 usage JSON 挂到该会话最后一条助手消息上。
     *
     * 为什么单独抽一个入口：「使用量行」和「语音播报按钮」都由收尾时挂到气泡上的元数据驱动
     * （usage / runId），而**兜底收尾**（SSE 流被掐断、靠 `GET /v1/runs/{id}` 判结束）原来只
     * 发通知与播报，两样都没挂 → 用户报「偶尔任务完成后不出现这些信息、也没有语音按钮」
     * （2026-10-10）。那条路径拿到的 run 状态 payload 里带的就是同一份 usage，补挂即可。
     */
    private fun attachUsageJson(r: SessionRuntime, u: JSONObject?) {
        if (u == null) return
        // 耗时起点与界面实时计时同源：优先用会话级的本轮起点 r.startedAt
        // （整轮唯一、不会被气泡重建冲掉），它没有才回落气泡自己的。
        val bubbleStart = r.messages.value.lastOrNull { it.role == "assistant" }?.startedAt ?: 0L
        // 优先用会话级的本轮起点（整轮唯一、不会被气泡重建冲掉）；它没有才回落气泡的。
        val baseStart = if (r.startedAt > 0) r.startedAt else bubbleStart
        val usage = Usage(
            input = u.optInt("input_tokens", 0),
            output = u.optInt("output_tokens", 0),
            total = u.optInt("total_tokens", 0),
            cacheRead = u.optInt("cache_read_tokens", 0),
            cacheWrite = u.optInt("cache_write_tokens", 0),
            // 耗时以服务端为准：服务端随 usage 下发的 duration_seconds 是本轮真实执行耗时，
            // 不含 App↔服务端网络往返、排队与断线重连等待，数字稳定。它没有才回落本地掐表。
            durationMs = if (u.has("duration_seconds"))
                (u.optDouble("duration_seconds", 0.0) * 1000.0).toLong()
            else if (baseStart > 0) System.currentTimeMillis() - baseStart else 0L,
        )
        // 只有耗时（token 全 0）的轮次也要挂上：耗时本身就是用户要看的统计。
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0 && usage.durationMs <= 0) return
        // 会话级记一份：合并把最终答复换成服务端插入行后，由 withTurnMeta 重挂（否则那条
        // 新行没有使用量行）。
        r.turnUsage = usage
        r.turnTrace = r.messages.value.lastOrNull { it.role == "assistant" }?.trace.orEmpty()
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(usage = usage)
        setMsgs(r, list)
    }

    /**
     * 把本轮收尾元数据（usage / runId / 过程轨迹）挂到**最后一条助手消息**上。
     *
     * 为什么要在「与服务端记录合并」之后再做一遍：合并（`mergeByUserAnchor`）按服务端顺序
     * 重建尾部，本轮最终答复可能变成一条**服务端插入行**（实测日志 `合并补齐助手正文
     * 本地块=1 服务端块=4 补入=3`），那条新行天然没有 usage/runId/trace——使用量行、
     * 「过程(N 步)」、语音播报按钮就全丢了（用户 2026-10-10 报「偶尔任务完成后不出现这些
     * 信息，包括语音播报按钮」）。收尾时把元数据留在会话上（turnUsage/turnRunId/turnTrace），
     * 合并后重挂到最后一条助手消息即可；只在对方缺项时补，不覆盖已有值。
     */
    private fun withTurnMeta(r: SessionRuntime, list: List<Msg>): List<Msg> {
        val u = r.turnUsage
        val rid = r.turnRunId
        val tr = r.turnTrace
        if (u == null && rid.isEmpty() && tr.isEmpty()) return list
        val i = list.indexOfLast { it.role == "assistant" }
        if (i < 0) return list
        val cur = list[i]
        val nu = cur.usage ?: u
        val nr = if (cur.runId.isEmpty()) rid else cur.runId
        val nt = if (cur.trace.isEmpty()) tr else cur.trace
        if (nu === cur.usage && nr == cur.runId && nt == cur.trace) return list
        val out = list.toMutableList()
        out[i] = cur.copy(usage = nu, runId = nr, trace = nt)
        return out
    }

    /** 正在发送回执的卡片消息 id：防连点重复 POST，发完（无论成败）都放回。 */
    private val receiptInFlight = ConcurrentHashMap.newKeySet<Long>()

    /** 子任务开始/结束：按 subagent_id 或 goal 归并成一行进度。 */
    private fun upsertSubagent(r: SessionRuntime, ev: com.hermesapp.net.SseEvent, running: Boolean) {
        val id = ev.data.optString("subagent_id", "").ifEmpty { ev.data.optString("delegation_id", "") }
        val goal = ev.data.optString("goal", "")
        val summary = ev.data.optString("summary", "")
        val statusRaw = ev.data.optString("status", "")
        val child = ev.data.optString("child_session_id", "")
        val toks = ev.data.optInt("input_tokens", 0) + ev.data.optInt("output_tokens", 0)
        val key = id.ifEmpty { goal }
        if (key.isEmpty()) return
        val status = when {
            running -> "running"
            statusRaw.isNotEmpty() -> statusRaw
            else -> "completed"
        }
        val list = r.messages.value.toMutableList()
        // 优先挂在本轮那条「进行中」的助手气泡上；本轮已收尾（后台子代理跑得比父 run 久，
        // 完成事件晚到）就退而挂在最后一条助手气泡上——旧逻辑找不到 pending 直接 return，
        // 这类事件会整条被丢掉，进度永远停在「▶ 运行中」。
        val pendingIdx = list.indexOfLast { it.role == "assistant" && it.pending }
        val mi = if (pendingIdx >= 0) pendingIdx else list.indexOfLast { it.role == "assistant" }
        if (mi < 0) return
        val old = list[mi].subagents
        val idx = old.indexOfFirst { it.id == key }
        val prev = old.getOrNull(idx)
        val now = System.currentTimeMillis()
        val line = SubagentLine(
            id = key,
            goal = goal.ifEmpty { prev?.goal ?: "" },
            status = status,
            summary = summary.ifEmpty { prev?.summary ?: "" },
            childSessionId = child.ifEmpty { prev?.childSessionId ?: "" },
            steps = prev?.steps ?: 0,
            startedAt = prev?.startedAt?.takeIf { it > 0 } ?: if (running) now else 0L,
            seenAt = prev?.seenAt ?: 0L,
            endedAt = if (running) 0L else now,
            tokens = if (toks > 0) toks else (prev?.tokens ?: 0),
        )
        val next = if (idx >= 0) old.toMutableList().also { it[idx] = line } else old + line
        list[mi] = list[mi].copy(subagents = next)
        setMsgs(r, list)
        // 开跑就把进度轮询挂上：运行中的进度只能靠读子代理自己的会话拿到。
        if (running && line.childSessionId.isNotEmpty()) ensureSubagentSweep(r.id)
    }

    /** 子任务进度面板：null = 没打开。 */
    private val _subDetail = MutableStateFlow<SubagentDetail?>(null)
    val subDetail = _subDetail.asStateFlow()
    private var subDetailJob: Job? = null

    /**
     * 子代理实时进度轮询（每个会话一条，全部收工自动退出）。
     *
     * 为什么必须轮询：网关那条 run 事件流只转发 subagent.start / subagent.complete，
     * 中间的 subagent.tool（子代理每调一次工具）与 subagent.progress（每满 5 次一批）
     * 被当「界面噪音」丢掉了，所以推送里根本没有运行中的进度。
     * 但子代理自己的会话是实时的（source=subagent），读它的 tool_call_count / ended_at
     * 就够出进度。全程只读，不改服务端。
     */
    private fun ensureSubagentSweep(sid: String) {
        if (sid.isEmpty()) return
        val r = rt(sid)
        if (r.subSweep?.isActive == true) return
        // 必须显式切到 IO：identityScope 是 Dispatchers.Main.immediate，
        // 而 sessionDetail() 是阻塞式 HTTP。挂主线程的话，打开有子任务的会话时
        // 第一次拉进度就把 UI 卡住（这正是「有子任务的会话打开卡顿」的根因）。
        r.subSweep = identityScope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            val deadline = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                val kids = runningChildren(r)
                if (kids.isEmpty()) break
                var changed = false
                for (k in kids) {
                    val s = runCatching { a.sessionDetail(k.childSessionId).optJSONObject("session") }
                        .getOrNull() ?: continue
                    if (applyChildProgress(r, k.id, s)) changed = true
                }
                delay(if (changed) 4_000L else 8_000L)
            }
            r.subSweep = null
        }
    }

    /** 把子代理会话的进度写回对应那一行。返回是否有变化。 */
    private fun applyChildProgress(r: SessionRuntime, key: String, s: JSONObject): Boolean {
        val list = r.messages.value.toMutableList()
        for (i in list.indices.reversed()) {
            val m = list[i]
            val idx = m.subagents.indexOfFirst { it.id == key }
            if (idx < 0) continue
            val old = m.subagents[idx]
            val endSec = s.optDouble("ended_at", 0.0)
            val endMs = if (endSec > 0) (endSec * 1000.0).toLong() else 0L
            // 子代理会话已结束、但那条 subagent.complete 没等到（后台子代理跑得比父 run 久时
            // 很常见，父 run 的事件流早断了）：按 end_reason 据实标注，不一律当成功。
            val st = if (endMs > 0 && old.status == "running") {
                val why = s.optString("end_reason", "")
                if (why.isEmpty() || why.contains("close") || why.contains("complete") ||
                    why.contains("normal")) "completed" else "ended"
            } else old.status
            val toks = s.optInt("input_tokens", 0) + s.optInt("output_tokens", 0)
            val next = old.copy(
                steps = s.optInt("tool_call_count", old.steps),
                seenAt = System.currentTimeMillis(),
                endedAt = endMs,
                status = st,
                tokens = if (toks > 0) toks else old.tokens,
            )
            if (next == old) return false
            val ns = m.subagents.toMutableList()
            ns[idx] = next
            list[i] = m.copy(subagents = ns)
            setMsgs(r, list)
            // 返回「真有进度」与否：只影响下一轮隔多久再拉（4 秒 / 8 秒），
            // seenAt 每轮都写，界面上的「刚刚取的进度」才不会看着像卡死。
            return next.steps != old.steps || next.status != old.status
        }
        return false
    }

    /**
     * 打开「子任务进度」面板：拉这个子代理会话的末尾消息，拆成步骤流水。
     * 面板开着时每 3 秒刷新一次，子任务收工就停（不再空转）。
     */
    /**
     * 停止一个正在跑的子任务。
     *
     * 为什么不复用 stop()：子任务是后台子代理，可能比父轮次活得久，父 run 的
     * /v1/runs/{id}/stop 管不到它。服务端另开了 POST /api/subagents/{id}/stop，
     * 对子代理对象直接发协作式中断（与 TUI 的 subagent.interrupt 同一条链路）。
     * 「停止」语义是到下一个迭代边界就停，不是立即杀进程；found=false 表示它已经不在跑了。
     */
    fun stopSubagent(sid: String, key: String) {
        if (sid.isEmpty() || key.isEmpty()) return
        val a = api ?: return
        AppLog.log("stop", "停止子任务 sid=" + sid.take(8) + " key=" + key)
        identityScope.launch(Dispatchers.IO) {
            val resp = runCatching { a.stopSubagent(key) }.getOrNull()
            val found = resp?.optBoolean("found", false) ?: false
            rt(sid).retryNote.value =
                if (found) "已请求停止该子任务（到下一个步骤边界停下）" else "那个子任务已经不在跑了"
        }
    }

    fun openSubagentDetail(sid: String, key: String) {
        val line = rt(sid).messages.value.flatMap { it.subagents }.firstOrNull { it.id == key } ?: return
        _subDetail.value = SubagentDetail(
            key = key, goal = line.goal, childSessionId = line.childSessionId,
            status = line.status, startedAt = line.startedAt, endedAt = line.endedAt, tokens = line.tokens,
        )
        subDetailJob?.cancel()
        // 同上：面板的 3 秒刷新也是阻塞 HTTP，不能在主线程上跑。
        subDetailJob = identityScope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            val deadline = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                val cur = _subDetail.value ?: break
                if (cur.key != key) break
                if (cur.childSessionId.isEmpty()) {
                    _subDetail.value = cur.copy(note = "这次派发没带回子会话 id，看不到它的内部步骤")
                    break
                }
                val resp = runCatching { a.sessionMessagesTail(cur.childSessionId, 40) }.getOrNull()
                val nowLine = rt(sid).messages.value.flatMap { it.subagents }.firstOrNull { it.id == key }
                _subDetail.value = cur.copy(
                    steps = if (resp != null) parseSubagentSteps(resp.optJSONArray("data")) else cur.steps,
                    status = nowLine?.status ?: cur.status,
                    endedAt = nowLine?.endedAt ?: cur.endedAt,
                    tokens = nowLine?.tokens ?: cur.tokens,
                    startedAt = nowLine?.startedAt?.takeIf { it > 0 } ?: cur.startedAt,
                    updatedAt = System.currentTimeMillis(),
                    note = if (resp == null) "这一下没读到，正在重试…" else "",
                )
                if ((nowLine?.status ?: "running") != "running") break
                delay(3_000L)
            }
        }
    }

    fun closeSubagentDetail() {
        subDetailJob?.cancel()
        subDetailJob = null
        _subDetail.value = null
    }

    private fun streamRun(a: HermesApi, sid: String) {
        // 状态收敛：streamGen 的读-改-写与所有事件回调必须在同一线程串行。
        // 否则 IO 线程（maybeContinue 退避链）与 OkHttp 回调线程会并发改它，
        // 这是历史上「重复播报 / 进度错乱」的共同根因。非主线程调用一律投回主线程。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            identityScope.launch { streamRun(a, sid) }
            return
        }
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        // 起新流前先掐掉上一条并作废它的回调：r.call 被覆盖而旧流还活着时，两条流会同时收到
        // run.completed，正文、通知与语音都执行两遍（实测 2.81 回前台重复播报）。
        val gen = r.streamGen + 1
        r.streamGen = gen
        val sendGen = r.sendGen
        r.call?.let { old -> if (!old.isCanceled()) { AppLog.log("stream", "重起流先掐旧流 run=" + rid.take(12)); old.cancel() } }
        r.lastEventAt = System.currentTimeMillis()   // 重新起流即重置活跃时间，避免刚连上就被判假死
        r.evCount = 0                                 // 本轮事件计数：流关闭时汇总，一眼看出事件到底到没到
        r.toolCount = 0
        AppLog.log("stream", "起流 run=" + rid.take(12) + " lastSeq=" + r.lastSeq + " 第" + r.autoContinue + "次续接 gen=" + gen + "（本轮事件计数清零）")
        // 每轮流一个攒帧器：碎字按帧放送。重起流时丢弃上一轮的残余。
        r.coalescer?.discard()
        r.coalescer = StreamDeltaCoalescer(identityScope, onFlush = { s -> appendDelta(r, s) })
        r.call = a.streamEvents(
            runId = rid,
            lastSeq = r.lastSeq,
            onEvent = { ev -> identityScope.launch {
                if (!r.acceptsStreamCallback(gen, sendGen)) return@launch   // 已被新流取代，作废
                if (ev.id != null) r.lastSeq = ev.id
                r.lastEventAt = System.currentTimeMillis()
                val name = ev.event ?: ev.data.optString("event", "")
                r.evCount++
                if (name.startsWith("tool.")) r.toolCount++
                // 收到任何真实事件即视为连接已恢复正常：清掉重连提示与退避计数。
                // 判据交给 ReconnectPolicy：必须覆盖「不经退避计数」的恢复路径
                // （看门狗重起流、回前台掐假死流），否则那行提示会一直钉在会话上方。
                if (ReconnectPolicy.shouldClearNoteOnEvent(r.retryNote.value, name, r.autoContinue)) {
                    r.autoContinue = 0
                    r.retryNote.value = ""
                }
                // 流已恢复：翻历史的盯梢任务作废。
                if (name != "replay.truncated") r.recoveryJob?.cancel()
                when (name) {
                    // 服务端明确告知：断线期间的事件已超出保留窗口、拿不回来了。
                    // 不能静默——在气泡里标一行，并拉一次服务端消息兜底。
                    "replay.truncated" -> {
                        appendDelta(r, TRUNCATED_NOTICE)
                        val s = sid
                        identityScope.launch(Dispatchers.IO) {
                            delay(600)
                            if (s == _currentId.value) refreshFromServer()
                        }
                    }
                    // 入攒帧缓冲，由 StreamDeltaCoalescer 按帧放送（避免一大块一大块地跳）。
                    "message.delta" -> r.coalescer?.append(ev.data.optString("delta", ""))
                    "message.interim" -> {}
                    // 服务端把 agent 的 lifecycle 状态转发进这条流了（服务端补丁 apply_run_status_stream_patch）：
                    // 召回指示 → 每轮固定一行（正文之前显式显示，用户 2026-10-14 要求）；
                    // 其它状态（上游等待 / 自动恢复倒计时 / 模型切换）→ 落进可折叠的「过程」，
                    // 让流静默时有据可查，不占正文、不打扰。
                    "hermes.status" -> {
                        val stTxt = ev.data.optString("text", "").trim()
                        if (stTxt.isNotEmpty()) {
                            val recall = RecallNote.line(stTxt)
                            if (recall != null) {
                                attachRecall(r, recall)
                            } else {
                                val msgs = r.messages.value.toMutableList()
                                val pi = msgs.indexOfLast { it.role == "assistant" && it.pending }
                                if (pi >= 0) {
                                    msgs[pi] = msgs[pi].copy(trace = msgs[pi].trace + "· 服务端：" + stTxt + "\n")
                                    setMsgs(r, msgs)
                                }
                                AppLog.log("stream", "服务端状态：" + stTxt.take(120))
                            }
                        }
                    }
                    "tool.started" -> {
                        // 服务端 canonical turn 链路有两个缺口，都得客户端补：
                        // ① tool.completed 不带 duration（只有 _make_run_event_callback 那条带）
                        //    → 这里记开始时刻，完成时算墙钟差；
                        // ② tool.completed 的 preview 也是空的（_tool_complete 没传 result）
                        //    → 入参预览只有这条 started 事件带（terminal 就是命令本身）。
                        val cid = ev.data.optString("tool_call_id", "")
                        if (cid.isNotEmpty()) {
                            if (toolStartedAt.size > 400) toolStartedAt.clear()
                            toolStartedAt[sid + "|" + cid] =
                                ToolStart(System.currentTimeMillis(), ev.data.optString("preview", "").take(400))
                        }
                    }
                    "tool.completed" -> {
                        r.coalescer?.flushNow()
                        val (dur, pv) = toolOutcome(sid, ev)
                        val line = toolLine(ev, ev.data.optBoolean("error", false), dur, pv)
                        if (line.isNotEmpty()) appendTrace(r, line)
                        AppLog.log("stream", "过程行已加 工具=" + ev.data.optString("tool", "") +
                            " 本轮事件=" + r.evCount + " 其中工具=" + r.toolCount)
                    }
                    "tool.failed" -> {
                        r.coalescer?.flushNow()
                        val (dur, pv) = toolOutcome(sid, ev)
                        val line = toolLine(ev, true, dur, pv)
                        if (line.isNotEmpty()) appendTrace(r, line)
                    }
                    // 流式语音（方案丙）：服务端边合成边推，这里边收边播。
                    // run.completed 已先到（finished 已置位），音频晚到不会触发误重连。
                    "audio.start" -> {
                        AppLog.log("stream", "语音流开始 run=" + rid.take(12))
                        if (prefs.playCompletionVoice) {
                            StreamVoicePlayer.rate = prefs.voiceRate
                            // 带上归属会话：多任务排队时提示条要显示「正在播放：X 的语音」。
                            val vTitle = _sessions.value.firstOrNull { it.id == sid }?.title ?: "对话"
                            StreamVoicePlayer.begin(getApplication(), "stream:" + rid, rid, sid, vTitle)
                        }
                    }
                    "audio.delta" -> {
                        if (prefs.playCompletionVoice) {
                            StreamVoicePlayer.append(getApplication(), "stream:" + rid, ev.data.optString("data", ""))
                        }
                    }
                    "audio.end" -> {
                        AppLog.log("stream", "语音流结束 run=" + rid.take(12) +
                            " 块数=" + ev.data.optInt("chunks", 0))
                        if (prefs.playCompletionVoice) {
                            StreamVoicePlayer.end(getApplication(), "stream:" + rid)
                        }
                    }
                    "approval.request" -> attachApproval(r, ev)
                    "clarify.request" -> attachClarify(r, ev)
                    "subagent.start" -> upsertSubagent(r, ev, running = true)
                    "subagent.complete" -> upsertSubagent(r, ev, running = false)
                    "run.completed" -> {
                        r.coalescer?.flushNow()
                        AppLog.log("stream", "run 完成 run=" + rid.take(12) + " 正文长度=" + ev.data.optString("output", "").length)
                        r.finished = true
                        tagLastAssistantRunId(r, rid)
                        val out = ev.data.optString("output", "")
                        if (out.isNotEmpty()) setPendingText(r, out) else finishPending(r)
                        attachUsage(r, ev)
                        doneOk(sid)
                        // 插话没赶上本轮：服务端把未送达的插话文本随终态放在 pending_steer 里下发。
                        // 不静默丢——放回输入框（若为空）并提示，用户点发送即可重发。
                        // 放在 doneOk 之后，避免被它的 retryNote 清空覆盖。
                        val ps = ev.data.optString("pending_steer", "").trim()
                        if (ps.isNotEmpty()) {
                            if (prefs.draftFor(sid).isEmpty()) prefs.setDraft(sid, ps)
                            r.retryNote.value = "上一句插话没赶上本轮，已放回输入框，点发送重发"
                        }
                        notifyCompletion(sid, out)
                        // 完成语音：服务端把整段回复合成音频随 output 下发，这里自动播一遍。
                        if (out.isNotEmpty()) {
                            VoicePlayer.playFromReply(getApplication(), out, prefs.playCompletionVoice)
                        }
                    }
                    "run.failed" -> {
                        r.coalescer?.flushNow()
                        AppLog.log("stream", "run 失败 run=" + rid.take(12) + " 错误=" + ev.data.optString("error", "未知").take(120))
                        r.finished = true
                        appendDelta(r, "\n[失败] " + ev.data.optString("error", "未知错误"))
                        doneOk(sid)
                    }
                    "run.cancelled", "run.interrupted" -> {
                        r.coalescer?.flushNow()
                        r.finished = true
                        appendDelta(r, "\n[已中断]")
                        doneOk(sid)
                    }
                }
            } },
            onClosed = { identityScope.launch {
                if (!r.acceptsStreamCallback(gen, sendGen)) return@launch   // 已被新流取代，作废
                r.coalescer?.flushNow()
                AppLog.log("stream", "流关闭 run=" + rid.take(12) + " busy=" + r.busy.value + " finished=" + r.finished + " lastSeq=" + r.lastSeq +
                    " 本轮共收事件=" + r.evCount + " 其中工具=" + r.toolCount)
                if (r.busy.value && !r.finished) maybeContinue(sid)
            } },
            onError = { e -> identityScope.launch {
                if (!r.acceptsStreamCallback(gen, sendGen)) return@launch   // 已被新流取代，作废
                AppLog.err("stream", "流出错 run=" + rid.take(12) + " busy=" + r.busy.value + " finished=" + r.finished +
                    " 本轮共收事件=" + r.evCount + " 其中工具=" + r.toolCount, e)
                // 不再往正文塞「[连接断开]」——断流期间的提示统一走 retryNote（气泡上方一行），
                // 正文只保留任务真实产出，避免一次抖动就在会话里留一条错行。
                r.coalescer?.flushNow()
                if (r.busy.value && !r.finished) {
                    // 404 不是网络抖动，是服务端已经没有这条 run 的 SSE 缓冲了（服务端的清扫
                    // 会把过期缓冲回收掉）。再按退避重起流只会每次都秒 404（实测一条 run 连撞
                    // 24 次，用户得手动点停止），所以直接转状态轮询等这一轮跑完。
                    if (e is com.hermesapp.net.RunStreamGoneException) {
                        AppLog.log("retry", "流 404（服务端缓冲已回收），转状态轮询 run=" + rid.take(12))
                        // 跳过退避重起流（再起也是秒 404）；404 不等于「这一轮结束了」，
                        // 状态轮询会先确认它是否还在服务端跑着。
                        r.autoContinue = MAX_RECONNECT_ATTEMPTS
                        waitLiveRunByStatus(sid, rid)
                    } else {
                        if (r.retryNote.value.isEmpty()) r.retryNote.value = ReconnectPolicy.noteReconnecting("：" + (e.message ?: "未知"))
                        maybeContinue(sid)
                    }
                }
            } },
            // 心跳等任何一行都刷新活跃时间：长工具执行期间只有心跳、没有真实事件，
            // 不刷就会让「回到前台」的 25 秒看门狗把健康流误判成假死（用户报的「一直在重连」）。
            onActivity = { identityScope.launch { if (r.acceptsStreamCallback(gen, sendGen)) r.lastEventAt = System.currentTimeMillis() } }
        )
    }

    /**
     * SSE 流提前断开（没收到 run.completed）时的续接：
     * 绝不伪造用户消息——只查同一 run 的状态，仍在跑就续接它的事件流；
     * 已结束就从服务端拉回结果。仅作用于该 run 所属的会话。
     *
     * 重试用退避而非固定间隔：网络抖动（地铁、切基站、网络切换）往往几秒内恢复，
     * 原来的「固定 1.2 秒 × 3 次」会在 4 秒内烧完次数然后彻底放弃；现在
     * 1→2→4→8→16→30 秒封顶、最多 8 次，覆盖约 1 分钟的窗口，且首次仍只等 1 秒。
     */
    private fun maybeContinue(sid: String) {
        val r = rt(sid)
        if (r.finished) return
        val a = api ?: return
        val rid = r.runId
        if (rid.isEmpty()) { failPending(sid); return }
        if (r.autoContinue >= MAX_RECONNECT_ATTEMPTS) {
            // 重连退避用尽：不判死，改去翻服务端会话记录等答案落盘。
            startHistoryRecovery(sid, rid)
            return
        }
        // 同一次断流可能从多个口子进来（onClosed 与 onError 都会调、切回前台的
        // 体检也会间接触发），各起一条退避链就会重复探测、重复加计数，
        // 严重时一边判「还在跑」一边判「已结束」，接流与收尾互相打架。
        // 这里做单飞：同一会话同时只允许一条退避链在等。
        if (!r.probing.compareAndSet(false, true)) return
        val attempt = r.autoContinue + 1
        r.autoContinue = attempt
        val waitMs = backoffDelayMs(attempt)
        r.retryNote.value = ReconnectPolicy.noteReconnecting("，${waitMs / 1000} 秒后重试（$attempt/$MAX_RECONNECT_ATTEMPTS）")
        AppLog.log("retry", "第 $attempt/$MAX_RECONNECT_ATTEMPTS 次重试，${waitMs / 1000}s 后探 run=" + rid.take(12))
        // 退避链认轮（R02）：进入时记下轮次与 runId，结果回来时逐字核对。
        // 旧实现只查 busy/finished —— 用户 Stop A 后起了 B 时 busy 仍是 true，于是
        // A 探测回来的 Known("completed")/Missing 会按当前会话收尾，把 B 的 busy、
        // runId 与 active 全清掉（探针实测 busy=false、runId=run-B、active={}、drains=2）。
        val gen = r.sendGen
        identityScope.launch(Dispatchers.IO) {
            delay(waitMs)
            // 退出前必须留一行：这条退避链可能已被别的路径（切回前台的体检、
            // 翻历史取回、手动停止）抢先收尾。静默 return 会让日志里只剩
            // 「第 N 次重试」却没有「探测结果」，排查时被误读成重试卡死。
            if (!r.busy.value || r.finished || r.runId != rid || r.sendGen != gen) {
                r.probing.set(false)
                AppLog.log("retry", "退避作废 run=" + rid.take(12) +
                    "（本轮已由其它路径收尾 busy=" + r.busy.value + " finished=" + r.finished +
                    " 现run=" + r.runId.take(12) + " gen=" + gen + "/" + r.sendGen + "）")
                return@launch
            }
            val st = try {
                a.probeRun(rid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.err("retry", "退避探测抛异常 run=" + rid.take(12), e)
                HermesApi.RunStatus.Unknown
            } finally {
                // 单飞位必须**无条件**释放：异常或取消漏掉这一行，该会话之后每次重连都会被
                // 入口那个 CAS 挡回，界面上「正在执行」就永久停住 —— 守卫本身成了泄漏源。
                // （2026-10-13 加固：原来这行在 probeRun 之后裸着，抛异常就永久泄漏。）
                r.probing.set(false)
            }
            // 探测期间（阻塞网络调用）用户也可能停/换轮：这份结果属于旧轮，
            // 不许拿它改当前会话（R02）。
            if (r.sendGen != gen || r.runId != rid || !r.busy.value || r.finished) {
                AppLog.log("retry", "探测结果属于旧轮，忽略 run=" + rid.take(12) +
                    " 现run=" + r.runId.take(12) + " gen=" + gen + "/" + r.sendGen)
                return@launch
            }
            AppLog.log("retry", "探测结果 " + when (st) {
                is HermesApi.RunStatus.Known -> "Known(" + st.status + ")"
                HermesApi.RunStatus.Missing -> "Missing(404)"
                HermesApi.RunStatus.Unknown -> "Unknown(探不出来)"
            })
            when (st) {
                // 服务端明确回答「还在跑」：续接同一 run，不新增任何用户消息。
                is HermesApi.RunStatus.Known -> {
                    if (st.status in RUNNING_STATES) {
                        r.retryNote.value = ""
                        // 断流期间服务端可能在等审批/澄清：续接前先把卡片挂回来。
                        restorePendingCard(r, st.payload)
                        streamRun(a, sid)
                    } else {
                        // 服务端明确回答「已结束」：收尾并拉回产出（含放行排队；用户点过
                        //「停止」而按住的队列不受影响——drainQueue 会看 queuePaused）。
                        finishRecoveredTurn(sid, rid, st.payload)
                    }
                }
                // 服务端明确说没这个 run：判结束（极少见，run 记录被清）。
                HermesApi.RunStatus.Missing -> {
                    // run 记录都没了，拿不到 usage 与产出；runId 仍补挂，语音按钮
                    //（按 runId 取留档）还能用。
                    finishRecoveredTurn(sid, rid, null)
                }
                // 探不出来（网还没通）：不知道 ≠ 已结束，继续退避重试。
                // 这是原实现最大的坑——把「不知道」当成了「已结束」，第一次探测
                // 失败就把任务判死，8 次退避等于摆设。
                HermesApi.RunStatus.Unknown -> {
                    if (r.autoContinue >= MAX_RECONNECT_ATTEMPTS) {
                        r.retryNote.value = ""
                        startHistoryRecovery(sid, rid)
                    } else {
                        maybeContinue(sid)
                    }
                }
            }
        }
    }

    /**
     * 服务端明确说这一轮已结束，或那条 run 记录已经不在了：收尾并把产出接回来。
     *
     * 抽成一处共用：退避探测、流 404 之后的状态轮询、服务端记录消失三条路要走同样的收尾
     * （清过期待办卡、挂 usage 与 runId、完成提醒、放行排队），分头写必有一处漏
     * ——原来「翻历史取回」那条就漏挂收尾元数据（用户报过「偶尔完成后没有使用量与语音按钮」）。
     */
    private fun finishRecoveredTurn(sid: String, rid: String, payload: org.json.JSONObject?) {
        val r = rt(sid)
        r.retryNote.value = ""
        r.finished = true
        clearStaleCards(r)
        finishPending(r)
        // 兜底收尾也要挂收尾元数据：使用量行与语音按钮都靠它（见 attachUsageJson）。
        tagLastAssistantRunId(r, rid)
        attachUsageJson(r, payload?.optJSONObject("usage"))
        notifyRecoveredCompletion(sid, payload?.optString("output", "").orEmpty())
        r.busy.value = false
        prefs.removeActiveRun(sid)
        updateRunService()
        if (sid == _currentId.value) refreshFromServer()
        // 本轮已确认结束 → 把排队中的下一条接着发（原来这条路径漏了 drainQueue，
        // 表现是「断流被判结束后，排队的消息一直卡着不走」，F06）。
        drainQueue(sid)
    }

    /**
     * 流接不上了（服务端 SSE 缓冲已回收，/events 永久 404），但这一轮在服务端还在跑：
     * 不判死、也不再反复重起流，改成按固定间隔问状态，跑完自动把产出接回来。
     *
     * 为什么不能沿用翻历史：翻历史靠「本地发送锚点」认哪条是本次答案，而重开 App 恢复出来
     * 的轮次没有这个锚点（pendingSendText 不落盘），旧代码于是只在界面上留一句
     * 「连接不稳，仍在尝试接回本轮…」干等到下次回前台。真机现场（2026-10-10）：任务
     * 21:13:37 就跑完了，App 21:17:41 才接回（等了 4 分 04 秒），那句误导提示挂了 9 分 38 秒。
     *
     * 复用 r.recoveryJob 这个槽位：所有「停止/换轮/删除会话」路径都已经在取消它，
     * 另开一个字段就会有人忘了取消。
     */
    private fun waitLiveRunByStatus(sid: String, rid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.finished) return
        if (r.recoveryJob?.isActive == true) return
        val startedAt = r.startedAt

        // 提示语分两段写：探测前只说「正在确认」，服务端确认真在跑之后才说「还在跑」。
        //
        // 原来一进函数就喊「这一轮还在服务器上跑（已 N 分钟），跑完自动取回」，再等一个
        // 轮询间隔（20 秒）才第一次探测。但 404 最常见的成因根本不是「还在跑」，恰恰是
        // 「这一轮刚跑完」——服务端清扫器回收的是**已结束**那条 run 的传输层。真机日志
        // （2026-10-14）：00:49:38 这一轮已完成，00:50:57 用户切回 App 拿到 404，于是界面报
        // 「还在服务器上跑（已 26 分钟）」，而结果其实就在服务端等着取（用户当场报障）。
        // 现在：进函数先探一次（不等间隔），探测前那句不含结论。
        r.retryNote.value = ReconnectPolicy.LIVE_RUN_CONFIRMING_NOTE

        fun noteRunning(payload: org.json.JSONObject?) {
            val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
            val ev = payload?.optString("last_event", "").orEmpty()
            val age = lastEventAgeMs(payload)
            r.retryNote.value = ReconnectPolicy.liveRunNote(elapsed, ev, age)
            // 把「界面此刻给用户看了什么」落进日志：这句提示原来是纯 UI 状态、日志里查不到，
            // 复盘「当时到底显示的还在跑还是已结束」只能靠猜。
            AppLog.log("retry", "状态轮询：服务端确认仍在跑 已" + (elapsed / 60_000L) + "分 · last_event=" + ev +
                " · 服务端最后事件=" + (if (age < 0) "未知" else (age / 1000L).toString() + "秒前") + " run=" + rid.take(12))
        }

        r.recoveryJob = identityScope.launch(Dispatchers.IO) {
            var elapsed = 0L
            var misses = 0
            var firstProbe = true
            while (elapsed < LIVE_STATUS_POLL_WINDOW_MS) {
                // 第一次探测立刻做：404 常常恰恰发生在「这一轮刚结束」的那一分钟里，
                // 先等一个间隔（20 秒）再问，就是把已经拿得到的结果按住不显示。
                if (firstProbe) {
                    firstProbe = false
                } else {
                    delay(LIVE_STATUS_POLL_INTERVAL_MS)
                    elapsed += LIVE_STATUS_POLL_INTERVAL_MS
                }
                if (!r.busy.value || r.finished) return@launch
                val gen = r.sendGen
                val st = try {
                    a.probeRun(rid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // 探测期间（阻塞网络调用）用户可能已经停轮/换轮：这份结果属于旧轮（R02），
                // 不许拿它改当前会话。
                if (gen != r.sendGen || r.runId != rid || !r.busy.value || r.finished) {
                    AppLog.log("retry", "状态轮询结果属于旧轮，忽略 run=" + rid.take(12))
                    return@launch
                }
                when (st) {
                    null, HermesApi.RunStatus.Unknown -> {
                        misses++
                        AppLog.log("retry", "状态轮询探不出来（" + misses + "/" + LIVE_STATUS_POLL_MAX_MISSES + "）run=" + rid.take(12))
                        if (misses >= LIVE_STATUS_POLL_MAX_MISSES) {
                            r.recoveryJob = null
                            startHistoryRecovery(sid, rid)
                            return@launch
                        }
                        // 探不出来 ≠ 已结束，也 ≠ 还在跑：提示照实说，别留上一句「还在跑」在屏幕上。
                        r.retryNote.value = ReconnectPolicy.liveRunUnreachableNote(misses, LIVE_STATUS_POLL_MAX_MISSES)
                    }
                    is HermesApi.RunStatus.Known -> {
                        misses = 0
                        if (st.status in RUNNING_STATES) {
                            noteRunning(st.payload)
                        } else {
                            AppLog.log("retry", "状态轮询：这一轮已结束（" + st.status + "），接回产出 run=" + rid.take(12))
                            finishRecoveredTurn(sid, rid, st.payload)
                            return@launch
                        }
                    }
                    HermesApi.RunStatus.Missing -> {
                        // 服务端连 run 记录都没了：交给翻历史（有锚点时才认得出来），
                        // 认不出来会给出「可重发」这类明确提示。
                        AppLog.log("retry", "状态轮询：服务端没有这条 run 了，转翻历史 run=" + rid.take(12))
                        r.recoveryJob = null
                        startHistoryRecovery(sid, rid)
                        return@launch
                    }
                }
            }
            r.recoveryJob = null
            giveUpRecovery(sid, "等 2 小时仍未取回结果，可稍后再看")
        }
    }

    /**
     * run 状态里的 `updated_at` 距现在多少毫秒；<0 = 负载没带这个字段（老服务端 / 异常体）。
     *
     * 它是服务端「最后一次有动静」的时刻（每次工具/生命周期事件都会刷新），提示里那句
     * 「最后一个工具步骤在 N 分钟前」就是拿它算的。注意 `updated_at` 是秒（浮点），
     * 不是毫秒——直接当毫秒用会把时间算到 1970 年。
     */
    private fun lastEventAgeMs(payload: org.json.JSONObject?): Long {
        val ts = payload?.optDouble("updated_at", 0.0) ?: 0.0
        if (ts <= 0.0) return -1L
        return System.currentTimeMillis() - (ts * 1000.0).toLong()
    }

    /** 退避等待（委托 ReconnectPolicy，便于单测）。 */
    private fun backoffDelayMs(attempt: Int): Long = ReconnectPolicy.backoffDelayMs(attempt)

    /**
     * 断流翻历史兜底。
     *
     * 重连退避用尽后不把回合判死：手机 SSE 常被系统掐死，而服务端其实还在跑，
     * 跑完会把答案写进会话记录。这里改为轮询服务端历史，等答案落盘后再合并回来。
     *
     * 认哪一条是本次的答案，靠「位置」不靠「文字」：发送前记下的用户消息条数 N，
     * 历史里第 N+1 条用户消息就是本次发送（再用正文做二次校验，防历史被编辑/分叉）。
     * 它之后最后一条非空助手消息即答案，且必须连续两次读到一致才算定下来
     * （签名里带记录总条数，服务端还在追加工具记录时签名会变，就继续等）。
     * 认不出锚点就放弃——宁可报错，也不认错答案。
     */
    private fun startHistoryRecovery(sid: String, rid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.finished) return
        if (r.recoveryJob?.isActive == true) return
        val pending = r.pendingSendText
        val prior = r.priorUserCount
        if (pending.isEmpty()) {
            // 重开 App 恢复出来的任务没有本地发送上下文，拿不到位置锚点，不能据此判死：
            // 保留活跃标记与气泡，等下次进前台（onAppForeground 会把退避计数归零）再续接。
            if (r.resumed) {
                // 这句原来写「连接不稳，仍在尝试接回本轮…」，但它既不是网络问题、也没在重试
                // ——实测那条误导管了用户 9 分 38 秒。回前台现在会立刻重新探测（见
                // onAppForeground），所以提示说实话。
                r.retryNote.value = "这一轮还没取回结果，切回前台会自动再试"
                r.autoContinue = 0
                return
            }
            // 终态提示：故意**不用**重连前缀（ReconnectPolicy.RECONNECTING_NOTE_PREFIX），
            // 因为它的语义不是「当下正在重连」而是「本轮到此结束」，流恢复也不该把它清掉。
            r.retryNote.value = "本轮连接中断：重试 $MAX_RECONNECT_ATTEMPTS 次仍未取回结果"
            failPending(sid)
            return
        }
        r.retryNote.value = ReconnectPolicy.noteReconnecting("，正在从服务端取回结果…")
        r.recoveryJob = identityScope.launch(Dispatchers.IO) {
            var delayMs = 5_000L
            var elapsed = 0L
            var lastSig: String? = null
            var unanchored = 0
            var failStreak = 0
            while (elapsed < HISTORY_RECOVERY_WINDOW_MS) {
                delay(delayMs)
                elapsed += delayMs
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
                if (!r.busy.value || r.finished) return@launch
                val rows = try {
                    historyRows(a, sid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failStreak++
                    if (failStreak >= 3) {
                        giveUpRecovery(sid, "服务端会话记录读取失败，可稍后再看")
                        return@launch
                    }
                    continue
                }
                failStreak = 0
                if (rows.isEmpty()) continue
                val anchor = resolveAnchor(rows, pending, prior)
                if (anchor < 0) {
                    lastSig = null
                    if (++unanchored >= 2) {
                        giveUpRecovery(sid, "本次发送未落到服务端，可重发")
                        return@launch
                    }
                    continue
                }
                unanchored = 0
                val sig = answerSignature(rows, anchor)
                if (sig != null && sig == lastSig) {
                    adoptRecovered(sid, rid)
                    return@launch
                }
                lastSig = sig
            }
            giveUpRecovery(sid, "等 30 分钟仍未取回结果，可稍后再看")
        }
    }

    /** 服务端会话记录解析成位置锚点所需的最小行。 */

    /** 答案已落盘：结束本轮并按服务端记录合并回来。 */
    private fun adoptRecovered(sid: String, rid: String) {
        val r = rt(sid)
        if (r.confirmingMsgId > 0L) {
            advanceReceipt(sid, r.confirmingMsgId, Receipt.ACCEPTED, note = "")
            r.confirmingMsgId = 0L
        }
        r.finished = true
        r.retryNote.value = ""
        r.busy.value = false
        r.runId = ""
        // 翻历史取回结果也等于本轮收尾：本次任务的「全部批准」关掉。
        disarmAutoApprove(sid)
        prefs.removeActiveRun(sid)
        updateRunService()
        refreshFromServerFor(sid)
        drainQueue(sid)   // 翻历史取回结果后，队列接着走
        // 翻历史取回结果这条路径原来不挂收尾元数据 → 使用量行与语音按钮都会缺
        //（用户报「偶尔任务完成后不出现这些信息」）。补一次 run 状态查询把 usage 与 runId
        // 挂上；纯尽力而为，取不到就算了（不影响已取回的正文）。
        if (rid.isEmpty()) return
        identityScope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            try {
                val st = a.probeRun(rid)
                // 期间若又开了新一轮就别乱挂（挂错气泡比不挂更糟）。
                if (r.busy.value) return@launch
                if (st is HermesApi.RunStatus.Known && st.payload != null) {
                    tagLastAssistantRunId(r, rid)
                    attachUsageJson(r, st.payload.optJSONObject("usage"))
                }
            } catch (e: Exception) {
                AppLog.log("retry", "补挂收尾元数据失败 run=" + rid.take(12) + " " + diagText(e))
            }
        }
    }

    /** 翻历史也没捞到：明确收尾，不留一个永远转圈的空气泡。 */
    private fun giveUpRecovery(sid: String, why: String) {
        val r = rt(sid)
        r.retryNote.value = why
        if (r.confirmingMsgId > 0L) {
            advanceReceipt(sid, r.confirmingMsgId, Receipt.FAILED, note = why)
            r.confirmingMsgId = 0L
        }
        failPending(sid)
    }

    /** 停止「当前会话」正在跑的任务。 */
    fun stop() = stopSession(_currentId.value)

    private fun stopSession(sid: String) {
        if (sid.isEmpty()) return
        val r = rt(sid)
        // 上一次停止没被确认时 runId 已清、但 stopIntentRid 还留着：这时再点停止
        // 就是对那条未确认的 run 重试（R08），不能像旧代码那样直接 return。
        val pendingStop = r.stopIntentRid
        if (!r.busy.value && r.runId.isEmpty() && pendingStop.isEmpty()) return
        val a = api
        val rid = r.runId.ifEmpty { pendingStop }
        AppLog.log("stop", "用户停止 sid=" + sid.take(8) + " run=" + rid.take(12))
        r.queuePaused.value = r.queue.isNotEmpty()
        try {
            store.saveQueue(sid, r.queue.toList(), paused = r.queuePaused.value)
        } catch (e: Exception) {
            r.retryNote.value = "队列暂停状态保存失败，重开后请先检查待发消息"
            AppLog.err("queue", "停止时队列暂停落盘失败", e)
        }
        r.finished = true
        // 用户主动停止：本次任务的「全部批准」也到此为止（后面再问就重新等他点）。
        disarmAutoApprove(sid)
        // 标记「已请求停止」：run 还没建好时 rid 为空，光置 finished 挡不住已在飞行的
        // startRun 请求——它返回后据这个标记补发 stopRun（见 startRunWith）。
        r.stopRequested = true
        // 把当前轮作废：万一还有一条 startRun 的同步请求在飞，它回来时轮次号对不上，
        // 只会补发 stop 收掉自己，不会重新接管会话（F04）。
        r.invalidateTurnForStop()
        r.recoveryJob?.cancel()
        r.coalescer?.discard()
        if (a != null && rid.isNotEmpty()) identityScope.launch(Dispatchers.IO) {
            // 停止请求本身也可能没送达（网络问题/服务端报错）。旧代码吞掉异常、界面照样
            // 显示「已请求停止」，用户以为停了其实还在跑。这里如实回一句（F21）。
            val sent = a.stopRun(rid)
            withContext(Dispatchers.Main) {
                if (sent) {
                    // 服务端收下了停止请求：未确认意图作废（R08）。
                    if (r.stopIntentRid == rid) r.stopIntentRid = ""
                } else {
                    // 留着重试凭据，提示也改成「再点一次＝对同一条重试」，别再说得含糊（R08）。
                    r.stopIntentRid = rid
                    r.retryNote.value = "停止请求没送达，服务端可能仍在跑（再点一次停止会重试这条）"
                    AppLog.log("stop", "stopRun 未确认，保留重试 run=" + rid.take(12))
                }
            }
        }
        r.call?.cancel()
        r.sendJob?.cancel()
        appendDelta(r, "\n[已请求停止]")
        finishPending(r)
        // 用户主动停止本轮：还挂着的审批/澄清不可能再有答案（run 正在收尾），一并收起，
        // 否则那张卡片留在气泡里、顶层提示条也跟着挂着（用户 2026-10-11 报的另一半场景）。
        expireAllPendingCards(r, "用户停止本轮")
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        // 停止时把待发队列一起按住：不自动发下一条，等用户点「继续」。
        if (r.queue.isNotEmpty()) r.queuePaused.value = true else drainQueue(sid)
    }

    /**
     * 过程行的「耗时 + 明细」（秒，入参预览）。
     *
     * 耗时：优先服务端 `duration`（>0 才算，0 就是没给——旧实现把 0 当有效值，于是全是 0.0s），
     * 否则用 started 记下的本地时刻算墙钟差，都没有返回 -1（界面不显示耗时段）。
     * 明细：优先 started 事件的入参预览（`tool.completed` 在 canonical turn 链路上不带 preview），
     * 没有才回落完成事件自带的 preview。
     */
    private fun toolOutcome(sid: String, ev: com.hermesapp.net.SseEvent): Pair<Double, String> {
        val cid = ev.data.optString("tool_call_id", "")
        val run = if (cid.isEmpty()) null else toolStartedAt.remove(sid + "|" + cid)
        val srv = ev.data.optDouble("duration", -1.0)
        val dur = if (srv > 0.0) srv else run?.let {
            val ms = System.currentTimeMillis() - it.startMs
            if (ms > 0) ms / 1000.0 else -1.0
        } ?: -1.0
        return dur to (run?.preview.orEmpty())
    }

    /** 工具轨迹追加到该会话当前 assistant 消息的 trace（界面默认折叠，不进正文）。 */
    private fun appendTrace(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(trace = list[i].trace + d)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), trace = d))
        setMsgs(r, list)
    }

    /**
     * 把「本轮召回 N 条记忆」写到当前这一轮的助手气泡上（每轮一行，界面里放在正文之前）。
     *
     * 三条纪律（用户 2026-10-14 要求「每轮开始都要有召回多少条记忆的显式」）：
     * ① 只在**进行中**的气泡上写；没有就退到最近一条助手消息，都没有才新建占位气泡——
     *    召回事件比正文先到是常态，不能因为它先到就丢掉；
     * ② 同一句不重复写（断流重连时服务端会把 backlog 重放一遍，否则重复挂）；
     * ③ 落盘由 `Msg.recall` 随消息走（SessionStore 存/读都带上），重开 App 这一行还在。
     */
    private fun attachRecall(r: SessionRuntime, line: String) {
        if (line.isEmpty()) return
        val list = r.messages.value.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) {
            if (list[i].recall == line) return
            list[i] = list[i].copy(recall = line)
        } else {
            list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), recall = line))
        }
        AppLog.log("stream", "本轮召回提示已挂到气泡：" + line)
        setMsgs(r, list)
    }

    private fun appendDelta(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = list[i].text + d)
        else list.add(Msg("assistant", d, pending = true, ts = stamp(), startedAt = turnStart(r)))
        setMsgs(r, list)
    }

    /**
     * 给最后一条助手消息记上本轮 run_id：语音重播按钮靠它取音频。
     * 必须在 setPendingText/finishPending 之前调用——那两步用的是 copy()，
     * 先记上才能被带过去；记晚了这条就永远没有按钮。
     */
    private fun tagLastAssistantRunId(r: SessionRuntime, runId: String) {
        if (runId.isEmpty()) return
        // 会话级也记一份：合并后重挂用（见 withTurnMeta）。语音播报按钮靠它。
        r.turnRunId = runId
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" }
        if (i < 0 || list[i].runId.isNotEmpty()) return
        list[i] = list[i].copy(runId = runId)
        setMsgs(r, list)
    }

    private fun setPendingText(r: SessionRuntime, t: String) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = t, pending = false)
        setMsgs(r, list)
    }

    private fun finishPending(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(pending = false)
        setMsgs(r, list)
    }

    private fun failPending(sid: String) {
        val r = rt(sid)
        // 本轮收尾：本次任务的「全部批准」到此为止（下一轮重新问）。
        disarmAutoApprove(sid)
        r.recoveryJob?.cancel()
        clearStaleCards(r)
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        // 同上：本轮失败了也要把「回前台该同步」这件事补上，否则界面会一直停在旧内容。
        if (r.needSync) {
            r.needSync = false
            AppLog.log("sync", "收尾补拉（本轮失败）sid=" + sid.take(8))
            refreshFromServerFor(sid)
        }
        updateRunService()
        drainQueue(sid)   // 本轮失败也要把排队的下一条发出去，别把队列卡死
    }

    private fun doneOk(sid: String) {
        val r = rt(sid)
        // 本轮收尾：本次任务的「全部批准」到此为止（下一轮重新问）。
        disarmAutoApprove(sid)
        r.autoContinue = 0
        r.recoveryJob?.cancel()
        r.retryNote.value = ""
        clearStaleCards(r)
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        // 回前台时若因本轮在跑而没能同步，收尾后补拉一次：后台期间别的端产生的轮次、
        // 或本地漏收的事件，靠它补齐。标记先清掉，避免反复拉。
        if (r.needSync) {
            r.needSync = false
            AppLog.log("sync", "收尾补拉 sid=" + sid.take(8))
            refreshFromServerFor(sid)
        }
        updateRunService()
        if (sid == _currentId.value) drainPendingReply()
        drainQueue(sid)   // 本轮结束：把排队的下一条发出去
        // 本轮跑完：服务端此刻多半已生成了正式标题，同步一次（节流 5 秒）。
        syncFromServer(5_000L)
    }

    /**
     * 「后台运行」开着就保持前台服务与常驻通知，跟有没有任务在跑无关；关掉才停。
     * 原来判据是「有任务才起、跑完就停」，所以没任务时切后台，状态栏一条通知都不剩。
     */
    private fun updateRunService() {
        val running = runtimes.filterValues { it.busy.value }.keys.toSet()
        // 顺带刷一次侧边栏提示：子任务/排队也算「有动静」，只按 busy 打点会漏。
        refreshRunFlags()
        // 治本：只要有任务在跑就举牌保活（抄 relay 的「活跃轮次登记」），
        // 不再只看「后台运行」开关：开关关着时切后台照样会被系统冻结，
        // 进度与完成通知都丢。
        if (!prefs.keepAlive && running.isEmpty()) {
            AppLog.log("service", "停前台服务（后台运行关 且 无任务）")
            RunService.stop(getApplication())
            return
        }
        // Android 12+ 禁止从后台启动前台服务：后台硬启会撞墙，反而触发
        // ForegroundServiceDidNotStartInTime 崩溃。切回前台时 onAppForeground
        // 会再调一次这里把服务补上。
        if (AppForeground.isForeground) {
            AppLog.log("service", "起前台服务 运行中会话=" + running.size + " keepAlive=" + prefs.keepAlive)
            RunService.start(getApplication(), running.isNotEmpty())
        } else {
            // 后台不许**新起**前台服务，但已在跑的服务可以把 active 推给它（R17）：
            // 最后一个任务在后台结束时，旧代码既不 start 也不 stop，CPU/Wi-Fi 锁一直占着；
            // 现在推 active=false，服务收到即释放锁（常驻通知不变）。
            AppLog.log("service", "后台更新服务 active=" + running.isNotEmpty() + " 运行中会话=" + running.size)
            RunService.updateActive(getApplication(), running.isNotEmpty())
        }
    }

    /** 进主界面时调一次：开关开着但还没发过消息，也要把常驻通知挂上。 */
    fun ensureRunService() {
        updateRunService()
        // 兜底闹钟（15 分钟粒度）：进程被系统回收后仍能补提醒，见 KeepAlive.kt。
        KeepAlive.arm(getApplication())
    }

    /**
     * 侧边栏会话说状态提示的心跳：每 2 秒扫一遍内存里的运行态，有变化才推给界面。
     *
     * 为什么用「定时扫」而不是在每处状态变化打点：状态有三个来源（本地 busy、子任务行、
     * 待发队列），而子任务是个后台子代理——它可能比父轮次活得久，靠「任务开始/结束」打点
     * 会漏掉这段窗口。扫描纯读内存、不发请求，开销可忽略；值没变就不推，界面也不重组。
     */
    private fun startRunFlagsTicker() {
        if (runFlagsTicker?.isActive == true) return
        runFlagsTicker = identityScope.launch {
            var tick = 0
            while (isActive) {
                refreshRunFlags()
                // 断流自愈看门狗：跟状态扫描同一趟（纯读内存，不发请求；只有真判定「没流」时才起流）。
                superviseStreams()
                // 每 ~30 秒顺带刷新一次服务端会话列表：把「别的端刚起的轮次」收进来（active_run），
                // 交给 syncFromServer 内部的 reconcileServerRuns 接管。不带这个，用户开着 App
                // 时别的端起的任务要等切前后台/切会话才被发现。
                tick++
                // 会话列表轮询节流（2026-10-13 省电）：有任务在跑 30 秒一轮，空闲 120 秒一轮；
                // 响应侧也精简了（compact=1）。原来固定 30 秒 × 167KB = 11.5 MB/小时。
                val period = ReconnectPolicy.sessionsSyncTicks(
                    runtimes.values.any { it.busy.value } || _serverRuns.value.isNotEmpty())
                if (tick % period == 0 && AppForeground.isForeground) syncFromServer(period * 2_000L)
                delay(2_000L)
            }
        }
    }

    /**
     * 断流自愈看门狗：每 2 秒扫一遍内存，凡是「本轮还在跑、手上却没有一条活着的流」的会话，
     * 立刻把流重起。
     *
     * 为什么必须有一个兜底，而不是把三条重连链各自修好就够：断流后的重连有**三个已知的
     * 静默出口** —— ① 流代际号把这条流的回调作废；② 同会话探测的单飞位没释放，之后每次
     * 重连都被挡回；③ 退避 8 次用尽转「翻历史」后，那条分支只留一行提示、不起任何任务。
     * 2026-10-13 真机现场就是这么停住的：01:33:37 起流的任务，流 01:45:59 断掉，之后十分钟
     * App 一次都没再连（同期健康轮询正常），界面上「正在执行」永久停住。
     *
     * 判据只看**事实**（有没有流、流多久没动静，见 ReconnectPolicy.streamNeedsRestart），
     * 不看是哪条链挂了 —— 以后新加的静默出口也自动被兜住。
     *
     * 成本：纯读内存 + 复用已经在跑的 2 秒心跳，零新增请求。只有真判定没流时才发一次
     * /events（那本来就是这个会话该有的长连接），同一会话 15 秒内最多重起一次。
     * 正在跑状态轮询/翻历史的会话跳过：那两条链自己会接流，别跟它们抢。
     */
    private fun superviseStreams() {
        val a = api ?: return
        val now = System.currentTimeMillis()
        for (r in runtimes.values) {
            if (r.recoveryJob?.isActive == true) continue
            val call = r.call
            val since = if (r.lastEventAt > 0) now - r.lastEventAt else Long.MAX_VALUE
            val need = ReconnectPolicy.streamNeedsRestart(
                busy = r.busy.value,
                finished = r.finished,
                hasRunId = r.runId.isNotEmpty(),
                hasCall = call != null,
                callCanceled = call?.isCanceled() == true,
                msSinceLastEvent = since,
            )
            if (!need) continue
            if (now - r.lastStreamRestartAt < ReconnectPolicy.STREAM_RESTART_THROTTLE_MS) continue
            r.lastStreamRestartAt = now
            AppLog.log("stream", "看门狗重起流 sid=" + r.id.take(8) + " run=" + r.runId.take(12) +
                "（" + (if (call == null) "没有流" else if (call.isCanceled()) "流已取消"
                        else "流静默 " + since + "ms") + "）")
            // 与回前台体检同一套：退避归零 + 作废历史轮询，让流式续接优先于「只等最终答案」。
            r.autoContinue = 0
            r.recoveryJob?.cancel()
            if (r.retryNote.value.isEmpty()) r.retryNote.value = ReconnectPolicy.noteReconnecting()
            streamRun(a, r.id)
        }
    }

    /** 重算每条会话的运行态摘要（只留有动静的），并同步旧的 runningIds。 */
    private fun refreshRunFlags() {
        val out = LinkedHashMap<String, SessionRunFlag>()
        for ((sid, r) in runtimes) {
            val remote = _serverRuns.value.containsKey(sid) && !r.busy.value
            val flag = SessionRunFlag(
                busy = r.busy.value,
                // 正在跑的子任务数：子代理可能比父轮次活得久，这是「会话里还有活」的证据。
                subagents = r.messages.value.sumOf { m -> m.subagents.count { it.status == "running" } },
                queued = r.queue.size,
                remote = remote,
            )
            if (flag.active) out[sid] = flag
        }
        // 服务端在跑、本地还没建 runtime 的会话（别的端起的轮次）也要亮角标。
        for (m in _sessions.value) {
            if (_serverRuns.value.containsKey(m.id) && out[m.id] == null) {
                out[m.id] = SessionRunFlag(remote = true)
            }
        }
        if (out != _runFlags.value) _runFlags.value = out
        val busyIds = out.filterValues { it.busy }.keys
        if (busyIds != _runningIds.value) _runningIds.value = busyIds
        // 顺手收掉「本轮早已结束、审批却从未被回答」的死卡片：会话空闲（本地不忙、无 runId、
        // 服务端也没有在跑的 run）时它不可能再被回答，留着就是一条点不动的假提示。
        // 放在这个 2 秒心跳里是为了覆盖所有入口（冷启动读盘、切会话、回前台、轮末）——
        // 分散打点必漏，而这里只读内存、无未处理卡片时立即返回。
        for (sid in runtimes.keys.toList()) {
            if (hasUnresolvedCard(sid)) expireCardsWhenIdle(sid)
        }
    }

    /** 该会话里还有没有「未处理且未失效」的卡片（心跳里的廉价前置判断，避免每 2 秒拼列表）。 */
    private fun hasUnresolvedCard(sid: String): Boolean {
        val msgs = runtimes[sid]?.messages?.value ?: return false
        return msgs.any { m ->
            (m.approval?.resolved?.isEmpty() == true && m.approval?.expired != true) ||
                (m.clarify?.resolved?.isEmpty() == true && m.clarify?.expired != true)
        }
    }

    /**
     * 任务停下来等人点头（审批/澄清）时弹提醒。
     *
     * 2026-10-08 改：原来「App 在前台就一律不弹」，导致用户开着 App 但在看别的页面 /
     * 别的会话时，审批来了完全没提示 —— 静默错过（用户报「经常静默错过」）。
     * 新判据：只有「就在当前这个会话里」才不弹（卡片已在屏幕上），其余情况都弹，前台也弹。
     */
    private fun notifyNeedAction(sid: String, title: String, text: String) {
        val onThisSession = ChatVisibility.visible() && sid == _currentId.value
        if (onThisSession) return
        // 落盘一份：App 若在用户点通知前被系统杀掉，冷启动的 Intent extra 可能丢，
        // 靠这份落盘仍能跳回那条待处理卡片。
        prefs.pendingOpenSession = sid
        val app = getApplication<Application>()
        val body = text.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "有任务在等你处理" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyAction(app, title, body, sid)
    }

    /**
     * 探测兜底收尾时也要通知+播报。
     *
     * 为什么需要：通知与语音原来只写在 run.completed 分支里，而流被掐断时那条事件
     * 根本收不到，只能靠探测「Known(completed)」收尾——结果任务跑完了手机不响、
     * 不播报（实测 2.88 用户报「没有任务完成的通知」）。这里用服务端返回的 output，
     * 没有就回落本地最后一条助手正文。
     */
    private fun notifyRecoveredCompletion(sid: String, out: String) {
        val r = rt(sid)
        val text = out.ifEmpty {
            r.messages.value.lastOrNull { it.role == "assistant" && it.text.isNotEmpty() }?.text.orEmpty()
        }
        if (text.isEmpty()) return
        notifyCompletion(sid, text)
        VoicePlayer.playFromReply(getApplication(), text, prefs.playCompletionVoice)
    }

    /**
     * 任务完成提醒。
     * 后台：一直提醒（原行为），前提是「后台运行」开着——否则根本没有连接能收到事件。
     * 前台：只在「别的会话跑完」且用户开了「其它会话完成也提醒」时弹；
     *       当前会话的内容就在屏幕上，再弹是骚扰。
     */
    /** 任务完成提醒（2026-10-10 方案 B：前台/后台、当前/其他会话一律通知 + 震动 + 响铃）。 */
    private fun notifyCompletion(sid: String, output: String) {
        if (output.isEmpty()) return
        AppLog.log("notif", "notifyCompletion sid=" + sid.take(8) + " len=" + output.length)
        val app = getApplication<Application>()
        val body = output.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "任务已完成" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyDone(app, "任务完成", body, sid)
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
        // 关掉开关也不能直接停：还有任务在跑就得继续举牌。
        updateRunService()
    }

    /** 设置页「其它会话完成也提醒」：需一条活连接才能观察到别的会话收尾。 */
    fun setNotifySessionCompletions(on: Boolean) {
        prefs.notifySessionCompletions = on
    }

    /** 设置页「完成语音播报」：任务跑完自动播服务端下发的整段语音。 */
    fun setPlayCompletionVoice(on: Boolean) {
        prefs.playCompletionVoice = on
        if (!on) { VoicePlayer.stop(); StreamVoicePlayer.stop(); VoiceReplayPlayer.stop() }
    }

    /**
     * 提示条上的「跳过」：跳过当前正在播的这条语音，接着播队列里的下一条。
     * 流式那条在播就跳过它；否则是手动重播在响，停掉它（会接着推进流式队列）。
     */
    fun skipVoice() {
        if (StreamVoicePlayer.nowPlaying.value.isNotEmpty()) {
            StreamVoicePlayer.skipCurrent()
        } else {
            VoiceReplayPlayer.stop()
        }
    }

    /**
     * 设置页调播报语速：写入 prefs 并立刻灌进**三个**播放器。
     *
     * 为什么必须三个都灌：自动播报走 StreamVoicePlayer、重播按钮走 VoiceReplayPlayer、
     * 老的整段附件走 VoicePlayer。它们各自持有 rate 字段、互不同步；只灌 VoicePlayer 时，
     * 设置页改完语速对自动播报（流式）与点重播都不生效——正是用户报的「语速功能失效」。
     */
    fun setVoiceRate(rate: Float) {
        prefs.voiceRate = rate
        VoicePlayer.rate = rate
        StreamVoicePlayer.rate = rate
        VoiceReplayPlayer.rate = rate
        // 正在播的流式语音立刻变速（ExoPlayer 支持播放中改速）；重播是 MediaPlayer，
        // 用 playWhenReady 的实时 setSpeed 会在部分机型上抛异常，故只对新播放生效。
        StreamVoicePlayer.applyRateNow()
    }

    // ---------- 自更新 ----------

    /** 只检查，不下载。有新版本时挂到 pendingUpdate，由界面弹确认框。 */
    fun checkUpdate(currentVersionCode: Int, ctx: Context) {
        val a = api ?: return
        _updateNote.value = "检查中…"
        _pendingUpdate.value = null
        identityScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate()
                if (info == null) {
                    _updateNote.value = "检查失败：无法获取版本信息"
                    return@launch
                }
                if (info.versionCode <= currentVersionCode) {
                    _updateNote.value = "已是最新版本 " + info.versionName
                    _updateBadge.value = false
                    return@launch
                }
                _updateNote.value = ""
                _updateBadge.value = true
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
        identityScope.launch(Dispatchers.IO) {
            try {
                val f = a.downloadApk(info, ctx) { done, total ->
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        _downloadPct.value = pct
                        _downloadText.value = pct.toString() + "%  " + fmtSize(done) + "/" + fmtSize(total)
                    } else {
                        _downloadText.value = fmtSize(done)
                    }
                }
                if (f == null) {
                    _updateNote.value = "下载失败（安装包校验没过或链路中断，请重试）"
                    AppLog.log("update", "下载/校验失败，未交给安装器")
                    _downloadPct.value = -1
                    return@launch
                }
                _downloadPct.value = 100
                _downloadText.value = "100%"
                _updateNote.value = "下载完成，请在弹出的提示中安装"
                AppLog.log("update", "拉起安装器 " + f.absolutePath)
                launch(Dispatchers.Main) { installApk(ctx, f) }
            } catch (e: Exception) {
                _updateNote.value = "下载失败：" + (e.message ?: "?")
                AppLog.log("update", "下载异常：" + (e.message ?: "?"))
                _downloadPct.value = -1
            }
        }
    }

    /**
     * ViewModel 销毁：取消挂在进程级 scope 上的轮询 ticker。
     *
     * 原来没有 onCleared，每次 Activity 重建都会在 identityScope 上新起一条永不取消的
     * ticker（字段在新实例里是 null，幂等守卫拦不住），越积越多。这里显式收掉。
     */
    /** Retire only local observers; server runs and paused queues remain recoverable. */
    fun detachIdentity() {
        if (detached) return
        detached = true
        identityJob.cancel()
        VoicePlayer.stop()
        VoiceReplayPlayer.stop()
        StreamVoicePlayer.stop()
        MediaFetch.handler = null
        VoiceReplayPlayer.fetcher = null
        for (r in runtimes.values) {
            r.coalescer?.flushNow()
            r.streamGen++
            r.call?.cancel()
            r.call = null
            r.saveJob?.cancel()
            r.recoveryJob?.cancel()
            r.subSweep?.cancel()
            if (!r.dead && r.loaded) {
                store.saveMessages(r.id, r.messages.value)
                store.saveQueue(r.id, r.queue.toList(), r.queuePaused.value)
                if (r.runId.isNotEmpty()) {
                    prefs.putActiveRun(r.id, r.runId)
                    prefs.putLastSeq(r.id, r.lastSeq)
                }
            }
        }
        if (indexReady) store.saveIndex(_sessions.value)
        runtimes.clear()
        runCatching { Notifier.clearBusinessNotifications(getApplication()) }
    }

    override fun onCleared() {
        detachIdentity()
        runFlagsTicker?.cancel()
        runFlagsTicker = null
        cpuTicker?.cancel()
        cpuTicker = null
        stallWatch?.cancel()
        stallWatch = null
        super.onCleared()
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
