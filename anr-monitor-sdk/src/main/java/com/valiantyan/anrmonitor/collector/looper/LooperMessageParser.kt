package com.valiantyan.anrmonitor.collector.looper

/**
 * 解析 Android Looper Printer 文本，提取 Handler dispatch 的起止边界和消息元信息。
 */
object LooperMessageParser {
    /**
     * 将单行 Looper Printer 输出转换为结构化事件。
     *
     * @param line 原始 Printer 文本。
     * @return 解析后的 [LooperDispatchEvent]，缺失字段使用空字符串或 null 表示。
     */
    fun parse(line: String): LooperDispatchEvent {
        val isStart: Boolean = line.startsWith(prefix = START_PREFIX)
        val targetClass: String = extractBetween(
            value = line,
            start = "(",
            end = ")",
        )
        return LooperDispatchEvent(
            isStart = isStart,
            targetClass = targetClass,
            callbackClass = extractCallback(line = line),
            what = extractWhat(line = line),
        )
    }

    /**
     * 提取两个标记之间的内容；格式异常时返回空字符串，避免采集链路抛出异常。
     */
    private fun extractBetween(
        value: String,
        start: String,
        end: String,
    ): String {
        val startIndex: Int = value.indexOf(string = start)
        if (startIndex < 0) {
            return ""
        }
        val contentStart: Int = startIndex + start.length
        val endIndex: Int = value.indexOf(
            string = end,
            startIndex = contentStart,
        )
        if (endIndex < contentStart) {
            return ""
        }
        return value.substring(
            startIndex = contentStart,
            endIndex = endIndex,
        )
    }

    /**
     * 提取 callback 文本；Android 会用 null 表示没有 Runnable callback。
     */
    private fun extractCallback(line: String): String? {
        val callbackPart: String = line.substringAfter(
            delimiter = "} ",
            missingDelimiterValue = "",
        )
        val callback: String = callbackPart.substringBefore(
            delimiter = ":",
            missingDelimiterValue = callbackPart,
        ).trim()
        if (callback.isBlank() || callback == "null") {
            return null
        }
        return callback
    }

    /**
     * 提取 Handler message what；结束日志通常没有 what，因此允许返回 null。
     */
    private fun extractWhat(line: String): Int? {
        val value: String = line.substringAfterLast(
            delimiter = ":",
            missingDelimiterValue = "",
        ).trim()
        return value.toIntOrNull()
    }

    /**
     * Android Looper Printer 表示 dispatch 开始的固定前缀。
     */
    private const val START_PREFIX: String = ">>>>>"
}
