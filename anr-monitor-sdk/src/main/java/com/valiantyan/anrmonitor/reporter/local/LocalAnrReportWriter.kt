package com.valiantyan.anrmonitor.reporter.local

import android.content.Context
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import com.valiantyan.anrmonitor.reporter.retry.LocalReportMeta
import com.valiantyan.anrmonitor.reporter.retry.ReportRetentionPolicy
import java.io.File
import java.io.IOException

/**
 * 本地 ANR 报告写入器，负责把 JSON 报告落到宿主 app 私有目录。
 */
class LocalAnrReportWriter internal constructor(
    // 报告目录注入点让 JVM 单测可以避开 Android [Context]。
    private val reportDirectory: File,
    // JSON 编码器保持可替换，后续 gzip 或 schema 升级可以复用写入逻辑。
    private val encoder: AnrReportJsonEncoder,
    // 本地报告治理策略，防止大量疑似事件导致目录无限增长。
    private val retentionPolicy: ReportRetentionPolicy = ReportRetentionPolicy(maxFileCount = Int.MAX_VALUE),
    // SDK 自监控器，记录写入、清理和失败指标。
    private val selfMonitor: SdkSelfMonitor? = null,
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
        retentionPolicy: ReportRetentionPolicy = ReportRetentionPolicy(maxFileCount = Int.MAX_VALUE),
        selfMonitor: SdkSelfMonitor? = null,
    ) : this(
        reportDirectory = File(context.filesDir, REPORT_DIRECTORY_NAME),
        encoder = encoder,
        retentionPolicy = retentionPolicy,
        selfMonitor = selfMonitor,
    )

    /**
     * 将 ANR 报告写入 `${eventId}.json` 文件。
     *
     * @param report 待落盘的 ANR 报告。
     * @return 已写入的 JSON 文件。
     * @throws IllegalStateException 当报告目录无法创建时抛出。
     */
    fun write(report: AnrReport): File {
        val startMs: Long = System.currentTimeMillis()
        try {
            ensureReportDirectory()
            val file: File = File(reportDirectory, "${safeFileName(eventId = report.snapshot.eventId)}.json")
            file.writeText(
                text = encoder.encode(report = report),
                charset = Charsets.UTF_8,
            )
            selfMonitor?.increment(name = "local_report_written")
            pruneExpiredReports(nowUptimeMs = System.currentTimeMillis())
            selfMonitor?.recordCost(
                name = "report_write_cost_ms",
                costMs = System.currentTimeMillis() - startMs,
            )
            return file
        } catch (error: IOException) {
            selfMonitor?.increment(name = "local_report_write_failure")
            throw error
        } catch (error: IllegalStateException) {
            selfMonitor?.increment(name = "local_report_write_failure")
            throw error
        }
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

    // 写入完成后清理超出保留策略的历史报告，清理失败只记录自监指标。
    private fun pruneExpiredReports(nowUptimeMs: Long): Unit {
        val files: List<File> = reportDirectory.listFiles()
            ?.filter { file: File -> file.isFile && isReportFile(file = file) }
            ?: return
        val filesByName: Map<String, File> = files.associateBy { file: File -> file.name }
        retentionPolicy.selectExpiredReports(
            reports = files.map { file: File -> file.toLocalReportMeta() },
            nowUptimeMs = nowUptimeMs,
        ).forEach { meta: LocalReportMeta ->
            deleteExpiredFile(file = filesByName[meta.fileName])
        }
    }

    // 只治理 SDK 自己生成的报告文件，避免误删宿主目录下其他文件。
    private fun isReportFile(file: File): Boolean {
        return file.name.endsWith(suffix = ".json") || file.name.endsWith(suffix = ".json.gz")
    }

    // 把磁盘文件转换成轻量元信息，保留策略不读取报告正文以降低成本。
    private fun File.toLocalReportMeta(): LocalReportMeta {
        return LocalReportMeta(
            fileName = name,
            createUptimeMs = lastModified(),
            sizeBytes = length(),
        )
    }

    // 删除过期文件并记录结果，清理失败不阻断本次报告写入。
    private fun deleteExpiredFile(file: File?): Unit {
        if (file == null) {
            return
        }
        if (file.delete()) {
            selfMonitor?.increment(name = "local_report_deleted")
        } else {
            selfMonitor?.increment(name = "local_report_delete_failure")
        }
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
