package com.valiantyan.anrmonitor.collector.looper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [LooperMessageParser] 能从 Android Looper Printer 文本中提取调度边界和 Handler 信息。
 */
class LooperMessageParserTest {
    /**
     * 起始日志必须提取 target 和 what，后续归因依赖它识别 ActivityThread.H 等关键消息。
     */
    @Test
    fun parseDispatchStartExtractsTargetAndWhat(): Unit {
        val line: String = ">>>>> Dispatching to Handler (android.app.ActivityThread\$H) {12345} null: 115"

        val event: LooperDispatchEvent = LooperMessageParser.parse(line = line)

        assertTrue(event.isStart)
        assertEquals("android.app.ActivityThread\$H", event.targetClass)
        assertEquals(115, event.what)
    }

    /**
     * 结束日志必须标识 dispatch 完成，以便采集器关闭当前消息并写入历史窗口。
     */
    @Test
    fun parseDispatchEndMarksEnd(): Unit {
        val line: String = "<<<<< Finished to Handler (android.app.ActivityThread\$H) {12345} null"

        val event: LooperDispatchEvent = LooperMessageParser.parse(line = line)

        assertFalse(event.isStart)
        assertEquals("android.app.ActivityThread\$H", event.targetClass)
    }
}
