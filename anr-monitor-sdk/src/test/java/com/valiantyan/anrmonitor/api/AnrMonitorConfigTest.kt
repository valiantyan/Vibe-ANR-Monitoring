package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [AnrMonitorConfig] 的默认预算和采样率边界，避免宿主未配置时产生过高采集成本。
 */
class AnrMonitorConfigTest {
    /**
     * 默认配置必须保守，保证 SDK 初次接入时不会默认开启高风险能力。
     */
    @Test
    fun createDefaultConfigUsesSafeInitialBudgets(): Unit {
        val config = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
        )
        assertTrue(config.enabled)
        assertEquals(120, config.historyBufferSize)
        assertEquals(5_000L, config.suspectAnrMs)
        assertEquals(200, config.pendingSnapshotMaxDepth)
        assertEquals(5_000L, config.componentTimeoutMs[AnrType.INPUT])
        assertEquals(10_000L, config.componentTimeoutMs[AnrType.SERVICE])
        assertEquals(10_000L, config.componentTimeoutMs[AnrType.BROADCAST_FOREGROUND])
        assertEquals(60_000L, config.componentTimeoutMs[AnrType.BROADCAST_BACKGROUND])
        assertEquals(10_000L, config.componentTimeoutMs[AnrType.PROVIDER])
        assertEquals(10_000L, config.componentTimeoutMs[AnrType.ACTIVITY])
        assertEquals(10_000L, config.componentTimeoutMs[AnrType.FINALIZER])
        assertTrue(config.captureChecktime)
        assertTrue(config.captureSystemEnvironment)
        assertTrue(config.captureThreadCpu)
        assertFalse(config.captureBarrierEvidence)
        assertEquals(5_000L, config.barrierTokenStuckThresholdMs)
        assertEquals(20, config.barrierEvidenceMaxRecords)
        assertTrue(config.captureBinderEvidence)
        assertEquals(8, config.binderThreadMaxCount)
        assertEquals(20, config.binderThreadStackMaxFrames)
        assertEquals(30, config.reportRetentionMaxFileCount)
        assertEquals(10L * 1024L * 1024L, config.reportRetentionMaxTotalBytes)
        assertEquals(7L * 24L * 60L * 60L * 1_000L, config.reportRetentionMaxAgeMs)
        assertEquals(60_000L, config.reportUploadMinIntervalMs)
        assertEquals(1_000L, config.reportRetryInitialDelayMs)
        assertEquals(60_000L, config.reportRetryMaxDelayMs)
    }

    /**
     * 采样率需要归一化到 [0, 1]，避免调用方误配导致全量关闭或超量上报。
     */
    @Test
    fun createConfigClampsSampleRateIntoValidRange(): Unit {
        val highConfig = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
            sampleRate = 9.0f,
        )
        val lowConfig = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
            sampleRate = -1.0f,
        )
        assertEquals(1.0f, highConfig.normalizedSampleRate, 0.0f)
        assertEquals(0.0f, lowConfig.normalizedSampleRate, 0.0f)
    }
}
