package com.valiantyan.anrmonitor.reporter.retry

import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.AnrReport

/**
 * 报告重试调度器，负责把 [ReportRetryQueue] 中到期的报告重新交给宿主上传器。
 *
 * @param retryQueue 报告重试队列。
 * @param clock uptime 时间源。
 * @param uploader 宿主上传动作。
 */
class ReportRetryDispatcher(
    private val retryQueue: ReportRetryQueue,
    private val clock: Clock,
    private val uploader: (AnrReport) -> UploadResult,
) {
    // 队列只保存压缩载荷和退避元信息，调度器保存本进程内可重试的报告对象。
    private val reportsByFileName: MutableMap<String, AnrReport> = linkedMapOf()

    // 正在上传的文件名，避免首次上传和后台重试并发上传同一份报告。
    private val inFlightFileNames: MutableSet<String> = linkedSetOf()

    /**
     * 入队报告并记录可重试对象，采样或限频跳过时不保存对象。
     *
     * @param fileName 报告文件名。
     * @param payloadText JSON 报告文本。
     * @param report 原始报告对象。
     * @return 入队或跳过结果。
     */
    @Synchronized
    fun enqueueReport(
        fileName: String,
        payloadText: String,
        report: AnrReport,
    ): ReportEnqueueResult {
        val result: ReportEnqueueResult = retryQueue.enqueue(
            fileName = fileName,
            payloadText = payloadText,
            nowUptimeMs = clock.uptimeMillis(),
        )
        if (result is ReportEnqueueResult.Enqueued) {
            reportsByFileName[fileName] = report
            inFlightFileNames += fileName
        }
        return result
    }

    /**
     * 记录上传结果，并在成功或跳过后清理可重试对象。
     *
     * @param fileName 报告文件名。
     * @param result 宿主上传结果。
     */
    @Synchronized
    fun recordUploadResult(
        fileName: String,
        result: UploadResult,
    ): Unit {
        retryQueue.recordUploadResult(
            fileName = fileName,
            result = result,
            nowUptimeMs = clock.uptimeMillis(),
        )
        inFlightFileNames.remove(element = fileName)
        if (result !is UploadResult.Failure) {
            reportsByFileName.remove(key = fileName)
        }
    }

    /**
     * 消费已到期的重试报告，并返回本轮实际尝试数量。
     *
     * @param maxCount 单轮最多重试数量。
     * @return 实际触发上传器的报告数量。
     */
    fun flushDueReports(maxCount: Int): Int {
        val dueReports: List<Pair<String, AnrReport>> = getDueReportsAndMarkInFlight(maxCount = maxCount)
        dueReports.forEach { item: Pair<String, AnrReport> ->
            val result: UploadResult = uploadSafely(report = item.second)
            recordUploadResult(
                fileName = item.first,
                result = result,
            )
        }
        return dueReports.size
    }

    // 获取当前已到期且仍有原始报告对象的重试项，并标记本轮即将上传的报告。
    @Synchronized
    private fun getDueReportsAndMarkInFlight(maxCount: Int): List<Pair<String, AnrReport>> {
        val dueReports: List<Pair<String, AnrReport>> = retryQueue.dueReports(
            nowUptimeMs = clock.uptimeMillis(),
            maxCount = Int.MAX_VALUE,
        ).filterNot { queuedReport: QueuedReport ->
            queuedReport.fileName in inFlightFileNames
        }.take(n = maxCount.coerceAtLeast(minimumValue = 0)).mapNotNull { queuedReport: QueuedReport ->
            reportsByFileName[queuedReport.fileName]?.let { report: AnrReport ->
                queuedReport.fileName to report
            }
        }
        dueReports.forEach { item: Pair<String, AnrReport> ->
            inFlightFileNames += item.first
        }
        return dueReports
    }

    // 宿主上传器异常不能杀死 SDK 重试线程，统一转成失败结果继续退避。
    private fun uploadSafely(report: AnrReport): UploadResult {
        return try {
            uploader(report)
        } catch (error: RuntimeException) {
            UploadResult.Failure(reason = error.message ?: error.javaClass.simpleName)
        }
    }
}
