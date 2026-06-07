package com.valiantyan.anrmonitor.acceptance

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证全量验收矩阵与 demo 入口，防止 01 到 05 的覆盖口径在交付末端退化。
 */
class FullAcceptanceMatrixTest {
    /**
     * Demo 页面必须暴露全量手动验收入口，方便复核当前忙等和 Binder 疑似两类新增场景。
     */
    @Test
    fun demoActivityContainsFullManualScenarioEntrypoints(): Unit {
        val rootDir: File = findProjectRoot()
        val layoutText: String = rootDir.resolve("app/src/main/res/layout/activity_main.xml").readText()
        val activityText: String = rootDir.resolve(
            "app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt",
        ).readText()
        assertContains(layoutText, "android:id=\"@+id/currentBusyButton\"")
        assertContains(layoutText, "android:text=\"Current Busy Loop\"")
        assertContains(layoutText, "android:id=\"@+id/binderLikeButton\"")
        assertContains(layoutText, "android:text=\"Binder Like Wait\"")
        assertContains(activityText, "R.id.currentBusyButton")
        assertContains(activityText, "runBusyLoop()")
        assertContains(activityText, "R.id.binderLikeButton")
        assertContains(activityText, "waitBinderLikeLock()")
        assertContains(activityText, "Math.sqrt(42.0)")
        assertContains(activityText, "Thread.sleep(6_000L)")
    }

    /**
     * 全量验收记录必须同时覆盖功能矩阵、性能预算和构建命令，支撑后续技术评审追溯。
     */
    @Test
    fun fullAcceptanceRecordCoversEvidenceMatrixAndPerformanceBudgets(): Unit {
        val rootDir: File = findProjectRoot()
        val recordFile: File = rootDir.resolve("docs-anr/101-ANR监控SDK全量验收记录.md")
        assertTrue("缺少全量验收记录: ${recordFile.path}", recordFile.exists())
        val recordText: String = recordFile.readText()
        val requiredTerms: List<String> = listOf(
            "当前消息慢",
            "历史消息慢",
            "消息风暴",
            "Pending 队列快照",
            "Barrier 疑似",
            "Barrier token",
            "nativePollOnce",
            "慢消息堆栈采样",
            "线程 CPU",
            "Checktime",
            "系统环境",
            "SP_LOAD_WAIT",
            "SP_APPLY_WAIT",
            "SharedPreferences 文件健康度",
            "QueuedWork 绕过治理",
            "Binder 阻塞疑似",
            "报告治理",
            "SDK 自监控",
            "常驻 CPU",
            "主线程单消息额外耗时",
            "快照耗时",
            "报告大小",
            "采样频率",
            "./gradlew :anr-monitor-sdk:testDebugUnitTest",
            "./gradlew :app:assembleDebug",
        )
        requiredTerms.forEach { requiredTerm: String ->
            assertContains(recordText, requiredTerm)
        }
    }

    // 从 Gradle 测试工作目录向上查找项目根目录，避免依赖固定执行目录。
    private fun findProjectRoot(): File {
        val userDir: String = requireNotNull(System.getProperty("user.dir"))
        var currentDir: File? = File(userDir).canonicalFile
        while (currentDir != null) {
            val settingsFile: File = currentDir.resolve("settings.gradle.kts")
            if (settingsFile.exists()) {
                return currentDir
            }
            currentDir = currentDir.parentFile
        }
        error("无法从 ${System.getProperty("user.dir")} 查找到项目根目录")
    }

    // 统一断言消息，方便 RED/GREEN 阶段快速定位缺失项。
    private fun assertContains(content: String, expected: String): Unit {
        assertTrue("缺少内容: $expected", content.contains(expected))
    }
}
