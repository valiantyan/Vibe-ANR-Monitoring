package com.valiantyan.anrmonitor.reporter.local

import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAnrReportWriterTest {
    @Test
    fun writeCreatesJsonFileUnderReportDirectory(): Unit {
        val directory: File = Files.createTempDirectory("anr-monitor-reports").toFile()
        val writer: LocalAnrReportWriter = LocalAnrReportWriter(
            reportDirectory = directory,
            encoder = AnrReportJsonEncoder(),
        )
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "test",
        )

        val file: File = writer.write(report = report)

        assertEquals(File(directory, "empty.json").absolutePath, file.absolutePath)
        assertTrue(file.exists())
        assertTrue(file.readText(charset = Charsets.UTF_8).contains("\"eventId\":\"empty\""))
        directory.deleteRecursively()
    }
}
