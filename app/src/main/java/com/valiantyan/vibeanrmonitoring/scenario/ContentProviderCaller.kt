package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * 可注入 Provider 查询器，测试中记录 authority/path，真实 Demo 中发起 [android.content.ContentResolver] 查询。
 */
fun interface ContentProviderCaller {
    /**
     * 查询指定 Provider 路径。
     *
     * @param authority Provider authority。
     * @param path Provider 路径。
     */
    fun query(authority: String, path: String): Unit
}

/**
 * 使用应用上下文查询应用内 ContentProvider，避免持有 Activity。
 *
 * @param context 用于获取应用上下文和 ContentResolver。
 */
class ContextContentProviderCaller(
    context: Context,
) : ContentProviderCaller {
    // 使用 Application Context 避免 Demo 场景被 Activity 生命周期影响。
    private val appContext: Context = context.applicationContext

    /**
     * 通过 ContentResolver 查询 Demo Provider。阻塞发生在 Provider.query() 内部。
     *
     * @param authority Provider authority。
     * @param path Provider 路径。
     */
    override fun query(authority: String, path: String): Unit {
        val uri: Uri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(path)
            .build()
        val cursor: Cursor? = appContext.contentResolver.query(
            uri,
            null,
            null,
            null,
            null,
        )
        cursor?.close()
    }
}
