package com.valiantyan.anrmonitor.collector.sharedprefs

import android.content.Context
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesFileStat
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationRecord
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesSnapshot
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Node
import org.xml.sax.SAXException

/**
 * 扫描 SharedPreferences 文件健康度，并合并包装入口记录到报告快照。
 *
 * @param fileReader 文件健康信息读取器。
 * @param operationReader 最近 SP 操作读取器。
 * @param pendingFinisherReader pending finisher 数量读取器。
 * @param bypassPolicyProvider QueuedWork 绕过策略读取器。
 */
class SharedPreferencesHealthScanner(
    private val fileReader: () -> List<SharedPreferencesFileStat>,
    private val operationReader: () -> List<SharedPreferencesOperationRecord> = { emptyList() },
    private val pendingFinisherReader: () -> Int? = { 0 },
    private val bypassPolicyProvider: () -> QueuedWorkBypassPolicy = { QueuedWorkBypassPolicy.disabled() },
) {
    /**
     * 扫描完整 SP 专项快照。
     *
     * @param maxFileCount 最多返回的高风险文件数量。
     * @param maxOperationCount 最多返回的最近操作数量。
     * @return 可进入 [com.valiantyan.anrmonitor.domain.model.AnrSnapshot] 的 SP 快照。
     */
    fun scan(
        maxFileCount: Int,
        maxOperationCount: Int,
    ): SharedPreferencesSnapshot {
        return try {
            val operations: List<SharedPreferencesOperationRecord> = operationReader()
            SharedPreferencesSnapshot(
                available = true,
                topFiles = enrichedTopFiles(
                    files = fileReader(),
                    operations = operations,
                    maxFileCount = maxFileCount,
                ),
                recentOperations = operations.takeLast(n = maxOperationCount),
                pendingFinisherCount = pendingFinisherReader(),
                queuedWorkBypass = bypassPolicyProvider().toState(),
                failureReason = null,
            )
        } catch (error: SecurityException) {
            SharedPreferencesSnapshot.unavailable(reason = "sharedPreferences scan denied: ${error.message}")
        } catch (error: RuntimeException) {
            SharedPreferencesSnapshot.unavailable(reason = "sharedPreferences scan failed: ${error.message}")
        }
    }

    /**
     * 按文件大小和 key 数返回高风险文件列表，兼容计划中的最小 API。
     *
     * @param maxCount 最多返回数量。
     * @return 高风险 SP 文件列表。
     */
    fun scanTopFiles(maxCount: Int): List<SharedPreferencesFileStat> {
        return scan(
            maxFileCount = maxCount,
            maxOperationCount = 0,
        ).topFiles
    }

    // 将运行期 load/apply/commit 记录聚合到文件健康度，补齐第 5 篇专项证据。
    private fun enrichedTopFiles(
        files: List<SharedPreferencesFileStat>,
        operations: List<SharedPreferencesOperationRecord>,
        maxFileCount: Int,
    ): List<SharedPreferencesFileStat> {
        val operationsByFile: Map<String, List<SharedPreferencesOperationRecord>> = operations.groupBy { record ->
            record.fileName
        }
        return files
            .map { stat: SharedPreferencesFileStat ->
                enrichStat(
                    stat = stat,
                    operations = operationsByFile[stat.fileName].orEmpty(),
                )
            }
            .sortedWith(
                compareByDescending<SharedPreferencesFileStat> { stat -> stat.sizeBytes }
                    .thenByDescending { stat -> stat.keyCount },
            )
            .take(n = maxFileCount)
    }

    // 计算单个 SP 文件的首次加载耗时、写入次数和最近写入成本。
    private fun enrichStat(
        stat: SharedPreferencesFileStat,
        operations: List<SharedPreferencesOperationRecord>,
    ): SharedPreferencesFileStat {
        val writeOperations: List<SharedPreferencesOperationRecord> = operations.filter { record ->
            record.operationType == SharedPreferencesOperationType.APPLY ||
                record.operationType == SharedPreferencesOperationType.COMMIT
        }
        return stat.copy(
            firstLoadCostMs = operations.firstOrNull { record ->
                record.operationType == SharedPreferencesOperationType.LOAD
            }?.costMs,
            applyCount = operations.count { record -> record.operationType == SharedPreferencesOperationType.APPLY },
            commitCount = operations.count { record -> record.operationType == SharedPreferencesOperationType.COMMIT },
            lastWriteCostMs = writeOperations.lastOrNull()?.costMs,
        )
    }

    /**
     * SharedPreferences 文件扫描器辅助构造器。
     */
    companion object {
        /**
         * 基于 Android [Context] 创建真实 shared_prefs 目录扫描器。
         *
         * @param context 宿主上下文。
         * @param operationRecorder SP 包装入口记录器。
         * @param bypassPolicyProvider QueuedWork 绕过策略读取器。
         * @return 真实目录扫描器。
         */
        fun create(
            context: Context,
            operationRecorder: SharedPreferencesOperationRecorder = SharedPreferencesOperationRecorder.global,
            bypassPolicyProvider: () -> QueuedWorkBypassPolicy = { QueuedWorkBypassPolicy.disabled() },
        ): SharedPreferencesHealthScanner {
            return SharedPreferencesHealthScanner(
                fileReader = { readSharedPreferencesDirectory(context = context.applicationContext) },
                operationReader = { operationRecorder.recentOperations(maxCount = DEFAULT_OPERATION_READ_COUNT) },
                pendingFinisherReader = operationRecorder::pendingFinisherCount,
                bypassPolicyProvider = bypassPolicyProvider,
            )
        }

        // 读取应用 shared_prefs 目录下的 XML 文件信息。
        private fun readSharedPreferencesDirectory(context: Context): List<SharedPreferencesFileStat> {
            val directory = File(
                context.applicationInfo.dataDir,
                SHARED_PREFS_DIRECTORY,
            )
            if (!directory.isDirectory) {
                return emptyList()
            }
            return directory.listFiles { file: File ->
                file.isFile && file.extension == XML_EXTENSION
            }?.map { file: File ->
                SharedPreferencesFileStat(
                    fileName = file.name,
                    sizeBytes = file.length(),
                    keyCount = countXmlKeys(file = file),
                )
            }.orEmpty()
        }

        // 使用 XML DOM 统计 key 节点数量，避免按文本行数猜测。
        private fun countXmlKeys(file: File): Int {
            return try {
                val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
                val document = factory.newDocumentBuilder().parse(file)
                val children = document.documentElement.childNodes
                (0 until children.length).count { index: Int ->
                    children.item(index).nodeType == Node.ELEMENT_NODE
                }
            } catch (error: ParserConfigurationException) {
                0
            } catch (error: SAXException) {
                0
            } catch (error: IOException) {
                0
            }
        }

        /**
         * Android SharedPreferences 文件目录名。
         */
        private const val SHARED_PREFS_DIRECTORY: String = "shared_prefs"

        /**
         * SharedPreferences 文件扩展名。
         */
        private const val XML_EXTENSION: String = "xml"

        /**
         * scanner 内部读取操作记录时的安全上限。
         */
        private const val DEFAULT_OPERATION_READ_COUNT: Int = 64
    }
}
