package com.valiantyan.anrmonitor.reporter.encoder

import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.EnvironmentEvidenceAvailability
import com.valiantyan.anrmonitor.domain.model.MemorySnapshot
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.ProcessIoSnapshot
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord

/**
 * 将 [AnrReport] 编码为本地落盘使用的稳定 JSON 文本。
 */
class AnrReportJsonEncoder {
    /**
     * 编码完整 ANR 报告，字段只来自已脱敏领域模型。
     *
     * @param report 待编码的 ANR 报告。
     * @return 可直接写入本地文件的 JSON 字符串。
     */
    fun encode(report: AnrReport): String {
        val fields: List<String> = listOf(
            "\"schemaVersion\":${report.schemaVersion}",
            "\"event\":${eventJson(report = report)}",
            "\"mainThread\":${mainThreadJson(report = report)}",
            "\"pendingQueue\":${pendingQueueJson(report = report)}",
            "\"threadCpu\":${threadCpuJson(report = report)}",
            "\"checktime\":${checktimeJson(report = report)}",
            "\"environmentSnapshot\":${environmentJson(report = report)}",
            "\"attribution\":${attributionJson(report = report)}",
            "\"sdkDiagnostics\":${diagnosticsJson(report = report)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码事件基础信息，作为服务端或本地检索的主索引。
    private fun eventJson(report: AnrReport): String {
        val fields: List<String> = listOf(
            "\"eventId\":${string(report.snapshot.eventId)}",
            "\"eventType\":${string(report.snapshot.eventType.name)}",
            "\"appId\":${string(report.snapshot.appId)}",
            "\"environment\":${string(report.snapshot.environment)}",
            "\"timeUptimeMs\":${report.snapshot.timeUptimeMs}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码主线程消息和栈，保留慢消息分析所需的顺序证据。
    private fun mainThreadJson(report: AnrReport): String {
        val fields: List<String> = listOf(
            "\"stackId\":${string(report.snapshot.mainThreadStack.stackId)}",
            "\"threadName\":${string(report.snapshot.mainThreadStack.threadName)}",
            "\"current\":${messageOrNull(record = report.snapshot.currentMessage)}",
            "\"history\":${messages(records = report.snapshot.historyMessages)}",
            "\"stackFrames\":${strings(values = report.snapshot.mainThreadStack.frames)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码 Pending 队列证据，只输出类名字段，避免泄露对象内容。
    private fun pendingQueueJson(report: AnrReport): String {
        val queue = report.snapshot.pendingQueue
        val fields: List<String> = listOf(
            "\"available\":${queue.available}",
            "\"truncated\":${queue.truncated}",
            "\"maxDepth\":${queue.maxDepth}",
            "\"failureReason\":${stringOrNull(value = queue.failureReason)}",
            "\"messages\":${pendingMessages(messages = queue.messages)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码线程 CPU TopN，作为判断进程内资源竞争的辅助证据。
    private fun threadCpuJson(report: AnrReport): String {
        val fields: List<String> = listOf(
            "\"topThreads\":${threadCpuRecords(records = report.snapshot.threadCpuRecords)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码 Checktime 调度延迟，帮助判断 Watchdog 和系统调度是否也被拖慢。
    private fun checktimeJson(report: AnrReport): String {
        val checktime = report.snapshot.checktimeSummary
        val fields: List<String> = listOf(
            "\"available\":${checktime.available}",
            "\"maxDelayMs\":${checktime.maxDelayMs}",
            "\"severeDelayCount\":${checktime.severeDelayCount}",
            "\"recentDelayMs\":${longs(values = checktime.recentDelayMs)}",
            "\"failureReason\":${stringOrNull(value = checktime.failureReason)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码系统环境快照，按可得性标记区分空值和采集失败。
    private fun environmentJson(report: AnrReport): String {
        val environment = report.snapshot.environmentSnapshot
        val fields: List<String> = listOf(
            "\"loadAverage1m\":${doubleOrNull(value = environment.loadAverage1m)}",
            "\"memory\":${memoryOrNull(snapshot = environment.memory)}",
            "\"availableStorageBytes\":${longOrNull(value = environment.availableStorageBytes)}",
            "\"processIo\":${processIoOrNull(snapshot = environment.processIo)}",
            "\"androidVersion\":${string(environment.androidVersion)}",
            "\"manufacturer\":${string(environment.manufacturer)}",
            "\"model\":${string(environment.model)}",
            "\"availability\":${environmentAvailability(availability = environment.availability)}",
            "\"failureReasons\":${strings(values = environment.failureReasons)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码归因结果，便于后续评审直接定位主因、辅因和证据缺口。
    private fun attributionJson(report: AnrReport): String {
        val attribution = report.attribution
        val fields: List<String> = listOf(
            "\"primary\":${string(attribution.primaryCode.name)}",
            "\"secondary\":${strings(values = attribution.secondaryCodes.map { code -> code.name })}",
            "\"confidence\":${string(attribution.confidence.name)}",
            "\"evidence\":${strings(values = attribution.evidenceItems)}",
            "\"missingEvidence\":${strings(values = attribution.missingEvidence)}",
            "\"suggestions\":${strings(values = attribution.actionSuggestions)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码 SDK 自诊断信息，避免采集失败时只剩业务现象。
    private fun diagnosticsJson(report: AnrReport): String {
        val diagnostics = report.diagnostics
        val fields: List<String> = listOf(
            "\"pendingAvailable\":${diagnostics.pendingAvailable}",
            "\"reportBuildCostMs\":${diagnostics.reportBuildCostMs}",
            "\"collectorFailures\":${strings(values = diagnostics.collectorFailures)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码可空消息记录，当前消息不存在时保持 JSON null。
    private fun messageOrNull(record: MessageRecord?): String {
        if (record == null) {
            return "null"
        }
        return "{${messageFields(record = record).joinToString(separator = ",")}}"
    }

    // 编码消息列表，按时间线顺序保留每条消息。
    private fun messages(records: List<MessageRecord>): String {
        return records.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { record: MessageRecord -> "{${messageFields(record = record).joinToString(separator = ",")}}" }
    }

    // 生成消息记录字段，避免把编码策略散落在多个列表编码处。
    private fun messageFields(record: MessageRecord): List<String> {
        return listOf(
            "\"seq\":${record.seq}",
            "\"kind\":${string(record.kind.name)}",
            "\"messageType\":${string(record.messageType)}",
            "\"what\":${intOrNull(value = record.what)}",
            "\"targetClass\":${string(record.targetClass)}",
            "\"callbackClass\":${stringOrNull(value = record.callbackClass)}",
            "\"isCriticalComponent\":${record.isCriticalComponent}",
            "\"startUptimeMs\":${record.startUptimeMs}",
            "\"endUptimeMs\":${longOrNull(value = record.endUptimeMs)}",
            "\"wallMs\":${record.wallMs}",
            "\"cpuMs\":${record.cpuMs}",
            "\"count\":${record.count}",
            "\"sampleStackIds\":${strings(values = record.sampleStackIds)}",
        )
    }

    // 编码 Pending 消息列表，保持队列顺序和屏障证据位置。
    private fun pendingMessages(messages: List<PendingMessage>): String {
        return messages.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { message: PendingMessage -> "{${pendingFields(message = message).joinToString(separator = ",")}}" }
    }

    // 生成 Pending 消息字段，严格避免输出 Message.obj 的字符串内容。
    private fun pendingFields(message: PendingMessage): List<String> {
        return listOf(
            "\"index\":${message.index}",
            "\"whenUptimeMs\":${message.whenUptimeMs}",
            "\"delayMs\":${message.delayMs}",
            "\"blockedMs\":${message.blockedMs}",
            "\"what\":${intOrNull(value = message.what)}",
            "\"arg1\":${message.arg1}",
            "\"arg2\":${message.arg2}",
            "\"targetClass\":${stringOrNull(value = message.targetClass)}",
            "\"callbackClass\":${stringOrNull(value = message.callbackClass)}",
            "\"objClass\":${stringOrNull(value = message.objClass)}",
            "\"isAsynchronous\":${booleanOrNull(value = message.isAsynchronous)}",
            "\"isBarrierLike\":${message.isBarrierLike}",
            "\"isCriticalComponent\":${message.isCriticalComponent}",
        )
    }

    // 编码线程 CPU 记录列表，保持 TopN 顺序。
    private fun threadCpuRecords(records: List<ThreadCpuRecord>): String {
        return records.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { record: ThreadCpuRecord -> "{${threadCpuFields(record = record).joinToString(separator = ",")}}" }
    }

    // 生成线程 CPU 字段，只包含进程内线程基础元信息和 CPU 消耗。
    private fun threadCpuFields(record: ThreadCpuRecord): List<String> {
        return listOf(
            "\"tid\":${record.tid}",
            "\"threadName\":${string(record.threadName)}",
            "\"totalCpuMs\":${record.totalCpuMs}",
        )
    }

    // 编码可空内存快照，保持环境字段结构稳定。
    private fun memoryOrNull(snapshot: MemorySnapshot?): String {
        if (snapshot == null) {
            return "null"
        }
        val fields: List<String> = listOf(
            "\"availableBytes\":${snapshot.availableBytes}",
            "\"totalBytes\":${snapshot.totalBytes}",
            "\"isLowMemory\":${booleanOrNull(value = snapshot.isLowMemory)}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码可空进程 I/O 快照，权限受限时保持 JSON null。
    private fun processIoOrNull(snapshot: ProcessIoSnapshot?): String {
        if (snapshot == null) {
            return "null"
        }
        val fields: List<String> = listOf(
            "\"readBytes\":${snapshot.readBytes}",
            "\"writeBytes\":${snapshot.writeBytes}",
            "\"cancelledWriteBytes\":${snapshot.cancelledWriteBytes}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码环境证据可得性，供服务端和评审区分采集缺失。
    private fun environmentAvailability(availability: EnvironmentEvidenceAvailability): String {
        val fields: List<String> = listOf(
            "\"cpuLoadAvailable\":${availability.cpuLoadAvailable}",
            "\"memoryAvailable\":${availability.memoryAvailable}",
            "\"storageAvailable\":${availability.storageAvailable}",
            "\"processIoAvailable\":${availability.processIoAvailable}",
        )
        return "{${fields.joinToString(separator = ",")}}"
    }

    // 编码长整型列表，供 Checktime 最近延迟窗口使用。
    private fun longs(values: List<Long>): String {
        return values.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { value: Long -> value.toString() }
    }

    // 编码字符串列表，统一处理转义和数组边界。
    private fun strings(values: List<String>): String {
        return values.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { value: String -> string(value = value) }
    }

    // 编码非空字符串并补齐 JSON 引号。
    private fun string(value: String): String = "\"${JsonEscaper.escape(value = value)}\""

    // 编码可空字符串，保留缺失字段和空字符串之间的区别。
    private fun stringOrNull(value: String?): String = value?.let { nonNullValue: String ->
        string(value = nonNullValue)
    } ?: "null"

    // 编码可空整型，避免不同数字类型在字符串模板中产生歧义。
    private fun intOrNull(value: Int?): String = value?.toString() ?: "null"

    // 编码可空长整型，保留当前消息未结束这一语义。
    private fun longOrNull(value: Long?): String = value?.toString() ?: "null"

    // 编码可空浮点值，保留 load average 无法读取这一状态。
    private fun doubleOrNull(value: Double?): String = value?.toString() ?: "null"

    // 编码可空布尔值，反射失败的未知状态不能降级成 false。
    private fun booleanOrNull(value: Boolean?): String = value?.toString() ?: "null"
}
