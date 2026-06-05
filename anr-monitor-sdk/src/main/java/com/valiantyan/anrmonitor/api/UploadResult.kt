package com.valiantyan.anrmonitor.api

/**
 * 宿主上报扩展点的结果，SDK 后续会据此决定是否重试或记录失败诊断。
 */
sealed interface UploadResult {
    /**
     * 报告已成功交给宿主上报链路。
     */
    data object Success : UploadResult

    /**
     * 宿主主动跳过本次上报，通常用于采样、限频或离线状态。
     */
    data object Skip : UploadResult

    /**
     * 报告上报失败。
     *
     * @property reason 失败原因，便于 SDK 自监控和本地诊断记录。
     */
    data class Failure(
        val reason: String,
    ) : UploadResult
}
