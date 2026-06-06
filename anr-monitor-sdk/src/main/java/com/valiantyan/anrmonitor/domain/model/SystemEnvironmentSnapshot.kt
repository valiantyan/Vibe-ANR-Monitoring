package com.valiantyan.anrmonitor.domain.model

/**
 * 系统环境证据可得性，避免把权限或 ROM 限制误解为环境正常。
 *
 * @property cpuLoadAvailable CPU Load 证据是否可用。
 * @property memoryAvailable 内存证据是否可用。
 * @property storageAvailable 存储空间证据是否可用。
 * @property processIoAvailable 进程 I/O 证据是否可用。
 */
data class EnvironmentEvidenceAvailability(
    val cpuLoadAvailable: Boolean,
    val memoryAvailable: Boolean,
    val storageAvailable: Boolean,
    val processIoAvailable: Boolean,
)

/**
 * 内存环境快照。
 *
 * @property availableBytes 可用内存字节数。
 * @property totalBytes 总内存字节数。
 * @property isLowMemory 系统是否处于低内存状态；无法判断时为 null。
 */
data class MemorySnapshot(
    val availableBytes: Long,
    val totalBytes: Long,
    val isLowMemory: Boolean?,
)

/**
 * 当前进程 I/O 快照。
 *
 * @property readBytes 当前进程累计读取字节数。
 * @property writeBytes 当前进程累计写入字节数。
 * @property cancelledWriteBytes 当前进程被取消写入的字节数。
 */
data class ProcessIoSnapshot(
    val readBytes: Long,
    val writeBytes: Long,
    val cancelledWriteBytes: Long,
)

/**
 * ANR 发生时的系统和设备环境快照。
 *
 * @property loadAverage1m 1 分钟系统负载均值，无法读取时为 null。
 * @property memory 内存快照，无法读取时为 null。
 * @property availableStorageBytes 数据分区可用存储空间，无法读取时为 null。
 * @property processIo 当前进程 I/O 快照，无法读取时为 null。
 * @property androidVersion Android 版本字符串。
 * @property manufacturer 设备厂商。
 * @property model 设备型号。
 * @property availability 各类环境证据的可得性。
 * @property failureReasons 采集失败或降级原因。
 */
data class SystemEnvironmentSnapshot(
    val loadAverage1m: Double?,
    val memory: MemorySnapshot?,
    val availableStorageBytes: Long?,
    val processIo: ProcessIoSnapshot?,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
    val availability: EnvironmentEvidenceAvailability,
    val failureReasons: List<String>,
) {
    /**
     * 环境快照辅助构造器。
     */
    companion object {
        /**
         * 返回不可用环境快照，用于关闭采集或兜底构造。
         *
         * @param reason 环境采集不可用原因。
         * @return 不包含环境数值但保留失败原因的快照。
         */
        fun unavailable(reason: String): SystemEnvironmentSnapshot {
            return SystemEnvironmentSnapshot(
                loadAverage1m = null,
                memory = null,
                availableStorageBytes = null,
                processIo = null,
                androidVersion = UNKNOWN_VALUE,
                manufacturer = UNKNOWN_VALUE,
                model = UNKNOWN_VALUE,
                availability = EnvironmentEvidenceAvailability(
                    cpuLoadAvailable = false,
                    memoryAvailable = false,
                    storageAvailable = false,
                    processIoAvailable = false,
                ),
                failureReasons = listOf(reason),
            )
        }

        /**
         * 设备信息不可用时使用的稳定占位值。
         */
        private const val UNKNOWN_VALUE: String = "unknown"
    }
}
