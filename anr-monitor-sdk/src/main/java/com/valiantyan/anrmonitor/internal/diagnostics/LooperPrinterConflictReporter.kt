package com.valiantyan.anrmonitor.internal.diagnostics

import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller

/**
 * 将 Looper Printer 单槽位竞争状态记录到 SDK 自监控指标。
 *
 * @param selfMonitor SDK 自监控器。
 */
internal class LooperPrinterConflictReporter(
    private val selfMonitor: SdkSelfMonitor,
) {
    /**
     * 读取安装句柄状态，并在 SDK Printer 已被替换或状态不可读时记录稳定指标。
     *
     * @param handle 当前 Looper Printer 安装句柄，未安装时为空。
     * @return 当前观察到的安装状态，未安装时返回 [MainLooperPrinterInstaller.InstallStatus.UNINSTALLED]。
     */
    fun record(handle: MainLooperPrinterInstaller.InstallHandle?): MainLooperPrinterInstaller.InstallStatus {
        val status: MainLooperPrinterInstaller.InstallStatus = handle?.status()
            ?: MainLooperPrinterInstaller.InstallStatus.UNINSTALLED
        if (status == MainLooperPrinterInstaller.InstallStatus.REPLACED) {
            selfMonitor.increment(name = METRIC_LOOPER_PRINTER_REPLACED)
        }
        if (status == MainLooperPrinterInstaller.InstallStatus.UNKNOWN) {
            selfMonitor.increment(name = METRIC_LOOPER_PRINTER_STATUS_UNKNOWN)
        }
        return status
    }

    private companion object {
        /**
         * Looper Printer 已被后装第三方替换的计数指标。
         */
        private const val METRIC_LOOPER_PRINTER_REPLACED: String = "looper_printer_replaced"

        /**
         * Looper Printer 当前状态读取失败的计数指标。
         */
        private const val METRIC_LOOPER_PRINTER_STATUS_UNKNOWN: String = "looper_printer_status_unknown"
    }
}
