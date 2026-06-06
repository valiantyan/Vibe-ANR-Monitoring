package com.valiantyan.anrmonitor.collector.environment

import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.valiantyan.anrmonitor.domain.model.MemorySnapshot
import com.valiantyan.anrmonitor.domain.model.ProcessIoSnapshot
import java.io.File

// 读取设备 Build 信息，统一裁剪空值为 unknown。
internal fun readDeviceBuildInfo(): DeviceBuildInfo {
    return DeviceBuildInfo(
        androidVersion = Build.VERSION.RELEASE.orEmpty().ifEmpty { UNKNOWN_VALUE },
        manufacturer = Build.MANUFACTURER.orEmpty().ifEmpty { UNKNOWN_VALUE },
        model = Build.MODEL.orEmpty().ifEmpty { UNKNOWN_VALUE },
    )
}

// 读取 1 分钟 load average，作为 CPU/系统负载证据。
internal fun readLoadAverage1m(): EnvironmentReadResult<Double> {
    val line: String = File("/proc/loadavg").readText().trim()
    val value: Double = line.split(regex = WHITE_SPACE).firstOrNull()?.toDoubleOrNull()
        ?: return EnvironmentReadResult.unavailable(reason = "loadavg parse failed")
    return EnvironmentReadResult.available(value = value)
}

// 读取 `/proc/meminfo`，优先使用 MemAvailable，旧系统缺失时降级到 MemFree。
internal fun readMemorySnapshot(): EnvironmentReadResult<MemorySnapshot> {
    val values: Map<String, Long> = File("/proc/meminfo").readLines().mapNotNull { line: String ->
        parseMeminfoLine(line = line)
    }.toMap()
    val totalBytes: Long = values["MemTotal"] ?: return EnvironmentReadResult.unavailable(reason = "meminfo total missing")
    val availableBytes: Long = values["MemAvailable"] ?: values["MemFree"]
        ?: return EnvironmentReadResult.unavailable(reason = "meminfo available missing")
    return EnvironmentReadResult.available(
        value = MemorySnapshot(
            availableBytes = availableBytes,
            totalBytes = totalBytes,
            isLowMemory = null,
        ),
    )
}

// 解析 meminfo 单行并把 kB 转换为 bytes。
private fun parseMeminfoLine(line: String): Pair<String, Long>? {
    val fields: List<String> = line.split(regex = WHITE_SPACE)
    val key: String = fields.getOrNull(index = 0)?.removeSuffix(suffix = ":") ?: return null
    val valueKb: Long = fields.getOrNull(index = 1)?.toLongOrNull() ?: return null
    return key to valueKb * BYTES_PER_KB
}

// 读取数据分区剩余空间，作为存储压力和潜在 I/O 异常的辅助证据。
internal fun readAvailableStorageBytes(): EnvironmentReadResult<Long> {
    val statFs = StatFs(Environment.getDataDirectory().absolutePath)
    return EnvironmentReadResult.available(value = statFs.availableBytes)
}

// 读取当前进程 `/proc/self/io`，ROM 不允许读取时由调用方记录不可用原因。
internal fun readProcessIoSnapshot(): EnvironmentReadResult<ProcessIoSnapshot> {
    val values: Map<String, Long> = File("/proc/self/io").readLines().mapNotNull { line: String ->
        parseProcIoLine(line = line)
    }.toMap()
    return EnvironmentReadResult.available(
        value = ProcessIoSnapshot(
            readBytes = values["read_bytes"] ?: 0L,
            writeBytes = values["write_bytes"] ?: 0L,
            cancelledWriteBytes = values["cancelled_write_bytes"] ?: 0L,
        ),
    )
}

// 解析 `/proc/self/io` 的 `key: value` 行。
private fun parseProcIoLine(line: String): Pair<String, Long>? {
    val parts: List<String> = line.split(
        delimiters = charArrayOf(':'),
        limit = 2,
    )
    val key: String = parts.getOrNull(index = 0)?.trim().orEmpty()
    val value: Long = parts.getOrNull(index = 1)?.trim()?.toLongOrNull() ?: return null
    return key to value
}

/**
 * kB 到字节换算值。
 */
private const val BYTES_PER_KB: Long = 1_024L

/**
 * 设备信息不可用时使用的稳定占位值。
 */
private const val UNKNOWN_VALUE: String = "unknown"

// 连续空白分隔符。
private val WHITE_SPACE: Regex = "\\s+".toRegex()
