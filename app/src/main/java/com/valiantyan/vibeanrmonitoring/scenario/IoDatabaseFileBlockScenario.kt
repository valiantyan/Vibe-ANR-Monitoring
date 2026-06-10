package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 在主线程执行同步文件和数据库操作，用于复现 IO / 数据库 / 文件阻塞导致的当前消息慢。
 *
 * @param workload 可测试工作负载，真实 Demo 中执行文件写入和 SQLite 事务。
 */
class IoDatabaseFileBlockScenario(
    private val workload: MainThreadIoWorkload,
) : AnrDemoScenario {
    /**
     * 使用真实 Android Context 创建 IO/DB 工作负载。
     *
     * @param context 用于访问应用私有文件和数据库目录。
     */
    constructor(context: Context) : this(
        workload = FileAndDatabaseBlockingWorkload(context = context),
    )

    /** 场景唯一标识，后续文档和分析报告用它区分 Demo 类型。 */
    override val id: String = "io_database_file_block"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "IO / 数据库 / 文件阻塞"

    /** 预期归因说明，主线程同步 IO/DB 通常先表现为当前消息慢。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + IO/DB call stack evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 IoDatabaseFileBlockScenario.run",
        "mainThread.stackFrames 包含 FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload",
        "mainThread.stackFrames 包含 FileOutputStream.write 或 SQLiteDatabase",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发主线程同步 IO/DB 工作负载。此方法由按钮点击在主线程调用。
     */
    override fun run(): Unit {
        workload.runIoDatabaseFileWorkload()
    }
}
