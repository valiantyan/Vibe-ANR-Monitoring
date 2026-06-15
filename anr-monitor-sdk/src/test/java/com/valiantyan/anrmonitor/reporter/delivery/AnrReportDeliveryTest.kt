package com.valiantyan.anrmonitor.reporter.delivery

import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.AnrReport
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Test

/**
 * 验证报告 delivery module 吸收本地写入、上传入队和失败重试细节。
 */
class AnrReportDeliveryTest {
    // 每个测试创建的临时目录，用例结束后统一清理。
    private val temporaryDirectories: MutableList<File> = mutableListOf()

    /**
     * 清理测试生成的本地报告目录，避免单测长期运行污染系统临时目录。
     */
    @After
    fun tearDown(): Unit {
        temporaryDirectories.forEach { directory: File ->
            directory.deleteRecursively()
        }
        temporaryDirectories.clear()
    }

    /**
     * 本地写入应保持在 delivery implementation 内，调用方不需要知道 encoder 或目录策略。
     */
    @Test
    fun writeLocalReportPersistsJsonReport(): Unit {
        val delivery: AnrReportDelivery = createDelivery(uploadEnabled = false)
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )
        val writtenFile = delivery.writeLocalReport(report = report)
        assertTrue(writtenFile.exists())
        assertTrue(writtenFile.name.endsWith(suffix = ".json"))
        assertTrue(writtenFile.readText(charset = Charsets.UTF_8).contains(other = "\"eventId\":\"empty\""))
    }

    /**
     * 上传关闭时 delivery 应直接跳过远端扩展点，避免 runtime 关心上传 gating。
     */
    @Test
    fun uploadReportIfEnabledSkipsUploaderWhenUploadDisabled(): Unit {
        val uploadedEventIds: MutableList<String> = mutableListOf()
        val delivery: AnrReportDelivery = createDelivery(
            uploadEnabled = false,
            uploadedEventIds = uploadedEventIds,
        )
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )
        val result: UploadResult? = delivery.uploadReportIfEnabled(report = report)
        assertEquals(null, result)
        assertTrue(uploadedEventIds.isEmpty())
    }

    /**
     * 采样跳过时 delivery 不应调用宿主上传器，避免 runtime 了解 queue 的 skip 细节。
     */
    @Test
    fun uploadReportIfEnabledSkipsUploaderWhenSampledOut(): Unit {
        val delivery: AnrReportDelivery = createDelivery(
            uploadEnabled = true,
            sampleRate = 0.0f,
            resultProvider = {
                throw AssertionError("sampled out report must not call uploader")
            },
        )
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )
        val result: UploadResult? = delivery.uploadReportIfEnabled(report = report)
        assertEquals(null, result)
    }

    /**
     * 首次上传失败后 delivery 应记录重试状态，到期后重新调用宿主上传器。
     */
    @Test
    fun uploadReportIfEnabledRecordsFailureForRetry(): Unit {
        val clock = MutableClock(value = 1_000L)
        val uploadedEventIds: MutableList<String> = mutableListOf()
        val uploadResults: List<UploadResult> = listOf(
            UploadResult.Failure(reason = "offline"),
            UploadResult.Success,
        )
        var uploadIndex: Int = 0
        val delivery: AnrReportDelivery = createDelivery(
            uploadEnabled = true,
            clock = clock,
            uploadedEventIds = uploadedEventIds,
            resultProvider = {
                val result: UploadResult = uploadResults[uploadIndex]
                uploadIndex += 1
                result
            },
        )
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )
        val firstResult: UploadResult? = delivery.uploadReportIfEnabled(report = report)
        clock.value = 2_100L
        val retryCount: Int = delivery.flushDueReports(maxCount = 10)
        assertEquals(UploadResult.Failure(reason = "offline"), firstResult)
        assertEquals(1, retryCount)
        assertEquals(listOf("empty", "empty"), uploadedEventIds)
    }

    /**
     * 首次上传尚未记录结果时，后台 flush 可继续运行但不应重传同一个报告。
     */
    @Test
    fun flushDueReportsSkipsInitialUploadInFlightReport(): Unit {
        val clock = MutableClock(value = 1_000L)
        val uploadCallCount = AtomicInteger(0)
        val firstUploadEntered = CountDownLatch(1)
        val releaseFirstUpload = CountDownLatch(1)
        val retryFlushStarted = CountDownLatch(1)
        val retryFlushFinished = CountDownLatch(1)
        val retryFlushCount = AtomicInteger(-1)
        lateinit var delivery: AnrReportDelivery
        delivery = createDelivery(
            uploadEnabled = true,
            clock = clock,
            resultProvider = {
                val callCount: Int = uploadCallCount.incrementAndGet()
                if (callCount == 1) {
                    firstUploadEntered.countDown()
                    releaseFirstUpload.await()
                }
                UploadResult.Success
            },
        )
        val uploadThread = Thread {
            delivery.uploadReportIfEnabled(
                report = AnrReport.empty(
                    appId = "demo",
                    environment = "debug",
                ),
            )
        }
        uploadThread.start()
        assertTrue(firstUploadEntered.await(1, TimeUnit.SECONDS))
        val retryThread = Thread {
            retryFlushStarted.countDown()
            retryFlushCount.set(delivery.flushDueReports(maxCount = 10))
            retryFlushFinished.countDown()
        }
        retryThread.start()
        assertTrue(retryFlushStarted.await(1, TimeUnit.SECONDS))
        val finishedBeforeInitialUploadResult: Boolean = retryFlushFinished.await(200, TimeUnit.MILLISECONDS)
        releaseFirstUpload.countDown()
        assertTrue(finishedBeforeInitialUploadResult)
        assertEquals(0, retryFlushCount.get())
        assertEquals(1, uploadCallCount.get())
        uploadThread.join(1_000L)
        retryThread.join(1_000L)
        assertTrue(!uploadThread.isAlive)
        assertTrue(!retryThread.isAlive)
        assertEquals(1, uploadCallCount.get())
    }

    /**
     * 首次上传抛异常时仍应记录失败退避，避免下一轮立刻重试。
     */
    @Test
    fun uploadReportIfEnabledRecordsThrownFailureForRetryBackoff(): Unit {
        val clock = MutableClock(value = 1_000L)
        var shouldThrow: Boolean = true
        val delivery: AnrReportDelivery = createDelivery(
            uploadEnabled = true,
            clock = clock,
            resultProvider = {
                if (shouldThrow) {
                    shouldThrow = false
                    throw IllegalStateException("offline")
                }
                UploadResult.Success
            },
        )
        try {
            delivery.uploadReportIfEnabled(
                report = AnrReport.empty(
                    appId = "demo",
                    environment = "debug",
                ),
            )
            fail("initial uploader exception must propagate to runtime")
        } catch (error: IllegalStateException) {
            assertEquals("offline", error.message)
        }
        assertEquals(0, delivery.flushDueReports(maxCount = 10))
        clock.value = 2_100L
        assertEquals(1, delivery.flushDueReports(maxCount = 10))
    }

    // 创建测试用 delivery，固定采样和限频配置以稳定验证上传重试路径。
    private fun createDelivery(
        uploadEnabled: Boolean,
        sampleRate: Float = 1.0f,
        clock: MutableClock = MutableClock(value = 1_000L),
        uploadedEventIds: MutableList<String> = mutableListOf(),
        resultProvider: () -> UploadResult = { UploadResult.Success },
    ): AnrReportDelivery {
        val reportDirectory = Files.createTempDirectory("anr-delivery-test").toFile()
        temporaryDirectories += reportDirectory
        val config = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
            uploadEnabled = uploadEnabled,
            sampleRate = sampleRate,
            reportUploadMinIntervalMs = 0L,
            reportRetryInitialDelayMs = 1_000L,
            reportRetryMaxDelayMs = 10_000L,
        )
        val uploader = AnrReportUploader { report: AnrReport ->
            uploadedEventIds += report.snapshot.eventId
            resultProvider()
        }
        return AnrReportDelivery(
            config = config,
            uploader = uploader,
            clock = clock,
            reportDirectory = reportDirectory,
        )
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
