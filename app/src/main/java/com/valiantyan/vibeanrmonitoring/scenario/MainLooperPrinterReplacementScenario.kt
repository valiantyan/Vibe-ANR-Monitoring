package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 模拟 SDK 安装后主 Looper Printer 被三方 SDK 后装替换。
 *
 * @param probe Looper Printer Demo 探针。
 */
class MainLooperPrinterReplacementScenario(
    private val probe: LooperPrinterDemoProbe,
) : AnrDemoScenario {
    constructor() : this(probe = AndroidLooperPrinterDemoProbe())

    /** 场景唯一标识，供 adb intent 自动化触发。 */
    override val id: String = "looper_main_printer_replaced"

    /** Demo 页面展示标题。 */
    override val title: String = "主 Looper Printer 被替换"

    /** 预期归因说明，当前场景本身不制造 ANR，只制造 Printer 竞争条件。 */
    override val expectedAttribution: String = "SDK_DIAGNOSTIC_LOOPER_PRINTER_REPLACED"

    /** 预期 JSON 证据，实际报告由后续 ANR 场景生成。 */
    override val expectedJsonSignals: List<String> = listOf(
        "logcat 包含 DemoThirdPartyPrinter",
        "sdkDiagnostics.selfMetrics 包含 looper_printer_replaced",
        "SDK 不默认抢回主 Looper Printer",
    )

    /**
     * 替换主 Looper Printer，用于后续 ANR 报告记录冲突诊断。
     */
    override fun run(): Unit {
        probe.replaceMainLooperPrinter()
    }
}
