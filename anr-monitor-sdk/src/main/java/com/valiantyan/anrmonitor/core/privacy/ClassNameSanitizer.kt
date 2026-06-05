package com.valiantyan.anrmonitor.core.privacy

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import java.security.MessageDigest

/**
 * 类名隐私脱敏器，用于在报告中保留可聚类信号并降低业务类名泄露风险。
 *
 * @property privacyMode 当前脱敏模式，决定输出原始规范类名还是稳定 hash。
 */
class ClassNameSanitizer(
    private val privacyMode: AnrPrivacyMode,
) {
    /**
     * 按隐私策略处理类名，空值统一输出空字符串方便后续 JSON 编码。
     *
     * @param className 原始类名，可为空。
     * @return 脱敏后的类名或稳定 hash。
     */
    fun sanitizeClassName(className: String?): String {
        if (className.isNullOrBlank()) {
            return ""
        }
        val normalizedName: String = trimAnonymousSuffix(className = className)
        if (privacyMode == AnrPrivacyMode.STRICT) {
            return "hash_${hash(value = normalizedName)}"
        }
        return normalizedName
    }

    /**
     * 裁剪 Kotlin/Java 匿名内部类数字尾缀，使同一调用点在报告中保持稳定。
     */
    private fun trimAnonymousSuffix(className: String): String {
        return className.replace(
            regex = Regex("\\$\\d+$"),
            replacement = "",
        )
    }

    /**
     * 生成短 SHA-256 hash，兼顾聚类稳定性和报告体积。
     */
    private fun hash(value: String): String {
        val digest: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(n = HASH_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        /**
         * 类名 hash 保留字节数，输出 16 个十六进制字符。
         */
        private const val HASH_BYTES: Int = 8
    }
}
