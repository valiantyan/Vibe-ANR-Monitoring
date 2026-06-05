package com.valiantyan.anrmonitor.core.privacy

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [ClassNameSanitizer] 的隐私策略，确保报告中的类名既可聚类又不泄露业务路径。
 */
class ClassNameSanitizerTest {
    /**
     * 安全模式需要保留系统类名，同时裁剪匿名内部类尾缀，避免同一 Handler 被拆成多个聚类。
     */
    @Test
    fun sanitizeSafeModeKeepsSystemClassAndTrimsAnonymousSuffix(): Unit {
        val sanitizer = ClassNameSanitizer(
            privacyMode = AnrPrivacyMode.SAFE,
        )

        val value: String = sanitizer.sanitizeClassName(
            className = "android.app.ActivityThread\$H\$1",
        )

        assertEquals("android.app.ActivityThread\$H", value)
    }

    /**
     * 严格模式需要输出稳定 hash，方便线上聚类且避免直接暴露业务包名。
     */
    @Test
    fun sanitizeStrictModeReturnsStableHashPrefix(): Unit {
        val sanitizer = ClassNameSanitizer(
            privacyMode = AnrPrivacyMode.STRICT,
        )

        val value: String = sanitizer.sanitizeClassName(
            className = "com.example.secret.PaymentHandler",
        )

        assertTrue(value.startsWith("hash_"))
        assertEquals(value, sanitizer.sanitizeClassName(className = "com.example.secret.PaymentHandler"))
    }
}
