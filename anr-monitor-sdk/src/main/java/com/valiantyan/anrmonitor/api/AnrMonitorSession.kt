package com.valiantyan.anrmonitor.api

/**
 * 已安装的监控会话句柄，宿主可用它查询运行状态或主动停止 SDK。
 *
 * @property config 本次安装使用的不可变配置。
 * @param stopAction SDK 运行时停止动作，后续由内部 runtime 注入。
 */
class AnrMonitorSession internal constructor(
    val config: AnrMonitorConfig,
    private val stopAction: () -> Unit,
) {
    /**
     * 当前会话是否仍处于运行状态。
     */
    @Volatile
    var isRunning: Boolean = config.enabled
        private set

    /**
     * 停止当前会话；多次调用必须保持幂等，避免宿主生命周期重复触发时崩溃。
     */
    fun stop(): Unit {
        if (!isRunning) {
            return
        }
        stopAction()
        isRunning = false
    }
}
