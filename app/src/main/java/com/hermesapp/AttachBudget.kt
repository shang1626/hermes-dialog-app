package com.hermesapp

/**
 * 附件批量预算（F20）。
 *
 * 为什么需要：单文件上限（图片 20MB / 文件 50MB）与「最多 10 个」都不限制**总量**，
 * 于是「10 个 50MB」会被全部收下——发送时每个文件都要 readBytes() 驻留成一个 ByteArray，
 * 再各自 gzip、base64 进 JSON，峰值内存是原始总量好几倍（实测路径：全量 ByteArray →
 * gzip 输出 → base64 → JSON 字符串）。低内存设备上直接 OOM 闪退。
 *
 * 这里给出**一个**可调的批量预算，选择时与发送前各查一次（发送前再查，是为了拦住
 * 「选的时候没超、文件后来变大或被别的路径塞进来」）。
 *
 * 数值取 64MB：日常发文档/截图远够用，而 64MB 原始量在上述流水线里峰值约 200MB 上下，
 * 仍值得盯（真正的根治是分片上传 + 流式，不在本次范围）。要放开就改这一个常量。
 */
internal const val MAX_ATTACH_TOTAL_BYTES = 64L * 1024 * 1024

/**
 * 已有 [currentBytes] 字节时，再收 [incomingBytes] 字节是否会超出预算。
 * 纯函数、无副作用，便于单测钉住边界（正好等于预算时允许，超过才拒）。
 */
internal fun exceedsAttachBudget(
    currentBytes: Long,
    incomingBytes: Long,
    budget: Long = MAX_ATTACH_TOTAL_BYTES,
): Boolean = currentBytes + incomingBytes > budget

/** 给用户看的一句话（MB 取整，避免 63.999 这种噪音）。 */
internal fun attachBudgetText(budget: Long = MAX_ATTACH_TOTAL_BYTES): String =
    "附件合计超过 " + (budget / 1024 / 1024) + "MB，请分批发送"
