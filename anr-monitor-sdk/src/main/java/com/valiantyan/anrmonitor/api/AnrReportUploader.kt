package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrReport

/**
 * 宿主应用提供的报告上传扩展点，SDK 不直接绑定具体网络库。
 */
fun interface AnrReportUploader {
    /**
     * 上传或转交 [report]，返回值用于驱动重试、限频和本地诊断。
     *
     * @param report 已完成归因和诊断拼装的 ANR 报告。
     * @return 宿主上报链路处理结果。
     */
    fun upload(report: AnrReport): UploadResult
}
