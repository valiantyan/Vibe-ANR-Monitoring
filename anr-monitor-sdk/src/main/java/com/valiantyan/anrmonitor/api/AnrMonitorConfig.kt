package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrType

/**
 * 控制采集数据的隐私等级，后续类名和栈信息脱敏会按该模式执行。
 */
enum class AnrPrivacyMode {
    /**
     * 保留系统类名，业务类名按安全规则裁剪。
     */
    SAFE,

    /**
     * 尽量使用稳定 hash，适合更严格的线上隐私要求。
     */
    STRICT,
}

/**
 * ANR 监控 SDK 的不可变配置。
 *
 * @property appId 宿主应用标识，用于报告聚类和多应用区分。
 * @property environment 运行环境标识，例如 debug、gray 或 prod。
 * @property enabled 是否启用 SDK 运行时采集。
 * @property uploadEnabled 是否允许 SDK 触发上报扩展点。
 * @property sampleRate 报告采样率原始配置，允许调用方传入后由 [normalizedSampleRate] 归一化。
 * @property historyBufferSize 主线程历史消息环形缓冲区容量。
 * @property shortMessageAggregateMs 短消息累计耗时归并阈值。
 * @property slowMessageMs 单条慢消息判定阈值。
 * @property stackSampleIntervalMs 慢消息栈采样间隔。
 * @property maxStackSamplesPerMessage 单条消息最多保留的栈采样数量。
 * @property watchdogIntervalMs Watchdog 心跳检查间隔。
 * @property suspectAnrMs 疑似 ANR 触发阈值，默认对齐常见前台 5 秒体验窗口。
 * @property pendingSnapshotMaxDepth Pending 队列反射快照最大深度。
 * @property componentTimeoutMs 各组件系统 ANR 超时阈值，用于解释 confirmed ANR 类型。
 * @property captureChecktime 是否采集 Watchdog Checktime 调度延迟证据。
 * @property captureSystemEnvironment 是否采集系统负载、内存、存储和进程 I/O 证据。
 * @property captureThreadCpu 是否采集当前进程线程 CPU TopN 证据。
 * @property capturePendingQueue 是否采集 Pending 队列证据。
 * @property captureBarrierEvidence 是否采集 Barrier token 和 [nativePollOnce] 增强证据，默认关闭以控制 hook 风险。
 * @property barrierTokenStuckThresholdMs Barrier token 未移除超过该阈值才输出为卡住证据。
 * @property barrierEvidenceMaxRecords Barrier token 和 [nativePollOnce] 最近证据最大输出数量。
 * @property captureBinderEvidence 是否采集 Binder 和跨进程阻塞疑似证据。
 * @property binderThreadMaxCount 单次报告最多读取的 Binder 线程数量。
 * @property binderThreadStackMaxFrames 单个 Binder 线程最多保留的栈帧数量。
 * @property reportRetentionMaxFileCount 本地最多保留的报告数量。
 * @property reportRetentionMaxTotalBytes 本地报告最多占用的总字节数。
 * @property reportRetentionMaxAgeMs 本地报告最长保留时长。
 * @property reportUploadMinIntervalMs 两次上报入队之间的最小间隔。
 * @property reportRetryInitialDelayMs 上报失败后的首次重试延迟。
 * @property reportRetryMaxDelayMs 上报失败后的最大重试延迟。
 * @property privacyMode 栈和类名脱敏模式。
 */
data class AnrMonitorConfig(
    val appId: String,
    val environment: String,
    val enabled: Boolean = true,
    val uploadEnabled: Boolean = false,
    val sampleRate: Float = 1.0f,
    val historyBufferSize: Int = 120,
    val shortMessageAggregateMs: Long = 300L,
    val slowMessageMs: Long = 1_000L,
    val stackSampleIntervalMs: Long = 500L,
    val maxStackSamplesPerMessage: Int = 10,
    val watchdogIntervalMs: Long = 1_000L,
    val suspectAnrMs: Long = 5_000L,
    val pendingSnapshotMaxDepth: Int = 200,
    val componentTimeoutMs: Map<AnrType, Long> = DEFAULT_COMPONENT_TIMEOUT_MS,
    val captureChecktime: Boolean = true,
    val captureSystemEnvironment: Boolean = true,
    val captureThreadCpu: Boolean = true,
    val capturePendingQueue: Boolean = true,
    val captureBarrierEvidence: Boolean = false,
    val barrierTokenStuckThresholdMs: Long = 5_000L,
    val barrierEvidenceMaxRecords: Int = 20,
    val captureBinderEvidence: Boolean = true,
    val binderThreadMaxCount: Int = 8,
    val binderThreadStackMaxFrames: Int = 20,
    val reportRetentionMaxFileCount: Int = 30,
    val reportRetentionMaxTotalBytes: Long = 10L * 1024L * 1024L,
    val reportRetentionMaxAgeMs: Long = 7L * 24L * 60L * 60L * 1_000L,
    val reportUploadMinIntervalMs: Long = 60_000L,
    val reportRetryInitialDelayMs: Long = 1_000L,
    val reportRetryMaxDelayMs: Long = 60_000L,
    val privacyMode: AnrPrivacyMode = AnrPrivacyMode.SAFE,
) {
    /**
     * 归一化后的采样率，保证后续上报策略只处理 [0, 1] 的有效区间。
     */
    val normalizedSampleRate: Float = sampleRate.coerceIn(
        minimumValue = 0.0f,
        maximumValue = 1.0f,
    )

    private companion object {
        /**
         * Android 组件 ANR 默认阈值，UNKNOWN 不配置以避免误导。
         */
        private val DEFAULT_COMPONENT_TIMEOUT_MS: Map<AnrType, Long> = mapOf(
            AnrType.INPUT to 5_000L,
            AnrType.SERVICE to 10_000L,
            AnrType.BROADCAST_FOREGROUND to 10_000L,
            AnrType.BROADCAST_BACKGROUND to 60_000L,
            AnrType.PROVIDER to 10_000L,
            AnrType.ACTIVITY to 10_000L,
            AnrType.FINALIZER to 10_000L,
        )
    }
}
