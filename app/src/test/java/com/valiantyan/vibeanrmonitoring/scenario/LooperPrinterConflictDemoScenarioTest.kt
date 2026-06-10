package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Looper Printer 竞争 Demo 入口能稳定触发对应探针，避免手动验收入口接错。
 */
class LooperPrinterConflictDemoScenarioTest {
    /**
     * worker Looper 场景只应安装后台 Looper Printer，不应替换主 Looper Printer。
     */
    @Test
    fun workerScenarioStartsWorkerLooperPrinter(): Unit {
        val probe: RecordingLooperPrinterDemoProbe = RecordingLooperPrinterDemoProbe()
        val scenario: WorkerLooperPrinterScenario = WorkerLooperPrinterScenario(probe = probe)

        scenario.run()

        assertEquals("looper_worker_printer", scenario.id)
        assertEquals("多 Looper Printer 验证", scenario.title)
        assertEquals(1, probe.workerPrinterStartCount)
        assertEquals(0, probe.mainPrinterReplacementCount)
        assertTrue(scenario.expectedJsonSignals.contains("sdkDiagnostics.selfMetrics 不包含 looper_printer_replaced"))
    }

    /**
     * 主 Looper 替换场景只应模拟三方后装 Printer，用于验证 SDK 冲突诊断。
     */
    @Test
    fun replacementScenarioReplacesMainLooperPrinter(): Unit {
        val probe: RecordingLooperPrinterDemoProbe = RecordingLooperPrinterDemoProbe()
        val scenario: MainLooperPrinterReplacementScenario = MainLooperPrinterReplacementScenario(probe = probe)

        scenario.run()

        assertEquals("looper_main_printer_replaced", scenario.id)
        assertEquals("主 Looper Printer 被替换", scenario.title)
        assertEquals(0, probe.workerPrinterStartCount)
        assertEquals(1, probe.mainPrinterReplacementCount)
        assertTrue(scenario.expectedJsonSignals.contains("sdkDiagnostics.selfMetrics 包含 looper_printer_replaced"))
    }

    private class RecordingLooperPrinterDemoProbe : LooperPrinterDemoProbe {
        var workerPrinterStartCount: Int = 0
        var mainPrinterReplacementCount: Int = 0

        override fun startWorkerLooperPrinter(): Unit {
            workerPrinterStartCount += 1
        }

        override fun replaceMainLooperPrinter(): Unit {
            mainPrinterReplacementCount += 1
        }

        override fun shutdown(): Unit = Unit
    }
}
