package com.hermesapp

/**
 * 从 ChatViewModel 抽出的纯逻辑（历史合并 / 锚点 / 富文本片段）。
 * 无实例依赖、无 UI 依赖，便于单测覆盖——动主状态机前的测试网。
 */
/**
 * 合并服务端与本地记录时，认定「同一条用户消息」的时间窗口。
 * 服务端压缩会重发行 id、改写老行，但保留原时间戳；手机与服务端钟差 + 发送延迟
 * 一般远小于这个窗口，超过即不认（避免把两句同文本的重复提问错配成一条）。
 */
internal const val REWRITTEN_ROW_WINDOW_MS = 120_000L

/**
 * 匹配分数里的「配上一对」奖励（R07）。取值远大于任何时间差（窗口 120s = 120000ms），
 * 保证「多配上一对」永远优先于「总时间差更小」——贪心错配的根因正是把后者放到了前面。
 */
private const val MATCH_BONUS = 1_000_000_000L

/** 被引正文压缩成一行片段（最多 80 字），服务端与气泡共用。 */
internal fun quoteSnippet(m: Msg): String {
    val one = m.text.replace(Regex("\\s+"), " ").trim()
    val who = if (m.role == "user") "我" else "助手"
    val body = if (one.isEmpty()) "[图片或附件]" else if (one.length > 80) one.take(80) + "…" else one
    return who + "：" + body
}
internal fun toolLine(ev: com.hermesapp.net.SseEvent, failed: Boolean): String {
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
/** 位置锚点：第 prior+1 条用户消息的下标；对不上返回 -1（不硬认）。 */
internal fun resolveAnchor(rows: List<HistRow>, pendingText: String, prior: Int): Int {
    // prior < 0 必须挡在最前（R15）：插话在途重启后，回执没有 prior（读回 -1），
    // 确认路径会把这 -1 传进来；旧代码只查 size <= prior，于是 userIdx[-1]
    // 直接抛 IndexOutOfBoundsException（本机探针已复现）。
    if (prior < 0) return -1
    val userIdx = rows.indices.filter { rows[it].role == "user" }
    if (userIdx.size <= prior) return -1
    val pos = userIdx[prior]
    if (rows[pos].text.trim() != pendingText) return -1
    return pos
}
/** 稳定性签名：锚点之后最后一条非空助手消息；带总条数，服务端还在追加时会变。 */
internal fun answerSignature(rows: List<HistRow>, anchor: Int): String? {
    val ans = rows.drop(anchor + 1)
        .lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return null
    return "${rows.size}|${ans.id}|${ans.text.length}"
}
/**
 * 服务端会话记录与本地消息的合并——按「用户消息内容」对齐，不按数组下标。
 *
 * 为什么不能按下标：/api/sessions/{id}/messages 会滤掉空内容的助手行（本地保留，
 * 它们带工具轨迹 trace），且**长会话被服务端压缩时老行会被删/合并**——两边用户消息
 * 条数天然不等，按「第几条」对齐会从第一处差异起整体错位（把服务端回复贴到错误的
 * 气泡上、用户消息重复出现）。用户消息正文两侧基本原样保留，所以拿「正文归一 + 时间
 * 接近」做顺序匹配：服务端被压缩删掉的那条匹配不上就跳过，不影响其后各块的对齐。
 */
internal fun mergeByUserAnchor(local: List<Msg>, srv: List<Msg>): List<Msg> {
    fun isUser(m: Msg) = m.role == "user"
    fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    // 各自切块：head = 第一条用户消息之前的行；随后每个用户消息带一个 tail
    //（其后、下一条用户消息之前的助手/系统行）。
    fun blocks(ms: List<Msg>): Triple<MutableList<Msg>, MutableList<Msg>, MutableList<MutableList<Msg>>> {
        val head = mutableListOf<Msg>()
        val users = mutableListOf<Msg>()
        val tails = mutableListOf<MutableList<Msg>>()
        var started = false
        for (m in ms) {
            if (isUser(m)) { started = true; users.add(m); tails.add(mutableListOf()) }
            else if (started) tails.last().add(m) else head.add(m)
        }
        return Triple(head, users, tails)
    }
    val (lHead, lUsers, lTails) = blocks(local)
    val (sHead, sUsers, sTails) = blocks(srv)

    // 用户消息顺序匹配：正文归一相同（时间做二次确认，避免同文本误配）才认成同一条。
    // 服务端压缩删掉的老用户消息匹配不上 → 跳过，不影响其后各块。
    // 同文本重复时，服务端那一行要留给「时间最吻合」的那条本地消息（F11）。
    // 场景：用户连发两次「继续」，服务端压缩把**更老**的那条删了、只留第二条。旧实现是
    // 「遇到第一个未匹配的服务端行就认」，于是第一条「继续」把它抢走——第二轮的答案被贴到
    // 第一轮、第一轮还凭空多出一条助手行（本机实测合并结果正是
    // 「继续 | 第二轮回答 | 第一轮回答 | 继续 | 第二轮回答」）。
    // 裁决规则：若**后面还有**同文本、且与该服务端行时间更接近的本地消息，就把这一行让给它；
    // 没有时间戳的老数据（<=0）维持原来的先到先得。
    // 用户消息顺序匹配（R07 重写）：旧实现是「后来者时间更近就把这一行让给它」的贪心，
    // 对**恒定时钟偏差**会错配——两句「继续」与两行服务端记录（本地 1000/2000、后台 1800/2800）
    // 被发现会交错贴错轮次（实测输出 继续/第一轮/继续/第一轮/第二轮/继续/第二轮）。
    // 改为在「同正文 + 时间在窗口内 + 顺序不倒退」的约束下做一次全局最优匹配：
    // 先最大化配上的条数，再最小化总时间差。这样「服务端压缩只剩后一条」（F11）
    // 与「两边都在、只差一个固定钟差」（R07）都能配对正确。
    // 时间与正文仅作保守候选；无时间戳的老数据不参与差值裁决（按顺序配对）。
    val l2s = IntArray(lUsers.size) { -1 }
    run {
        val n = lUsers.size
        val m = sUsers.size
        val normSrv = Array(m) { norm(sUsers[it].text) }
        val normLoc = Array(n) { norm(lUsers[it].text) }
        fun tsDiff(li: Int, sj: Int): Long {
            val a = lUsers[li]; val b = sUsers[sj]
            if (a.ts <= 0 || b.ts <= 0) return 0L
            return kotlin.math.abs(a.ts - b.ts)
        }
        fun matchable(li: Int, sj: Int): Boolean {
            val na = normLoc[li]
            if (na.isEmpty() || na != normSrv[sj]) return false
            val a = lUsers[li]; val b = sUsers[sj]
            if (a.ts <= 0 || b.ts <= 0) return true
            return kotlin.math.abs(a.ts - b.ts) <= REWRITTEN_ROW_WINDOW_MS
        }
        val dp = Array(n + 1) { LongArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                var best = maxOf(dp[i - 1][j], dp[i][j - 1])
                if (matchable(i - 1, j - 1)) {
                    val cand = dp[i - 1][j - 1] + MATCH_BONUS - tsDiff(i - 1, j - 1)
                    if (cand > best) best = cand
                }
                dp[i][j] = best
            }
        }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val cur = dp[i][j]
            if (matchable(i - 1, j - 1) && cur == dp[i - 1][j - 1] + MATCH_BONUS - tsDiff(i - 1, j - 1)) {
                l2s[i - 1] = j - 1
                i--; j--
                continue
            }
            if (cur == dp[i - 1][j]) { i--; continue }
            j--
        }
    }
    val srvMatched = BooleanArray(sUsers.size)
    for (m in l2s) if (m >= 0) srvMatched[m] = true

    fun mergeTail(lt: List<Msg>, st: List<Msg>): List<Msg> {
        if (lt.isEmpty()) return st
        if (st.isEmpty()) return lt
        val seg = lt.toMutableList()
        // ① 本地最后那条「正文为空的进行中气泡」：服务端有正文就补上
        //（保住它的 trace/计时/图片槽位，别另起一个新气泡）。
        val lastAsst = seg.indexOfLast { it.role == "assistant" }
        if (lastAsst >= 0 && seg[lastAsst].text.isEmpty()) {
            val sText = st.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
            if (!sText.isNullOrEmpty()) {
                seg[lastAsst] = seg[lastAsst].copy(text = sText, pending = false)
            }
        }
        // ② 本地「有正文的助手行」与服务端助手行做匹配（顺序贪心 + 正文包含判定）。
        // 判重放宽到「包含」：服务端压缩/改写会让同一段正文两侧不完全等长，只比相等会把
        // 改写过的旧行当成新行，重复贴一条。
        //
        // ⚠️ 2026-10-08 修：**不能用共享游标单遍扫描**。本地行乱序时（最终答复曾被历史
        // 版本 append 到块首），本地第一条就匹配到服务端最末行、游标跳到末尾，其后所有
        // 本地行永远匹配不上 → 一边把服务端 0..N-1 当「本地没有」插进来、一边把它们当
        // 「本地独有」追加 → 每次同步重复堆叠（实测 300→349→387…，且顺序仍颠倒）。改为
        // 「在未匹配的本地行里找第一个命中」，乱序也能对齐。
        val locIdx = seg.indices.filter { seg[it].role == "assistant" && seg[it].text.isNotBlank() }
        val usedLoc = mutableSetOf<Int>()
        val srv2loc = HashMap<Int, Int>()
        for (si in st.indices) {
            if (st[si].role != "assistant" || st[si].text.isBlank()) continue
            val ns = norm(st[si].text)
            for (li in locIdx) {
                if (li in usedLoc) continue
                val n = norm(seg[li].text)
                if (ns == n || ns.contains(n) || n.contains(ns)) { srv2loc[si] = li; usedLoc.add(li); break }
            }
        }
        // ③ 按服务端顺序重建这一块：命中就吐对应的本地行（保住内联图片、trace、runId
        // 这些服务端没有的东西），没命中就吐服务端行。顺序完全跟随服务端（id 插入序），
        // 本地乱序/缺失/多余的行都被纠正到正确位置。
        //
        // 2026-10-08：上一版是「按本地顺序遍历、边走边把服务端缺行前插」——本地行乱序时
        // 只能插不能重排，最终答复被历史版本堆到块首后，新代码看「行都在了」一条不补，
        // 顺序原样不动（用户报「重启+切会话没触发重排序」就是这个）。
        val out = mutableListOf<Msg>()
        val outNorm = mutableListOf<String>()
        var added = 0
        var filled = 0
        for (si in st.indices) {
            val li = srv2loc[si]
            val row = when {
                li != null -> {
                    // 命中服务端行：默认吐本地行（保住内联图片/trace/runId/计时）。
                    // 但本地那条正文若是残缺/被截断的（长度短于服务端、且被服务端包含），
                    // 会让断线重连后一直显示截断内容——此时只把正文与 pending 换成服务端完整版，
                    // 其余元数据原样保留。
                    val loc = seg[li]
                    val nLocal = norm(loc.text)
                    val nSrv = norm(st[si].text)
                    if (nLocal.length < nSrv.length && nSrv.contains(nLocal)) {
                        filled++
                        loc.copy(text = st[si].text, pending = false)
                    } else loc
                }
                st[si].role == "assistant" && st[si].text.isNotBlank() -> { added++; st[si].copy(pending = false) }
                else -> st[si]
            }
            out.add(row); outNorm.add(norm(row.text))
        }
        // 本地未匹配上的助手行：正文已在 out 里出现过（包含判定）就跳过——这样对「已被
        // 旧版堆叠污染」的块能自愈（实测 35/53 条 → 18 条），并保证重复合并幂等。
        for (li in locIdx) {
            if (li in usedLoc) continue
            val n = norm(seg[li].text)
            if (outNorm.any { it.isNotEmpty() && (it.contains(n) || n.contains(it)) }) continue
            out.add(seg[li]); outNorm.add(n)
        }
        // 进行中的空气泡占位（服务端暂无对应正文时）与非助手行：保留到末尾，别丢。
        for (k in seg.indices) {
            if (seg[k].role != "assistant" || seg[k].text.isBlank()) out.add(seg[k])
        }
        if (added > 0 || filled > 0) {
            AppLog.log("sync", "合并补齐助手正文 本地块=" + lt.size + " 服务端块=" + st.size +
                " 补入=" + added + " 补全=" + filled)
        }
        return out
    }

    val out = mutableListOf<Msg>()
    out.addAll(if (lHead.isNotEmpty()) lHead else sHead)
    // 服务端独有的用户块（被压缩改写 / 别的端发的轮次）按其原顺序插回对应位置：
    // 用「下一个本地已匹配块的 srv 索引」当边界，把边界之前未匹配的服务端块补进去。
    var sIdx = 0
    for (li in lUsers.indices) {
        val target = l2s[li]
        if (target >= 0) {
            while (sIdx < target) {
                if (!srvMatched[sIdx]) { out.add(sUsers[sIdx]); out.addAll(sTails[sIdx]) }
                sIdx++
            }
            sIdx = target + 1
        }
        out.add(lUsers[li])
        out.addAll(mergeTail(lTails[li], if (target >= 0) sTails[target] else emptyList()))
    }
    // 本地所有块之后，剩余未匹配的服务端用户块照原顺序补上。
    while (sIdx < sUsers.size) {
        if (!srvMatched[sIdx]) { out.add(sUsers[sIdx]); out.addAll(sTails[sIdx]) }
        sIdx++
    }
    return out
}
