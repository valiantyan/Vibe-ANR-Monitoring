package com.valiantyan.anrmonitor.collector.looper

/**
 * Looper Printer 输出的一次 dispatch 边界事件。
 *
 * @property isStart 是否为 dispatch 开始事件。
 * @property targetClass Handler target 类名，尚未做隐私脱敏。
 * @property callbackClass Runnable callback 类名，未知或为空时为 null。
 * @property what Handler 消息 what，无法解析时为 null。
 */
data class LooperDispatchEvent(
    val isStart: Boolean,
    val targetClass: String,
    val callbackClass: String?,
    val what: Int?,
)
