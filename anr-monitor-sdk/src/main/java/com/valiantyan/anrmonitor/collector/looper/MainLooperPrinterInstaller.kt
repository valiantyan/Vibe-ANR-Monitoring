package com.valiantyan.anrmonitor.collector.looper

import android.os.Looper
import android.util.Printer

/**
 * 主 Looper Printer 安装器，用于把 SDK 采集器接入 [Looper.setMessageLogging]。
 *
 * @param looper 目标 Looper，默认使用主线程 Looper。
 */
class MainLooperPrinterInstaller(
    private val looper: Looper = Looper.getMainLooper(),
) {
    /**
     * 安装新的 Printer，并在已有 Printer 存在时形成链式调用，降低与宿主调试工具的冲突。
     *
     * @param printer SDK 侧用于采集消息时间线的 Printer。
     * @return 安装结果，包含是否覆盖过已有 Printer。
     */
    fun install(printer: Printer): InstallResult {
        val previousPrinter: Printer? = readCurrentPrinter()
        val chainedPrinter = Printer { line ->
            printer.println(line)
            previousPrinter?.println(line)
        }
        looper.setMessageLogging(chainedPrinter)
        return InstallResult(
            installed = true,
            hadPreviousPrinter = previousPrinter != null,
            failureReason = null,
        )
    }

    /**
     * 通过反射读取 Looper 已有 Printer；读取失败时视为不存在，保证安装流程不中断。
     */
    private fun readCurrentPrinter(): Printer? {
        return runCatching {
            val field = Looper::class.java.getDeclaredField("mLogging")
            field.isAccessible = true
            field.get(looper) as? Printer
        }.getOrNull()
    }

    /**
     * Printer 安装结果。
     *
     * @property installed 是否已成功调用 [Looper.setMessageLogging]。
     * @property hadPreviousPrinter 安装前是否已经存在 Printer。
     * @property failureReason 失败原因；当前实现失败时返回 null 之外的文本。
     */
    data class InstallResult(
        val installed: Boolean,
        val hadPreviousPrinter: Boolean,
        val failureReason: String?,
    )
}
