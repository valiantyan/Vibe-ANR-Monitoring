package com.valiantyan.anrmonitor.collector.sharedprefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 QueuedWork 绕过治理默认关闭，并受白名单、黑名单和回滚开关约束。
 */
class QueuedWorkBypassControllerTest {
    /**
     * 默认关闭时即使文件在白名单中也不能绕过系统等待语义。
     */
    @Test
    fun evaluateRejectsWhenPolicyDisabled(): Unit {
        val controller = QueuedWorkBypassController(
            policyProvider = {
                QueuedWorkBypassPolicy(
                    enabled = false,
                    allowedFiles = setOf("settings.xml"),
                    blockedFiles = emptySet(),
                )
            },
        )

        val decision = controller.evaluate(
            request = request(fileName = "settings.xml"),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("disabled"))
    }

    /**
     * 开启灰度后仍必须同时满足白名单、黑名单、ROM 和回滚条件。
     */
    @Test
    fun evaluateAllowsOnlyWhitelistedFileWhenPolicyMatchesRuntime(): Unit {
        val controller = QueuedWorkBypassController(
            policyProvider = {
                QueuedWorkBypassPolicy(
                    enabled = true,
                    allowedFiles = setOf("settings.xml"),
                    blockedFiles = setOf("auth.xml"),
                    allowedManufacturers = setOf("Google"),
                    blockedManufacturers = setOf("BadRom"),
                    minSdk = 23,
                    maxSdk = 35,
                    rollbackEnabled = false,
                )
            },
        )

        val allowed = controller.evaluate(
            request = request(fileName = "settings.xml"),
        )
        val blockedFile = controller.evaluate(
            request = request(fileName = "auth.xml"),
        )
        val blockedRom = controller.evaluate(
            request = request(
                fileName = "settings.xml",
                manufacturer = "BadRom",
            ),
        )

        assertTrue(allowed.allowed)
        assertFalse(blockedFile.allowed)
        assertFalse(blockedRom.allowed)
    }

    /**
     * 回滚开关优先级最高，避免线上一致性风险扩大。
     */
    @Test
    fun evaluateRejectsWhenRollbackEnabled(): Unit {
        val controller = QueuedWorkBypassController(
            policyProvider = {
                QueuedWorkBypassPolicy(
                    enabled = true,
                    allowedFiles = setOf("settings.xml"),
                    blockedFiles = emptySet(),
                    rollbackEnabled = true,
                )
            },
        )

        val decision = controller.evaluate(
            request = request(fileName = "settings.xml"),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("rollback"))
    }

    // 构造绕过决策请求，默认使用允许范围内的运行时环境。
    private fun request(
        fileName: String,
        manufacturer: String = "Google",
        sdkInt: Int = 34,
    ): QueuedWorkBypassRequest {
        return QueuedWorkBypassRequest(
            fileName = fileName,
            manufacturer = manufacturer,
            sdkInt = sdkInt,
        )
    }
}
