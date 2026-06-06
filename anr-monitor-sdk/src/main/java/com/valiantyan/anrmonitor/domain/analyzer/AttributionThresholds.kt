package com.valiantyan.anrmonitor.domain.analyzer

/**
 * 归因规则阈值集合，用于把线上可调参数集中在一个不可变模型内。
 *
 * @property slowMessageMs 单条消息进入慢消息观察的耗时阈值。
 * @property suspectAnrMs 疑似 ANR 的主线程阻塞阈值。
 * @property highCpuRatio 当前消息判定为 CPU 忙的最低 CPU/Wall 比例。
 * @property messageStormCount 同一 target 或 callback 构成消息风暴的最小重复数量。
 */
data class AttributionThresholds(
    val slowMessageMs: Long = 1_000L,
    val suspectAnrMs: Long = 5_000L,
    val highCpuRatio: Double = 0.5,
    val messageStormCount: Int = 20,
)
