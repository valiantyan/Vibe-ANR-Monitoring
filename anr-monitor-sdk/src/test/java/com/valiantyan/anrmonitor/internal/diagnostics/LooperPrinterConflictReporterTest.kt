package com.valiantyan.anrmonitor.internal.diagnostics

import android.util.Printer
import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证 Looper Printer 单槽位冲突能进入 SDK 自诊断指标。
 */
class LooperPrinterConflictReporterTest {
    /**
     * 当前 Looper Printer 仍由 SDK 持有时，不应记录冲突指标。
     */
    @Test
    fun recordDoesNotIncrementMetricWhenPrinterStillInstalled(): Unit {
        var installedPrinter: Printer? = null
        val selfMonitor = SdkSelfMonitor()
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )
        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val reporter = LooperPrinterConflictReporter(selfMonitor = selfMonitor)

        val status: MainLooperPrinterInstaller.InstallStatus = reporter.record(handle = handle)

        assertEquals(MainLooperPrinterInstaller.InstallStatus.INSTALLED, status)
        assertNull(selfMonitor.snapshotCounters()["looper_printer_replaced"])
        assertNull(selfMonitor.snapshotCounters()["looper_printer_status_unknown"])
    }

    /**
     * 当前 Looper Printer 被第三方替换时，应记录稳定的冲突计数指标。
     */
    @Test
    fun recordIncrementsMetricWhenPrinterWasReplaced(): Unit {
        var installedPrinter: Printer? = null
        val selfMonitor = SdkSelfMonitor()
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )
        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        installedPrinter = Printer {}
        val reporter = LooperPrinterConflictReporter(selfMonitor = selfMonitor)

        val status: MainLooperPrinterInstaller.InstallStatus = reporter.record(handle = handle)

        assertEquals(MainLooperPrinterInstaller.InstallStatus.REPLACED, status)
        assertEquals(1L, selfMonitor.snapshotCounters()["looper_printer_replaced"])
    }

    /**
     * 当前 Looper Printer 状态读取失败时，应记录独立指标，避免误判为三方覆盖。
     */
    @Test
    fun recordIncrementsMetricWhenPrinterStatusIsUnknown(): Unit {
        var installedPrinter: Printer? = null
        var readCount = 0
        val selfMonitor = SdkSelfMonitor()
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = {
                readCount += 1
                if (readCount == 1) {
                    installedPrinter
                } else {
                    throw IllegalStateException("reflection failed")
                }
            },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )
        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val reporter = LooperPrinterConflictReporter(selfMonitor = selfMonitor)

        val status: MainLooperPrinterInstaller.InstallStatus = reporter.record(handle = handle)

        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNKNOWN, status)
        assertEquals(1L, selfMonitor.snapshotCounters()["looper_printer_status_unknown"])
        assertNull(selfMonitor.snapshotCounters()["looper_printer_replaced"])
    }
}
