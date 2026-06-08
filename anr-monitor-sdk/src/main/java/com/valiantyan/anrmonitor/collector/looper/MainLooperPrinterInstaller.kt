package com.valiantyan.anrmonitor.collector.looper

import android.os.Looper
import android.util.Printer

/**
 * 主 Looper Printer 安装器，用于把 SDK 采集器接入 [Looper.setMessageLogging]。
 *
 * @param looper 目标 Looper，默认使用主线程 Looper。
 */
class MainLooperPrinterInstaller(
    private val looper: Looper? = null,
    private val currentPrinterReader: (() -> Printer?)? = null,
    private val messageLoggingSetter: ((Printer?) -> Unit)? = null,
) {
    /**
     * 安装新的 Printer，并在已有 Printer 存在时形成链式调用，降低与宿主调试工具的冲突。
     *
     * @param printer SDK 侧用于采集消息时间线的 Printer。
     * @return 安装句柄，用于 SDK 停止时恢复安装前的 [Printer]。
     */
    fun install(printer: Printer): InstallHandle {
        val reader: () -> Printer? = currentPrinterReader ?: { readCurrentPrinterFromLooper(looper = requireLooper()) }
        val setter: (Printer?) -> Unit = messageLoggingSetter ?: { value: Printer? -> requireLooper().setMessageLogging(value) }
        val previousPrinter: Printer? = reader()
        val chainedPrinter = Printer { line ->
            printer.println(line)
            previousPrinter?.println(line)
        }
        setter(chainedPrinter)
        return InstallHandle(
            previousPrinter = previousPrinter,
            messageLoggingSetter = setter,
        )
    }

    // 默认运行时才读取主 Looper，避免纯 JVM 单测构造对象时触发 Android stub。
    private fun requireLooper(): Looper {
        return looper ?: Looper.getMainLooper()
    }

    /**
     * Printer 安装句柄。
     *
     * @property previousPrinter 安装前已有的 [Printer]。
     * @property messageLoggingSetter 恢复 [Printer] 的安装入口。
     */
    class InstallHandle internal constructor(
        private val previousPrinter: Printer?,
        private val messageLoggingSetter: (Printer?) -> Unit,
    ) {
        // 避免重复卸载时反复覆盖宿主后续安装的 Printer。
        @Volatile
        private var isInstalled: Boolean = true

        /**
         * 恢复到安装前的 [Printer]，让 SDK 停止后不继续接收主线程消息日志。
         */
        @Synchronized
        fun uninstall(): Unit {
            if (!isInstalled) {
                return
            }
            messageLoggingSetter(previousPrinter)
            isInstalled = false
        }
    }

    private companion object {
        // 通过反射读取 [Looper] 已有 Printer；读取失败时视为不存在，保证安装流程不中断。
        fun readCurrentPrinterFromLooper(looper: Looper): Printer? {
            return runCatching {
                val field = Looper::class.java.getDeclaredField("mLogging")
                field.isAccessible = true
                field.get(looper) as? Printer
            }.getOrNull()
        }
    }
}
