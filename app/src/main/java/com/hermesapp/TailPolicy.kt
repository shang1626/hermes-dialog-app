package com.hermesapp

/**
 * 「退出界面后这一轮还该不该继续跑」的判据（纯函数，便于回归）。
 *
 * 为什么单独抽出来：这是「回复消失」那条链的开关。判错的两种代价都很实在——
 *  - 该留的没留：界面一销毁就掐流，服务端跑完的正文没人接，App 里永远缺一条回复；
 *  - 不该留的留了：已经收尾/已删除/登出切换身份的运行态还挂在进程里，
 *    旧身份的流会往新身份的会话里写正文（数据错乱，红线）。
 */
internal object TailPolicy {

    /**
     * @param keepTail 调用方语义：true = Activity 销毁/重建（onCleared）；false = 登出或切换身份。
     * @param dead      会话已删除/作废：一个字都不许再写。
     * @param runId     当前挂着的 run：空 = 没有正在跑的轮次。
     * @param finished  本轮已收尾（run.completed 已到，只剩语音尾巴）。
     */
    fun keepOnDetach(keepTail: Boolean, dead: Boolean, runId: String, finished: Boolean): Boolean =
        keepTail && !dead && runId.isNotEmpty() && !finished

    /** 进程里是否还有需要跑完的轮次（退出界面后的守护据此决定继续还是收工）。 */
    fun hasLiveTail(runtimes: Collection<SessionRuntime>): Boolean =
        runtimes.any { !it.dead && it.runId.isNotEmpty() && !it.finished }
}
