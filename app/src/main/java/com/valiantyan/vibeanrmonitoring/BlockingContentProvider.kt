package com.valiantyan.vibeanrmonitoring

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlocker

/**
 * Demo 专用阻塞 Provider，用于复现 Provider 查询长时间不返回导致的主线程 ANR。
 */
class BlockingContentProvider : ContentProvider() {
    // Provider 内部阻塞器，单独保留在 scenario 包中方便测试。
    private val blocker: ContentProviderBlocker = ContentProviderBlocker()

    /**
     * Provider 初始化不做阻塞，避免 App 启动阶段被污染；真正阻塞只发生在 query()。
     */
    override fun onCreate(): Boolean = true

    /**
     * 收到 Demo 专用路径后阻塞，再返回一行最小结果。
     *
     * @param uri 查询 URI。
     * @param projection 查询列。
     * @param selection 查询条件。
     * @param selectionArgs 查询参数。
     * @param sortOrder 排序条件。
     * @return 最小 Cursor，证明查询链路完成。
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.lastPathSegment == ContentProviderBlockScenario.BLOCKING_PATH) {
            blocker.block()
        }
        return MatrixCursor(arrayOf("status")).apply {
            addRow(arrayOf("blocked"))
        }
    }

    /**
     * 返回 Demo Provider 的固定 MIME 类型，便于系统完成 Provider 契约。
     *
     * @param uri 查询 URI。
     * @return Demo Provider 的 MIME 类型。
     */
    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.vibeanr.blocking-provider"

    /**
     * Demo 不支持插入数据。
     *
     * @param uri 插入 URI。
     * @param values 插入内容。
     * @return 始终返回 null。
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    /**
     * Demo 不支持删除数据。
     *
     * @param uri 删除 URI。
     * @param selection 删除条件。
     * @param selectionArgs 删除参数。
     * @return 始终返回 0。
     */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /**
     * Demo 不支持更新数据。
     *
     * @param uri 更新 URI。
     * @param values 更新内容。
     * @param selection 更新条件。
     * @param selectionArgs 更新参数。
     * @return 始终返回 0。
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
