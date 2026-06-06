package com.valiantyan.anrmonitor.reporter.encoder

/**
 * JSON 字符串转义工具，避免报告字段破坏本地 JSON 结构。
 */
object JsonEscaper {
    /**
     * 将普通字符串转成 JSON 字符串内容可安全承载的形式。
     *
     * @param value 原始字符串。
     * @return 已转义但不包含外层引号的字符串。
     */
    fun escape(value: String): String {
        return buildString {
            value.forEach { char: Char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> appendEscapedControlChar(char = char)
                }
            }
        }
    }

    // 控制字符不能原样出现在 JSON 字符串中，需要编码成标准 unicode 转义。
    private fun StringBuilder.appendEscapedControlChar(char: Char): Unit {
        if (char.code < JSON_CONTROL_CHAR_BOUNDARY) {
            append("\\u")
            append(char.code.toString(radix = HEX_RADIX).padStart(length = UNICODE_ESCAPE_WIDTH, padChar = '0'))
        } else {
            append(char)
        }
    }

    /**
     * JSON 控制字符上界。
     */
    private const val JSON_CONTROL_CHAR_BOUNDARY = 0x20

    /**
     * unicode 转义使用的十六进制基数。
     */
    private const val HEX_RADIX = 16

    /**
     * `\\uXXXX` 中十六进制部分的宽度。
     */
    private const val UNICODE_ESCAPE_WIDTH = 4
}
