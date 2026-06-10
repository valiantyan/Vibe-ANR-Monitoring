package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 验证多个 Looper 同时存在时，worker Looper 的 Printer 不会影响 SDK 主 Looper 采集。
 *
 * @param probe Looper Printer Demo 探针。
 */
class WorkerLooperPrinterScenario(
    private val probe: LooperPrinterDemoProbe,
) : AnrDemoScenario {
    constructor() : this(probe = AndroidLooperPrinterDemoProbe())

    /** 场景唯一标识，供 adb intent 自动化触发。 */
    override val id: String = "looper_worker_printer"

    /** Demo 页面展示标题。 */
    override val title: String = "多 Looper Printer 验证"

    /** 预期归因说明，当前场景本身不制造 ANR，只准备验证环境。 */
    override val expectedAttribution: String = "NO_ANR_SETUP"

    /** 预期 JSON 证据，实际报告由后续 ANR 场景生成。 */
    override val expectedJsonSignals: List<String> = listOf(
        "logcat 包含 DemoWorkerLooper",
        "后续 ANR 报告 mainThread.current 仍可用",
        "sdkDiagnostics.selfMetrics 不包含 looper_printer_replaced",
    )

    /**
     * 启动 worker Looper Printer，保持主 Looper Printer 不变。
     */
    override fun run(): Unit {
        probe.startWorkerLooperPrinter()
    }
}
