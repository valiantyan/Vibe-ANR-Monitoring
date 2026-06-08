package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 查询 Demo 专用阻塞 Provider，用于复现 ContentProvider 查询长时间不返回。
 *
 * @param contentProviderCaller Provider 查询器，测试中可替换为记录器。
 */
class ContentProviderBlockScenario(
    private val contentProviderCaller: ContentProviderCaller,
) : AnrDemoScenario {
    /**
     * 使用真实 Android Context 创建 Provider 查询器。
     *
     * @param context 用于获取应用上下文和 ContentResolver。
     */
    constructor(context: Context) : this(
        contentProviderCaller = ContextContentProviderCaller(context = context),
    )

    /** 场景唯一标识，后续文档和分析报告用它区分 Demo 类型。 */
    override val id: String = "content_provider_block"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "ContentProvider 阻塞"

    /** 预期归因说明，Provider 阻塞通常先表现为当前消息慢。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + PROVIDER call stack evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.stackFrames 包含 BlockingContentProvider.query",
        "mainThread.stackFrames 包含 ContentProviderBlocker.block",
        "mainThread.stackFrames 包含 ContentResolver.query 或 ContentProvider.Transport.query",
        "mainThread.current.wallMs >= 3000",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发 Provider 查询。此方法由按钮点击在主线程调用。
     */
    override fun run(): Unit {
        contentProviderCaller.query(
            authority = AUTHORITY,
            path = BLOCKING_PATH,
        )
    }

    companion object {
        /**
         * Demo Provider authority，需要和 Manifest 中的 `${applicationId}.blocking-provider` 保持一致。
         */
        const val AUTHORITY: String = "com.valiantyan.vibeanrmonitoring.blocking-provider"

        /**
         * Demo 专用阻塞路径，避免普通 Provider 查询也被阻塞。
         */
        const val BLOCKING_PATH: String = "block"
    }
}
