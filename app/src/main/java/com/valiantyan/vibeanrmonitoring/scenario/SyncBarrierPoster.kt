package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Looper
import android.os.MessageQueue
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 可注入 Sync Barrier 插入器，测试中返回固定 token，Demo 中反射调用隐藏 API。
 */
fun interface SyncBarrierPoster {
    /**
     * 插入 Sync Barrier。
     *
     * @return 插入成功时返回系统 token，失败时返回 null。
     */
    fun post(): Int?
}

/**
 * Android Debug Demo 使用的真实 Sync Barrier 插入器。
 */
class ReflectionSyncBarrierPoster : SyncBarrierPoster {
    /**
     * 反射调用隐藏 API，失败时返回 null 让场景层统一提示。
     */
    override fun post(): Int? {
        return try {
            Log.d(TAG, "开始反射调用 postSyncBarrier")
            val queue: MessageQueue = Looper.getMainLooper().queue
            val method: Method = MessageQueue::class.java.getDeclaredMethod("postSyncBarrier")
            method.isAccessible = true
            method.invoke(queue) as Int
        } catch (error: NoSuchMethodException) {
            Log.e(TAG, "未找到 postSyncBarrier 方法: ${error.message}", error)
            null
        } catch (error: IllegalAccessException) {
            Log.e(TAG, "postSyncBarrier 访问失败: ${error.message}", error)
            null
        } catch (error: InvocationTargetException) {
            Log.e(TAG, "postSyncBarrier 调用失败: ${error.message}", error)
            null
        } catch (error: SecurityException) {
            Log.e(TAG, "postSyncBarrier 被系统拒绝: ${error.message}", error)
            null
        } catch (error: ClassCastException) {
            Log.e(TAG, "postSyncBarrier token 类型异常: ${error.message}", error)
            null
        }
    }

    private companion object {
        /**
         * Sync Barrier 场景日志标签。
         */
        private const val TAG: String = "SyncBarrierLeak"
    }
}
