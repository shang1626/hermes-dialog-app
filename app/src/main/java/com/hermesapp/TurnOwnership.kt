package com.hermesapp

/**
 * 「这一轮发送是否仍然拥有当前会话」——纯函数，无副作用，便于单测。
 *
 * 背景（F04）：一轮发送的收尾状态（finished / stopRequested / runId / 活跃 run）
 * 是存在「每个会话一份」的共享变量里的，而 `startRun` 是**同步 HTTP**，协程取消拦不住它。
 * 于是「停止 A → 立刻发 B → A 的响应迟到」时，B 已经把共享布尔重置为 false，
 * A 回来看见「没有停止标记」就以为自己还是当前轮，把 B 的 runId 顶掉、重新接流。
 *
 * 修法就是给每次发送发一个轮次号（`SessionRuntime.sendGen`，发送与停止各 +1）。
 * 迟到的响应先对号：对不上 = 自己已被新发送或停止取代 → 只补发 stop 收掉自己那条 run，
 * **绝不写任何共享状态、绝不接流**。
 *
 * @param myGen 本轮发起时拿到的轮次号
 * @param curGen 会话当前轮次号（每发起一次发送 / 每次停止都会 +1）
 * @param finished 本轮是否已被收尾
 * @param stopRequested 用户是否已请求停止
 */
internal fun ownsTurn(myGen: Int, curGen: Int, finished: Boolean, stopRequested: Boolean): Boolean =
    myGen == curGen && !finished && !stopRequested
