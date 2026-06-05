package com.valiantyan.anrmonitor.api

import android.content.Context

/**
 * ANR 监控 SDK 的公开安装入口。
 */
object AnrMonitor {
    // 当前进程内唯一活跃会话，避免重复安装多个 Watchdog 或 Looper Printer。
    @Volatile
    private var activeSession: AnrMonitorSession? = null

    /**
     * 安装 SDK 并返回会话句柄；当前任务只建立 API 骨架，后续任务会接入真实运行时。
     *
     * @param context 宿主上下文，实际运行时会使用 application context。
     * @param config 本次安装的不可变配置。
     * @param uploader 宿主提供的报告上报扩展点。
     * @param listener 宿主提供的事件监听器。
     * @return 当前进程内的活跃 [AnrMonitorSession]。
     */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun install(
        context: Context,
        config: AnrMonitorConfig,
        uploader: AnrReportUploader = AnrReportUploader { UploadResult.Skip },
        listener: AnrEventListener = object : AnrEventListener {},
    ): AnrMonitorSession {
        val existingSession: AnrMonitorSession? = activeSession
        if (existingSession != null) {
            return existingSession
        }
        context.applicationContext
        val session = AnrMonitorSession(
            config = config,
            stopAction = {},
        )
        activeSession = session
        return session
    }

    /**
     * 卸载当前会话；后续任务接入真实运行时时会释放采集器和后台线程。
     */
    @Synchronized
    fun uninstall(): Unit {
        activeSession?.stop()
        activeSession = null
    }
}
