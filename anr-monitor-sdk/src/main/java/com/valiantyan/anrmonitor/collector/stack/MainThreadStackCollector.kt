package com.valiantyan.anrmonitor.collector.stack

import android.os.Looper
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot

/**
 * 主线程 Java 栈采集器，用于疑似 ANR 时记录当前执行位置。
 */
class MainThreadStackCollector {
    /**
     * 采集主线程当前 Java 栈，并生成可用于去重的栈 ID。
     *
     * @return 主线程栈快照。
     */
    fun capture(): StackTraceSnapshot {
        val mainThread: Thread = Looper.getMainLooper().thread
        val frames: List<String> = mainThread.stackTrace.map { frame -> frame.toString() }
        return StackTraceSnapshot(
            stackId = "main-${frames.hashCode()}",
            threadName = mainThread.name,
            frames = frames,
        )
    }
}
