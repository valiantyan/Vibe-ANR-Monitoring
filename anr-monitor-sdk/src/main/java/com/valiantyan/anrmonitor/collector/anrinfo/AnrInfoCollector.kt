package com.valiantyan.anrmonitor.collector.anrinfo

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.valiantyan.anrmonitor.domain.model.AnrInfoSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrType

/**
 * 读取系统错误状态，补充 ActivityManager 侧已确认 ANR 信息。
 *
 * @param stateReader 可替换读取器，便于单元测试覆盖系统接口不可得场景。
 */
class AnrInfoCollector(
    private val stateReader: () -> AnrInfoSnapshot?,
) {
    /**
     * 采集系统确认 ANR 信息；系统未返回错误状态时输出未确认快照。
     *
     * @return 归一化后的 [AnrInfoSnapshot]。
     */
    fun collect(): AnrInfoSnapshot {
        return try {
            stateReader()?.let { snapshot: AnrInfoSnapshot ->
                normalize(snapshot = snapshot)
            } ?: AnrInfoSnapshot.unconfirmed()
        } catch (error: SecurityException) {
            AnrInfoSnapshot.unavailable(reason = "anr info read failed: ${error.message}")
        } catch (error: RuntimeException) {
            AnrInfoSnapshot.unavailable(reason = "anr info read failed: ${error.message}")
        }
    }

    // 只在系统已确认但类型未知时推断组件类型，避免覆盖调用方明确传入的类型。
    private fun normalize(snapshot: AnrInfoSnapshot): AnrInfoSnapshot {
        if (!snapshot.isConfirmedAnr || snapshot.anrType != AnrType.UNKNOWN) {
            return snapshot
        }
        return snapshot.copy(
            anrType = inferAnrType(
                shortMsg = snapshot.shortMsg,
                longMsg = snapshot.longMsg,
            ),
        )
    }

    // 从系统消息中提取组件类型；该类型只作为阈值证据，不直接作为根因。
    private fun inferAnrType(
        shortMsg: String?,
        longMsg: String?,
    ): AnrType {
        val message: String = listOfNotNull(shortMsg, longMsg)
            .joinToString(separator = " ")
            .lowercase()
        return when {
            message.contains(other = "input") -> AnrType.INPUT
            message.contains(other = "service") -> AnrType.SERVICE
            message.contains(other = "broadcast") && message.contains(other = "foreground") -> {
                AnrType.BROADCAST_FOREGROUND
            }
            message.contains(other = "broadcast") -> AnrType.BROADCAST_BACKGROUND
            message.contains(other = "provider") -> AnrType.PROVIDER
            message.contains(other = "activity") -> AnrType.ACTIVITY
            message.contains(other = "finalizer") -> AnrType.FINALIZER
            else -> AnrType.UNKNOWN
        }
    }

    /**
     * 系统读取器工厂，运行时通过 [Context] 获取当前进程的 [ActivityManager] 错误状态。
     */
    companion object {
        /**
         * 创建默认系统读取器。
         *
         * @param context 宿主应用上下文。
         * @return 默认 [AnrInfoCollector]。
         */
        fun create(context: Context): AnrInfoCollector {
            val appContext: Context = context.applicationContext
            return AnrInfoCollector(
                stateReader = { readSystemAnrInfo(context = appContext) },
            )
        }

        // 从 ActivityManager 中筛选当前进程的 NOT_RESPONDING 状态。
        private fun readSystemAnrInfo(context: Context): AnrInfoSnapshot? {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val states: List<ActivityManager.ProcessErrorStateInfo> = activityManager
                ?.processesInErrorState
                ?: return null
            val processId: Int = Process.myPid()
            val state: ActivityManager.ProcessErrorStateInfo = states.firstOrNull { item ->
                item.pid == processId && item.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
            } ?: return null
            return AnrInfoSnapshot(
                available = true,
                isConfirmedAnr = true,
                anrType = AnrType.UNKNOWN,
                shortMsg = state.shortMsg,
                longMsg = state.longMsg,
                condition = state.condition,
                failureReason = null,
            )
        }
    }
}
