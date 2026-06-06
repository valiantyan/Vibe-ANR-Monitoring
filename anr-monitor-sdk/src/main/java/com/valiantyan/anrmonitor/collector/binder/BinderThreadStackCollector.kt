package com.valiantyan.anrmonitor.collector.binder

/**
 * 当前进程 Binder 线程栈采集器，只采集本进程线程，不能代表远端进程完整现场。
 */
class BinderThreadStackCollector(
    private val stackReader: () -> Map<Thread, Array<StackTraceElement>> = { Thread.getAllStackTraces() },
) {
    /**
     * 采集 Binder 线程栈帧并展开为字符串列表，失败时返回空列表降级。
     *
     * @param maxThreadCount 最多采集的 Binder 线程数量。
     * @param maxFramesPerThread 每个线程最多保留的栈帧数量。
     * @return 当前进程 Binder 线程栈帧。
     */
    fun capture(
        maxThreadCount: Int,
        maxFramesPerThread: Int,
    ): List<String> {
        if (maxThreadCount <= 0 || maxFramesPerThread <= 0) {
            return emptyList()
        }
        return runCatching {
            stackReader()
                .filterKeys { thread: Thread -> isBinderThread(thread = thread) }
                .entries
                .take(n = maxThreadCount)
                .flatMap { entry: Map.Entry<Thread, Array<StackTraceElement>> ->
                    entry.value
                        .take(n = maxFramesPerThread)
                        .map { frame: StackTraceElement -> frame.toString() }
                }
        }.getOrElse {
            emptyList()
        }
    }

    // 只读取当前进程 Binder 线程，避免把普通业务线程误作为跨进程阻塞证据。
    private fun isBinderThread(thread: Thread): Boolean {
        return thread.name.contains(
            other = "binder",
            ignoreCase = true,
        )
    }
}
