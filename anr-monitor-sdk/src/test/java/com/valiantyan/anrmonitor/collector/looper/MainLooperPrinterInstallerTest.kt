package com.valiantyan.anrmonitor.collector.looper

import android.util.Printer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证 [MainLooperPrinterInstaller] 安装后可恢复旧 [Printer]，避免 SDK 卸载后残留采集链。
 */
class MainLooperPrinterInstallerTest {
    /**
     * 卸载安装句柄时，需要把 [Looper.setMessageLogging] 恢复到安装前的 [Printer]。
     */
    @Test
    fun uninstallRestoresPreviousPrinter(): Unit {
        val previousLines: MutableList<String> = mutableListOf()
        val sdkLines: MutableList<String> = mutableListOf()
        val previousPrinter = Printer { line: String -> previousLines += line }
        val sdkPrinter = Printer { line: String -> sdkLines += line }
        var installedPrinter: Printer? = previousPrinter
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = sdkPrinter)
        installedPrinter?.println("dispatch")
        handle.uninstall()

        assertEquals(listOf("dispatch"), sdkLines)
        assertEquals(listOf("dispatch"), previousLines)
        assertEquals(previousPrinter, installedPrinter)
    }

    /**
     * 没有旧 [Printer] 时，卸载句柄应恢复为空，避免旧 SDK 闭包继续留在主线程。
     */
    @Test
    fun uninstallClearsPrinterWhenNoPreviousPrinter(): Unit {
        var installedPrinter: Printer? = null
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        handle.uninstall()

        assertNull(installedPrinter)
    }
}
