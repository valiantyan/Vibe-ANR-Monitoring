package com.valiantyan.vibeanrmonitoring

import android.app.Application
import android.util.Log
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

/**
 * Demo 应用入口，在 debug 环境启动 ANR SDK 并把关键事件写入 logcat。
 */
class VibeAnrApplication : Application() {
    /**
     * 应用启动时安装 ANR SDK，确保 Activity 场景按钮触发前采集链路已经就绪。
     */
    override fun onCreate(): Unit {
        super.onCreate()
        AnrMonitor.install(
            context = this,
            config = AnrMonitorConfig(
                appId = "vibe-anr-demo",
                environment = "debug",
                enabled = true,
                uploadEnabled = false,
                sampleRate = 1.0f,
                suspectAnrMs = 3_000L,
                watchdogIntervalMs = 500L,
                captureBarrierEvidence = true,
                barrierTokenStuckThresholdMs = 2_000L,
            ),
            listener = object : AnrEventListener {
                /**
                 * 疑似 ANR 现场捕获后输出事件 ID，便于手动验收对齐报告文件。
                 */
                override fun onSuspectAnr(snapshot: AnrSnapshot): Unit {
                    Log.w(TAG, "suspect ANR captured: ${snapshot.eventId}")
                }

                /**
                 * 完整报告生成后输出事件 ID，便于确认本地 JSON 已完成编码。
                 */
                override fun onConfirmedAnr(report: AnrReport): Unit {
                    Log.w(TAG, "ANR report written: ${report.snapshot.eventId}")
                }

                /**
                 * SDK 内部异常只记录到 logcat，避免 demo 进程因为监控失败而崩溃。
                 */
                override fun onMonitorError(error: Throwable): Unit {
                    Log.e(TAG, "ANR monitor error: ${error.message}", error)
                }
            },
        )
    }

    private companion object {
        /**
         * Application 级 SDK 日志标签。
         */
        private const val TAG: String = "VibeAnrApplication"
    }
}
