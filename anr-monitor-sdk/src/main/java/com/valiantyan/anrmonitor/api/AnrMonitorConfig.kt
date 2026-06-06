package com.valiantyan.anrmonitor.api

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
 * @property captureChecktime 是否采集 Watchdog Checktime 调度延迟证据。
 * @property captureSystemEnvironment 是否采集系统负载、内存、存储和进程 I/O 证据。
 * @property captureThreadCpu 是否采集当前进程线程 CPU TopN 证据。
 * @property capturePendingQueue 是否采集 Pending 队列证据。
 * @property captureSpHealth 是否采集 SharedPreferences 健康度证据。
 * @property enableQueuedWorkBypass 是否启用 [android.app.QueuedWork] 绕过能力，默认关闭避免一致性风险。
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
    val captureChecktime: Boolean = true,
    val captureSystemEnvironment: Boolean = true,
    val captureThreadCpu: Boolean = true,
    val capturePendingQueue: Boolean = true,
    val captureSpHealth: Boolean = true,
    val enableQueuedWorkBypass: Boolean = false,
    val privacyMode: AnrPrivacyMode = AnrPrivacyMode.SAFE,
) {
    /**
     * 归一化后的采样率，保证后续上报策略只处理 [0, 1] 的有效区间。
     */
    val normalizedSampleRate: Float = sampleRate.coerceIn(
        minimumValue = 0.0f,
        maximumValue = 1.0f,
    )
}
