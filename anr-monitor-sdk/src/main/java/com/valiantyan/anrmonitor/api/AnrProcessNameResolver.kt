package com.valiantyan.anrmonitor.api

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

/**
 * SDK 内部进程名解析器，用于把接入样板收进 SDK 边界并统一多版本兼容策略。
 */
internal object AnrProcessNameResolver {
    /**
     * 判断当前进程是否为宿主主进程，默认以 [Context.getPackageName] 作为主进程名。
     *
     * @param context 宿主上下文。
     * @return 当前进程名等于宿主包名时返回 `true`。
     */
    fun isMainProcess(context: Context): Boolean {
        val packageName: String = context.packageName
        return isMainProcess(
            packageName = packageName,
            processName = currentProcessName(context = context),
        )
    }

    /**
     * 获取当前进程名；Android P 及以上优先使用系统 API，旧版本回退到 [ActivityManager]。
     *
     * @param context 宿主上下文。
     * @return 当前进程名；无法读取时回退为宿主包名，保持主进程默认安装行为可用。
     */
    fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName: String? = Application.getProcessName()
            if (!processName.isNullOrBlank()) {
                return processName
            }
        }
        return legacyProcessName(context = context) ?: context.packageName
    }

    /**
     * 纯判断函数供测试入口复用，避免单测依赖 Android framework 运行时。
     */
    fun isMainProcess(
        packageName: String,
        processName: String,
    ): Boolean = processName == packageName

    // 旧系统无法直接读取进程名，只能按当前 pid 从运行进程列表里匹配。
    private fun legacyProcessName(context: Context): String? {
        val pid: Int = android.os.Process.myPid()
        val activityManager: ActivityManager? = context.getSystemService(
            Context.ACTIVITY_SERVICE,
        ) as? ActivityManager
        return activityManager
            ?.runningAppProcesses
            ?.firstOrNull { processInfo: ActivityManager.RunningAppProcessInfo ->
                processInfo.pid == pid
            }
            ?.processName
    }
}
