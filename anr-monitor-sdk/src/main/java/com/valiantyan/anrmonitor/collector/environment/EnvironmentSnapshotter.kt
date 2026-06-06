package com.valiantyan.anrmonitor.collector.environment

import com.valiantyan.anrmonitor.domain.model.EnvironmentEvidenceAvailability
import com.valiantyan.anrmonitor.domain.model.MemorySnapshot
import com.valiantyan.anrmonitor.domain.model.ProcessIoSnapshot
import com.valiantyan.anrmonitor.domain.model.SystemEnvironmentSnapshot
import java.io.IOException

/**
 * 设备 Build 信息，测试可注入以避免依赖 Android 静态字段。
 *
 * @property androidVersion Android 版本字符串。
 * @property manufacturer 设备厂商。
 * @property model 设备型号。
 */
data class DeviceBuildInfo(
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
)

/**
 * 单项环境读取结果，显式区分成功值和不可用原因。
 *
 * @property value 成功读取到的值。
 * @property failureReason 读取失败或不可用原因。
 */
data class EnvironmentReadResult<T>(
    val value: T?,
    val failureReason: String?,
) {
    /**
     * 环境读取结果辅助构造器。
     */
    companion object {
        /**
         * 创建成功读取结果。
         *
         * @param value 读取到的环境证据。
         * @return 带值且无失败原因的读取结果。
         */
        fun <T> available(value: T): EnvironmentReadResult<T> {
            return EnvironmentReadResult(
                value = value,
                failureReason = null,
            )
        }

        /**
         * 创建不可用读取结果。
         *
         * @param reason 不可用原因。
         * @return 无值且带失败原因的读取结果。
         */
        fun <T> unavailable(reason: String): EnvironmentReadResult<T> {
            return EnvironmentReadResult(
                value = null,
                failureReason = reason,
            )
        }
    }
}

/**
 * 系统环境快照采集器，用于补充外部负载、内存、存储和进程 I/O 证据。
 *
 * @param buildProvider 设备 Build 信息提供方。
 * @param loadAverageReader CPU Load 读取方。
 * @param memoryReader 内存信息读取方。
 * @param storageReader 数据分区可用空间读取方。
 * @param processIoReader 当前进程 I/O 读取方。
 */
class EnvironmentSnapshotter(
    private val buildProvider: () -> DeviceBuildInfo = ::readDeviceBuildInfo,
    private val loadAverageReader: () -> EnvironmentReadResult<Double> = ::readLoadAverage1m,
    private val memoryReader: () -> EnvironmentReadResult<MemorySnapshot> = ::readMemorySnapshot,
    private val storageReader: () -> EnvironmentReadResult<Long> = ::readAvailableStorageBytes,
    private val processIoReader: () -> EnvironmentReadResult<ProcessIoSnapshot> = ::readProcessIoSnapshot,
) {
    /**
     * 采集系统环境快照；单项失败不会影响其他项输出。
     *
     * @return 带可得性和失败原因的系统环境快照。
     */
    fun capture(): SystemEnvironmentSnapshot {
        val buildInfo: DeviceBuildInfo = readBuildInfoSafely()
        val loadAverage: EnvironmentReadResult<Double> = safeRead(
            label = "load average",
            reader = loadAverageReader,
        )
        val memory: EnvironmentReadResult<MemorySnapshot> = safeRead(
            label = "memory",
            reader = memoryReader,
        )
        val storage: EnvironmentReadResult<Long> = safeRead(
            label = "storage",
            reader = storageReader,
        )
        val processIo: EnvironmentReadResult<ProcessIoSnapshot> = safeRead(
            label = "process io",
            reader = processIoReader,
        )
        return SystemEnvironmentSnapshot(
            loadAverage1m = loadAverage.value,
            memory = memory.value,
            availableStorageBytes = storage.value,
            processIo = processIo.value,
            androidVersion = buildInfo.androidVersion,
            manufacturer = buildInfo.manufacturer,
            model = buildInfo.model,
            availability = EnvironmentEvidenceAvailability(
                cpuLoadAvailable = loadAverage.value != null,
                memoryAvailable = memory.value != null,
                storageAvailable = storage.value != null,
                processIoAvailable = processIo.value != null,
            ),
            failureReasons = failureReasons(
                loadAverage = loadAverage,
                memory = memory,
                storage = storage,
                processIo = processIo,
            ),
        )
    }

    // 读取 Build 信息失败时降级为 unknown，避免环境采集整体失败。
    private fun readBuildInfoSafely(): DeviceBuildInfo {
        return runCatching { buildProvider() }.getOrElse {
            DeviceBuildInfo(
                androidVersion = UNKNOWN_VALUE,
                manufacturer = UNKNOWN_VALUE,
                model = UNKNOWN_VALUE,
            )
        }
    }

    // 对每项 reader 增加异常边界，保证单项失败进入 failureReasons。
    private fun <T> safeRead(
        label: String,
        reader: () -> EnvironmentReadResult<T>,
    ): EnvironmentReadResult<T> {
        return try {
            reader()
        } catch (error: SecurityException) {
            EnvironmentReadResult.unavailable(reason = "$label denied: ${error.message.orEmpty()}")
        } catch (error: IOException) {
            EnvironmentReadResult.unavailable(reason = "$label io failed: ${error.message.orEmpty()}")
        } catch (error: RuntimeException) {
            EnvironmentReadResult.unavailable(reason = "$label failed: ${error.message.orEmpty()}")
        }
    }

    // 汇总所有不可用原因，后续报告和 SDK 自诊断共用同一份原因列表。
    private fun failureReasons(
        loadAverage: EnvironmentReadResult<Double>,
        memory: EnvironmentReadResult<MemorySnapshot>,
        storage: EnvironmentReadResult<Long>,
        processIo: EnvironmentReadResult<ProcessIoSnapshot>,
    ): List<String> {
        return listOfNotNull(
            loadAverage.failureReason,
            memory.failureReason,
            storage.failureReason,
            processIo.failureReason,
        )
    }

    private companion object {
        /**
         * 设备信息不可用时使用的稳定占位值。
         */
        private const val UNKNOWN_VALUE: String = "unknown"
    }
}
