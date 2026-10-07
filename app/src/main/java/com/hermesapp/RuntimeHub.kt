package com.hermesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级会话运行态仓库（治本：Activity 重建 / 切后台回来不丢进度、不重复接流）。
 *
 * 为什么必须放在进程级：原实现把 runtimes 放在 ChatViewModel 里，而 ViewModel 会随
 * Activity 一起被系统销毁重建（息屏、内存紧张、旋转、从通知栏回到 App 都可能触发）。
 * 新建的 ViewModel 里，流代际号 streamGen 与续接计数 autoContinue 都从 0 重来，
 * 与旧实例那条仍在跑的流各执一份运行态，同一个 run 就挂上了两条流：
 * 进度被两条流互相搅乱（看着像「进度没了」），完成事件也可能落在已被作废的那条上，
 * 于是通知不弹。实测日志：02:45:04 起流 gen=2，02:45:37 又出现「第0次续接 gen=1」。
 *
 * 放进 object 后，整机只有一份运行态：新 ViewModel 拿到的是同一个对象、同一个 streamGen、
 * 同一个 call，回前台时能直接接管旧流（取消它并续接），而不是另起一条。
 */
internal object RuntimeHub {
    /** 键 = 会话 id；进程内唯一，跨 Activity/ViewModel 重建保持。 */
    val runtimes = ConcurrentHashMap<String, SessionRuntime>()

    fun rt(id: String): SessionRuntime = runtimes.computeIfAbsent(id) { SessionRuntime(it) }
    /**
     * 进程级协程作用域：流式攒帧、落盘、退避重连、翻历史都挂这里，
     * 而不是 viewModelScope。挂 viewModelScope 的话，Activity 一被系统重建，
     * 这些任务全部随旧实例陪葬——表现就是「进度卡住不动、跑完了才一次性冒出来」。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}


/**
 * 一个会话的运行态。多会话并行：每个会话各自持有消息、忙闲、当前 run，
 * 切换会话只换「正在看的」id，后台会话的流照常接收、写进它自己的缓冲。
 */
internal class SessionRuntime(val id: String) {
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val busy = MutableStateFlow(false)
    val retryNote = MutableStateFlow("")
    var runId: String = ""
    var call: Call? = null
    /**
     * 流的代际号：每起一条新流就 +1。被新流取代的旧流，其回调据此自我作废——
     * 否则两条流会同时收 run.completed，正文、通知与语音都执行两遍（实测 2.81 回前台重复播报）。
     */
    var streamGen: Int = 0
    var lastSeq: Int = -1
    var autoContinue: Int = 0
    /**
     * 退避探测单飞位：同一会话同时只允许一条退避链在等。
     * 多口子（onClosed / onError / 回前台体检）并发进来时，只有一个能排上重试。
     */
    val probing = java.util.concurrent.atomic.AtomicBoolean(false)
    var finished: Boolean = false
    /** 这条 run 是重开 App 后从落盘标记恢复的（没有本地发送上下文，拿不到位置锚点）。 */
    var resumed: Boolean = false
    var startedAt: Long = 0L
    var loaded: Boolean = false
    var saveJob: Job? = null
    /** 最近一次收到事件的墙钟时间，用于「回到前台」判断流是否已假死。 */
    var lastEventAt: Long = 0L
    /** 本轮（当前这条流）收到的 SSE 事件条数：排查「步骤不显示」时看事件到底到没到。 */
    var evCount: Int = 0
    /** 本轮收到的工具事件（tool.started/completed/failed）条数。 */
    var toolCount: Int = 0
    /** 流式攒帧器：把碎字按帧放送，避免一大块一大块地跳。 */
    var coalescer: StreamDeltaCoalescer? = null
    /** 本轮发送前该会话已有多少条用户消息：断流翻历史时的位置锚点。 */
    var priorUserCount: Int = 0
    /** 本轮待发正文（trim 过）：锚点的内容校验用。 */
    var pendingSendText: String = ""
    /** 最近一条用户消息的 id：投递回执按它认领。 */
    var lastUserMsgId: Long = 0L
    /** 「确认送达」进行中要回写的用户消息 id；0 表示没有。 */
    var confirmingMsgId: Long = 0L
    /** 断流翻历史的轮询任务；收到正常事件或任务结束时取消。 */
    var recoveryJob: Job? = null
    /**
     * 「待同步」标记：回前台想同步、但该会话此刻正在跑（服务端记录还是半成品），
     * 就先记下，等本轮收尾（doneOk / failPending）时自动补拉一次。
     * 放在会话运行态上而不是函数局部——收尾可能发生在另一个 ViewModel 实例里。
     */
    var needSync: Boolean = false
    /**
     * 子任务进度轮询：本会话有子代理在跑时挂一条，全部收工自动退出。
     * 加在会话运行态上（而不是函数局部）——Activity 被系统重建后，进度还接着刷。
     */
    var subSweep: Job? = null
    /** 本会话排队待发的消息（跑着任务时用户又发的那些），按先后顺序，本轮结束依次发。 */
    val queue = mutableListOf<QueuedSend>()
    /** 队列长度：输入栏显示「排队 N 条」。 */
    val queued = MutableStateFlow(0)
    /** 用户按过停止后置真：待发队列暂停，等「继续」再走。 */
    val queuePaused = MutableStateFlow(false)
}
