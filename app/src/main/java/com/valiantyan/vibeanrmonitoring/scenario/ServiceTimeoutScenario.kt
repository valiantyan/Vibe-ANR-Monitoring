package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 启动 Demo 专用阻塞 Service，用于复现 Service 生命周期执行超时。
 *
 * @param serviceStarter Service 启动器，测试中可替换为记录器。
 */
class ServiceTimeoutScenario(
    private val serviceStarter: ServiceStarter,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        serviceStarter = ContextServiceStarter(context = context),
    )

    override val id: String = "service_timeout"
    override val title: String = "Service 超时"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + SERVICE component evidence"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.stackFrames 包含 ServiceTimeoutService.onStartCommand",
        "mainThread.current.targetClass 包含 ActivityThread\$H",
        "systemAnr.anrType = SERVICE",
        "barrierEvidence.stuckTokens 不是主因",
    )

    /**
     * 启动 Demo 专用显式 Service。真正阻塞入口在 [ServiceTimeoutService.onStartCommand]。
     */
    override fun run(): Unit {
        serviceStarter.start(action = ACTION_SERVICE_TIMEOUT)
    }

    companion object {
        /**
         * Demo 专用 action，只用于应用内显式 Service。
         */
        const val ACTION_SERVICE_TIMEOUT: String =
            "com.valiantyan.vibeanrmonitoring.action.SERVICE_TIMEOUT"
    }
}
