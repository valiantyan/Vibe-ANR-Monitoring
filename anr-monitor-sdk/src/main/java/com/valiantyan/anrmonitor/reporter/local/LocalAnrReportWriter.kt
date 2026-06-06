package com.valiantyan.anrmonitor.reporter.local

import android.content.Context
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import java.io.File

/**
 * 本地 ANR 报告写入器，负责把 JSON 报告落到宿主 app 私有目录。
 */
class LocalAnrReportWriter internal constructor(
    // 报告目录注入点让 JVM 单测可以避开 Android [Context]。
    private val reportDirectory: File,
    // JSON 编码器保持可替换，后续 gzip 或 schema 升级可以复用写入逻辑。
    private val encoder: AnrReportJsonEncoder,
) {
    /**
     * 使用宿主 app 私有文件目录创建写入器。
     *
     * @param context 宿主应用上下文。
     * @param encoder 报告 JSON 编码器。
     */
    constructor(
        context: Context,
        encoder: AnrReportJsonEncoder = AnrReportJsonEncoder(),
    ) : this(
        reportDirectory = File(context.filesDir, REPORT_DIRECTORY_NAME),
        encoder = encoder,
    )

    /**
     * 将 ANR 报告写入 `${eventId}.json` 文件。
     *
     * @param report 待落盘的 ANR 报告。
     * @return 已写入的 JSON 文件。
     * @throws IllegalStateException 当报告目录无法创建时抛出。
     */
    fun write(report: AnrReport): File {
        ensureReportDirectory()
        val file: File = File(reportDirectory, "${safeFileName(eventId = report.snapshot.eventId)}.json")
        file.writeText(
            text = encoder.encode(report = report),
            charset = Charsets.UTF_8,
        )
        return file
    }

    // 确保目录存在，目录创建失败时主动暴露问题，避免调用方误以为报告已落盘。
    private fun ensureReportDirectory(): Unit {
        if (reportDirectory.exists()) {
            return
        }
        if (!reportDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create ANR report directory: ${reportDirectory.absolutePath}")
        }
    }

    // eventId 参与文件名时只保留安全字符，避免路径分隔符改变写入位置。
    private fun safeFileName(eventId: String): String {
        return eventId.map { char: Char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') {
                char
            } else {
                '_'
            }
        }.joinToString(separator = "")
    }

    /**
     * 本地报告目录常量。
     */
    companion object {
        /**
         * SDK 在 app 私有文件目录下使用的报告子目录名称。
         */
        private const val REPORT_DIRECTORY_NAME = "anr-monitor-reports"
    }
}
