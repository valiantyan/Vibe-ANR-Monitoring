package com.valiantyan.anrmonitor.reporter.retry

import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证报告重试队列的采样、限频、压缩和失败重试元信息。
 */
class ReportRetryQueueTest {
    /**
     * 入队报告应按 gzip 压缩保存，并记录自监成功指标。
     */
    @Test
    fun enqueueCompressesPayloadAndRecordsMetric(): Unit {
        val selfMonitor = SdkSelfMonitor()
        val queue = ReportRetryQueue(
            sampleRate = 1.0f,
            minEnqueueIntervalMs = 0L,
            selfMonitor = selfMonitor,
            sampleBucketProvider = { 0.0f },
        )

        val result: ReportEnqueueResult = queue.enqueue(
            fileName = "event-1.json",
            payloadText = "{\"eventId\":\"event-1\"}",
            nowUptimeMs = 1_000L,
        )

        val enqueued: ReportEnqueueResult.Enqueued = result as ReportEnqueueResult.Enqueued
        assertTrue(enqueued.report.isCompressed)
        assertEquals("{\"eventId\":\"event-1\"}", unzip(bytes = enqueued.report.payloadBytes))
        assertEquals(1L, selfMonitor.snapshotCounters()["report_queue_enqueued"])
    }

    /**
     * 采样和限频命中时不入队，并分别记录跳过原因。
     */
    @Test
    fun enqueueSkipsBySampleAndRateLimit(): Unit {
        val selfMonitor = SdkSelfMonitor()
        val queue = ReportRetryQueue(
            sampleRate = 0.5f,
            minEnqueueIntervalMs = 1_000L,
            selfMonitor = selfMonitor,
            sampleBucketProvider = { 0.9f },
        )

        val sampledOut: ReportEnqueueResult = queue.enqueue(
            fileName = "sampled.json",
            payloadText = "{}",
            nowUptimeMs = 1_000L,
        )
        val rateLimitedQueue = ReportRetryQueue(
            sampleRate = 1.0f,
            minEnqueueIntervalMs = 1_000L,
            selfMonitor = selfMonitor,
            sampleBucketProvider = { 0.0f },
        )
        rateLimitedQueue.enqueue(
            fileName = "first.json",
            payloadText = "{}",
            nowUptimeMs = 1_000L,
        )
        val rateLimited: ReportEnqueueResult = rateLimitedQueue.enqueue(
            fileName = "second.json",
            payloadText = "{}",
            nowUptimeMs = 1_500L,
        )

        assertEquals(ReportSkipReason.SAMPLED_OUT, (sampledOut as ReportEnqueueResult.Skipped).reason)
        assertEquals(ReportSkipReason.RATE_LIMITED, (rateLimited as ReportEnqueueResult.Skipped).reason)
        assertEquals(1L, selfMonitor.snapshotCounters()["report_queue_sampled_out"])
        assertEquals(1L, selfMonitor.snapshotCounters()["report_queue_rate_limited"])
    }

    /**
     * 上报失败后报告保留在队列中，并按照退避时间进入下一轮重试。
     */
    @Test
    fun recordUploadFailureSchedulesRetryAndSuccessRemovesReport(): Unit {
        val queue = ReportRetryQueue(
            sampleRate = 1.0f,
            initialRetryDelayMs = 1_000L,
            maxRetryDelayMs = 10_000L,
            sampleBucketProvider = { 0.0f },
        )
        queue.enqueue(
            fileName = "event-1.json",
            payloadText = "{}",
            nowUptimeMs = 1_000L,
        )

        queue.recordUploadResult(
            fileName = "event-1.json",
            result = UploadResult.Failure(reason = "offline"),
            nowUptimeMs = 1_100L,
        )
        val blocked: List<QueuedReport> = queue.dueReports(nowUptimeMs = 1_500L)
        val due: List<QueuedReport> = queue.dueReports(nowUptimeMs = 2_100L)
        queue.recordUploadResult(
            fileName = "event-1.json",
            result = UploadResult.Success,
            nowUptimeMs = 2_200L,
        )

        assertTrue(blocked.isEmpty())
        assertEquals(1, due.size)
        assertEquals(1, due.first().attemptCount)
        assertEquals("offline", due.first().lastFailureReason)
        assertTrue(queue.dueReports(nowUptimeMs = 3_000L).isEmpty())
    }

    // gzip 测试解码工具，避免把压缩实现细节暴露到生产 API。
    private fun unzip(bytes: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }
}
