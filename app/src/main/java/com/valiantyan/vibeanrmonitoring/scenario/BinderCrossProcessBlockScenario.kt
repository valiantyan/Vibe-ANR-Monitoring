package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 通过同步 AIDL 调用复现 Binder / 跨进程阻塞。
 *
 * @param remoteBinderClient 远端 Binder 客户端，测试中可替换为记录器。
 * @param durationMs 远端进程阻塞时长。
 */
class BinderCrossProcessBlockScenario(
    private val remoteBinderClient: RemoteBinderClient,
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : AnrDemoScenario {
    /**
     * 使用真实 Android Context 创建远端 Binder 客户端。
     *
     * @param context 用于绑定远端 Service 和展示失败提示。
     */
    constructor(context: Context) : this(
        remoteBinderClient = RemoteBlockingServiceClient(
            context = context,
            failureNotifier = BinderScenarioFailureNotifier(context = context),
        ),
    )

    init {
        remoteBinderClient.ensureBound()
    }

    /** 场景唯一标识，后续文档和分析报告用它区分 Demo 类型。 */
    override val id: String = "binder_cross_process_block"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "Binder / 跨进程阻塞"

    /** 预期归因说明，Binder 同步调用阻塞应落到 Binder 疑似归因。 */
    override val expectedAttribution: String = "BINDER_BLOCK_SUSPECTED"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "attribution.primary = BINDER_BLOCK_SUSPECTED",
        "binderBlock.suspected = true",
        "binderBlock.mainThreadInBinder = true",
        "mainThread.stackFrames 包含 BinderProxy.transact",
        "mainThread.stackFrames 包含 BinderCrossProcessBlockScenario.run",
        "barrierEvidence.stuckTokens 不是主因",
    )

    /**
     * 在主线程同步调用远端 AIDL，让当前点击消息卡在 Binder transact。
     */
    override fun run(): Unit {
        remoteBinderClient.blockRemote(durationMs = durationMs)
    }

    /**
     * Activity 销毁时释放远端 Service 绑定。
     */
    fun release(): Unit {
        remoteBinderClient.release()
    }

    private companion object {
        /**
         * 默认阻塞 12 秒，足够覆盖 Demo 的 suspectAnrMs=3000，同时避免等待过久。
         */
        private const val DEFAULT_DURATION_MS: Long = 12_000L
    }
}
