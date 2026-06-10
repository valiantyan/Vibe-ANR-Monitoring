package com.valiantyan.anrmonitor.collector.looper

import android.util.Printer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /**
     * SDK 安装后如果第三方再次设置 Printer，安装句柄应能识别当前槽位已不属于 SDK。
     */
    @Test
    fun statusReportsReplacedWhenAnotherPrinterOverridesSdkPrinter(): Unit {
        val thirdPartyPrinter = Printer {}
        var installedPrinter: Printer? = null
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        installedPrinter = thirdPartyPrinter

        assertEquals(MainLooperPrinterInstaller.InstallStatus.REPLACED, handle.status())
    }

    /**
     * SDK 安装后如果第三方再次设置 Printer，卸载时不能把第三方 Printer 覆盖回旧值。
     */
    @Test
    fun uninstallDoesNotOverwritePrinterInstalledAfterSdk(): Unit {
        val previousPrinter = Printer {}
        val thirdPartyPrinter = Printer {}
        var installedPrinter: Printer? = previousPrinter
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        installedPrinter = thirdPartyPrinter
        val result: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()

        assertEquals(MainLooperPrinterInstaller.InstallStatus.REPLACED, result)
        assertSame(thirdPartyPrinter, installedPrinter)
        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNINSTALLED, handle.status())
    }

    /**
     * 读取当前 Printer 失败时，应输出 UNKNOWN，避免把反射失败误判为三方覆盖。
     */
    @Test
    fun statusReportsUnknownWhenCurrentPrinterCannotBeRead(): Unit {
        val previousPrinter = Printer {}
        var installedPrinter: Printer? = previousPrinter
        var readCount = 0
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
        val sdkInstalledPrinter: Printer? = installedPrinter
        val result: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()

        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNKNOWN, result)
        assertSame(sdkInstalledPrinter, installedPrinter)
        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNINSTALLED, handle.status())
    }

    /**
     * 重复卸载不能再次写入 Looper Printer 槽位，避免破坏后续宿主状态。
     */
    @Test
    fun uninstallIsIdempotentAfterSafeRestore(): Unit {
        val previousPrinter = Printer {}
        var installedPrinter: Printer? = previousPrinter
        var setterCount = 0
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                setterCount += 1
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val firstResult: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()
        val secondResult: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()

        assertEquals(MainLooperPrinterInstaller.InstallStatus.INSTALLED, firstResult)
        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNINSTALLED, secondResult)
        assertSame(previousPrinter, installedPrinter)
        assertEquals(2, setterCount)
    }
}
