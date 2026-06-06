package com.valiantyan.anrmonitor.collector.sharedprefs

import android.os.Build
import com.valiantyan.anrmonitor.domain.model.QueuedWorkBypassState

/**
 * QueuedWork waitToFinish 绕过策略，默认只做决策不主动修改系统行为。
 *
 * @param policyProvider 灰度策略提供方。
 */
class QueuedWorkBypassController(
    private val policyProvider: () -> QueuedWorkBypassPolicy,
) {
    /**
     * 评估指定 SP 文件是否允许绕过 QueuedWork 等待。
     *
     * @param request 当前运行时和文件信息。
     * @return 带原因的绕过决策。
     */
    fun evaluate(request: QueuedWorkBypassRequest): QueuedWorkBypassDecision {
        val policy: QueuedWorkBypassPolicy = policyProvider()
        if (!policy.enabled) {
            return QueuedWorkBypassDecision(
                allowed = false,
                reason = "queued work bypass disabled",
            )
        }
        if (policy.rollbackEnabled) {
            return QueuedWorkBypassDecision(
                allowed = false,
                reason = "queued work bypass rollback enabled",
            )
        }
        return evaluateEnabledPolicy(
            policy = policy,
            request = request,
        )
    }

    /**
     * 兼容计划中的简化入口，只按当前设备环境评估文件名。
     *
     * @param fileName SP 文件名。
     * @return true 表示允许绕过。
     */
    fun canBypass(fileName: String): Boolean {
        return evaluate(
            request = QueuedWorkBypassRequest(
                fileName = fileName,
                manufacturer = Build.MANUFACTURER,
                sdkInt = Build.VERSION.SDK_INT,
            ),
        ).allowed
    }

    // 已开启策略后继续检查黑名单、白名单、ROM 和 SDK 版本边界。
    private fun evaluateEnabledPolicy(
        policy: QueuedWorkBypassPolicy,
        request: QueuedWorkBypassRequest,
    ): QueuedWorkBypassDecision {
        if (request.fileName in policy.blockedFiles) {
            return rejected(reason = "file blocked")
        }
        if (request.fileName !in policy.allowedFiles) {
            return rejected(reason = "file not allowed")
        }
        if (request.manufacturer in policy.blockedManufacturers) {
            return rejected(reason = "manufacturer blocked")
        }
        if (policy.allowedManufacturers.isNotEmpty() && request.manufacturer !in policy.allowedManufacturers) {
            return rejected(reason = "manufacturer not allowed")
        }
        if (request.sdkInt < policy.minSdk || request.sdkInt > policy.maxSdk) {
            return rejected(reason = "sdk version not allowed")
        }
        return QueuedWorkBypassDecision(
            allowed = true,
            reason = "queued work bypass allowed",
        )
    }

    // 构造拒绝决策，统一 reason 输出。
    private fun rejected(reason: String): QueuedWorkBypassDecision {
        return QueuedWorkBypassDecision(
            allowed = false,
            reason = reason,
        )
    }
}

/**
 * QueuedWork 绕过灰度策略。
 *
 * @property enabled 总开关，默认必须关闭。
 * @property allowedFiles 文件白名单。
 * @property blockedFiles 文件黑名单。
 * @property allowedManufacturers ROM 白名单，空集合表示不限制。
 * @property blockedManufacturers ROM 黑名单。
 * @property minSdk 允许的最低 SDK。
 * @property maxSdk 允许的最高 SDK。
 * @property rollbackEnabled 回滚开关，优先级高于白名单。
 */
data class QueuedWorkBypassPolicy(
    val enabled: Boolean,
    val allowedFiles: Set<String>,
    val blockedFiles: Set<String>,
    val allowedManufacturers: Set<String> = emptySet(),
    val blockedManufacturers: Set<String> = emptySet(),
    val minSdk: Int = DEFAULT_MIN_SDK,
    val maxSdk: Int = DEFAULT_MAX_SDK,
    val rollbackEnabled: Boolean = false,
) {
    /**
     * 转换为报告内的治理状态。
     *
     * @return 报告使用的稳定状态模型。
     */
    fun toState(): QueuedWorkBypassState {
        return QueuedWorkBypassState(
            enabled = enabled,
            allowedFiles = allowedFiles,
            blockedFiles = blockedFiles,
            rollbackEnabled = rollbackEnabled,
        )
    }

    /**
     * QueuedWork 绕过策略辅助构造器。
     */
    companion object {
        /**
         * Android 6.0 起 SDK 项目最低运行版本。
         */
        private const val DEFAULT_MIN_SDK: Int = 23

        /**
         * 当前项目 targetSdk 对应的默认上限。
         */
        private const val DEFAULT_MAX_SDK: Int = 35

        /**
         * 创建默认关闭策略。
         *
         * @return 默认关闭的 QueuedWork 绕过策略。
         */
        fun disabled(): QueuedWorkBypassPolicy {
            return QueuedWorkBypassPolicy(
                enabled = false,
                allowedFiles = emptySet(),
                blockedFiles = emptySet(),
            )
        }
    }
}

/**
 * QueuedWork 绕过决策请求。
 *
 * @property fileName SP 文件名。
 * @property manufacturer 设备厂商。
 * @property sdkInt 当前 Android SDK 版本。
 */
data class QueuedWorkBypassRequest(
    val fileName: String,
    val manufacturer: String,
    val sdkInt: Int,
)

/**
 * QueuedWork 绕过决策结果。
 *
 * @property allowed 是否允许绕过。
 * @property reason 决策原因。
 */
data class QueuedWorkBypassDecision(
    val allowed: Boolean,
    val reason: String,
)
