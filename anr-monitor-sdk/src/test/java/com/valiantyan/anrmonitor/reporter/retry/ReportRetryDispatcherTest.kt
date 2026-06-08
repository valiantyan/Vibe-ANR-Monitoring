package com.valiantyan.anrmonitor.reporter.retry

import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.AnrReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证运行时重试调度器会真正消费到期队列，而不是只保存退避元信息。
 */
class ReportRetryDispatcherTest {
    /**
     * 失败报告到期后应再次调用宿主上传器，成功后从队列移除。
     */
    @Test
    fun flushDueReportsRetriesFailedReportAndRemovesSuccess(): Unit {
        val clock = MutableClock(value = 1_000L)
        val queue = ReportRetryQueue(
            sampleRate = 1.0f,
            initialRetryDelayMs = 1_000L,
            maxRetryDelayMs = 10_000L,
            sampleBucketProvider = { 0.0f },
        )
        val uploadedEventIds: MutableList<String> = mutableListOf()
        val dispatcher = ReportRetryDispatcher(
            retryQueue = queue,
            clock = clock,
            uploader = { report: AnrReport ->
                uploadedEventIds += report.snapshot.eventId
                UploadResult.Success
            },
        )
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )

        dispatcher.enqueueReport(
            fileName = "event-1.json.gz",
            payloadText = "{}",
            report = report,
        )
        dispatcher.recordUploadResult(
            fileName = "event-1.json.gz",
            result = UploadResult.Failure(reason = "offline"),
        )
        clock.value = 2_100L
        val retryCount: Int = dispatcher.flushDueReports(maxCount = 10)

        assertEquals(1, retryCount)
        assertEquals(listOf("empty"), uploadedEventIds)
        assertTrue(queue.dueReports(nowUptimeMs = 3_000L).isEmpty())
    }

    private class MutableClock(
        var value: Long,
    ) : Clock {
        /**
         * 返回可控 uptime，驱动重试到期判断。
         */
        override fun uptimeMillis(): Long = value
    }
}
