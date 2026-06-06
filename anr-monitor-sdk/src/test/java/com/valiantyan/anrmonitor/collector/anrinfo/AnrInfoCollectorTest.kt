package com.valiantyan.anrmonitor.collector.anrinfo

import com.valiantyan.anrmonitor.domain.model.AnrInfoSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证系统确认 ANR 信息采集，确保系统状态不可得时仍能输出清晰降级状态。
 */
class AnrInfoCollectorTest {
    /**
     * 系统没有返回错误状态时，只能说明当前未确认 ANR，不能把它当作采集失败。
     */
    @Test
    fun collectReturnsUnconfirmedWhenSystemStateIsMissing(): Unit {
        val collector = AnrInfoCollector(
            stateReader = { null },
        )

        val snapshot: AnrInfoSnapshot = collector.collect()

        assertTrue(snapshot.available)
        assertFalse(snapshot.isConfirmedAnr)
        assertEquals(AnrType.UNKNOWN, snapshot.anrType)
        assertNull(snapshot.failureReason)
    }

    /**
     * [longMsg] 中的组件类型只用于系统确认类型和阈值选择，不直接作为根因。
     */
    @Test
    fun collectInfersInputAnrTypeFromSystemMessage(): Unit {
        val collector = AnrInfoCollector(
            stateReader = {
                AnrInfoSnapshot(
                    available = true,
                    isConfirmedAnr = true,
                    anrType = AnrType.UNKNOWN,
                    shortMsg = "Input dispatching timed out",
                    longMsg = "Input dispatching timed out waiting for com.example/.MainActivity",
                    condition = 2,
                    failureReason = null,
                )
            },
        )

        val snapshot: AnrInfoSnapshot = collector.collect()

        assertTrue(snapshot.isConfirmedAnr)
        assertEquals(AnrType.INPUT, snapshot.anrType)
        assertEquals(2, snapshot.condition)
    }

    /**
     * 系统接口异常时需要保留失败原因，方便报告解释证据缺口。
     */
    @Test
    fun collectReturnsUnavailableWhenReaderThrows(): Unit {
        val collector = AnrInfoCollector(
            stateReader = {
                throw SecurityException("permission denied")
            },
        )

        val snapshot: AnrInfoSnapshot = collector.collect()

        assertFalse(snapshot.available)
        assertFalse(snapshot.isConfirmedAnr)
        assertEquals(AnrType.UNKNOWN, snapshot.anrType)
        assertTrue(snapshot.failureReason!!.contains("permission denied"))
    }
}
