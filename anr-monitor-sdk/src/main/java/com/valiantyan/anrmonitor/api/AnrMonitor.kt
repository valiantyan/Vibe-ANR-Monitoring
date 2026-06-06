package com.valiantyan.anrmonitor.api

import android.content.Context
import android.content.SharedPreferences
import com.valiantyan.anrmonitor.collector.sharedprefs.MonitoredSharedPreferences
import com.valiantyan.anrmonitor.internal.AnrMonitorRuntime

/**
 * ANR 监控 SDK 的公开安装入口。
 */
object AnrMonitor {
    // 当前进程内唯一活跃会话，避免重复安装多个 Watchdog 或 Looper Printer。
    @Volatile
    private var activeSession: AnrMonitorSession? = null

    // 当前安装的内部运行时，卸载时用于释放 Watchdog 等后台资源。
    @Volatile
    private var runtime: RuntimeHandle? = null

    /**
     * 安装 SDK 并返回会话句柄；重复安装会复用当前进程内的活跃会话。
     *
     * @param context 宿主上下文，实际运行时会使用 application context。
     * @param config 本次安装的不可变配置。
     * @param uploader 宿主提供的报告上报扩展点。
     * @param listener 宿主提供的事件监听器。
     * @return 当前进程内的活跃 [AnrMonitorSession]。
     */
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
        val createdRuntime = AnrMonitorRuntime(
            context = context,
            config = config,
            uploader = uploader,
            listener = listener,
        )
        return installRuntimeLocked(
            config = config,
            runtimeHandle = createdRuntime,
        )
    }

    /**
     * 测试专用安装入口，用可控 runtime 验证单例生命周期，不触发 Android Looper 依赖。
     *
     * @param config 本次安装的不可变配置。
     * @param runtimeHandle 测试注入的运行时句柄。
     * @return 当前进程内的活跃 [AnrMonitorSession]。
     */
    @Synchronized
    internal fun installRuntimeForTesting(
        config: AnrMonitorConfig,
        runtimeHandle: RuntimeHandle,
    ): AnrMonitorSession {
        val existingSession: AnrMonitorSession? = activeSession
        if (existingSession != null) {
            return existingSession
        }
        return installRuntimeLocked(
            config = config,
            runtimeHandle = runtimeHandle,
        )
    }

    /**
     * 在持有 [AnrMonitor] 锁时创建会话并启动运行时，避免公开安装和测试安装逻辑分叉。
     */
    private fun installRuntimeLocked(
        config: AnrMonitorConfig,
        runtimeHandle: RuntimeHandle,
    ): AnrMonitorSession {
        val session = AnrMonitorSession(
            config = config,
            stopAction = {
                runtimeHandle.stop()
            },
        )
        runtime = runtimeHandle
        activeSession = session
        runtimeHandle.start()
        return session
    }

    /**
     * 卸载当前会话并释放内部运行时，允许宿主在调试场景中重新安装。
     */
    @Synchronized
    fun uninstall(): Unit {
        activeSession?.stop()
        activeSession = null
        runtime = null
    }

    /**
     * 包装 SharedPreferences 打开动作，用于记录首次加载耗时和后续写入证据。
     *
     * @param fileName SP 文件名，建议与磁盘 XML 文件名一致。
     * @param opener 原始 SP 打开动作，例如 [Context.getSharedPreferences]。
     * @return 被监控的 [SharedPreferences]。
     */
    fun openSharedPreferences(
        fileName: String,
        opener: () -> SharedPreferences,
    ): SharedPreferences {
        return MonitoredSharedPreferences.open(
            fileName = fileName,
            opener = opener,
        )
    }

    /**
     * 包装已有 SharedPreferences 实例，用于记录 apply 和 commit 写入证据。
     *
     * @param fileName SP 文件名，建议与磁盘 XML 文件名一致。
     * @param sharedPreferences 原始 [SharedPreferences]。
     * @return 被监控的 [SharedPreferences]。
     */
    fun monitorSharedPreferences(
        fileName: String,
        sharedPreferences: SharedPreferences,
    ): SharedPreferences {
        return MonitoredSharedPreferences.wrap(
            fileName = fileName,
            delegate = sharedPreferences,
        )
    }

    /**
     * 内部运行时句柄，只向公开入口暴露启动和停止语义。
     */
    internal interface RuntimeHandle {
        /**
         * 启动 SDK 采集链路。
         */
        fun start(): Unit

        /**
         * 停止 SDK 后台采集链路。
         */
        fun stop(): Unit
    }
}
