package com.valiantyan.anrmonitor.collector.binder

import com.valiantyan.anrmonitor.domain.model.BinderBlockSnapshot

/**
 * Binder/跨进程阻塞疑似分类器，只输出疑似证据，不确认跨进程死锁。
 */
class BinderBlockClassifier {
    /**
     * 根据主线程栈和进程内 Binder 线程栈判断是否疑似 Binder 阻塞。
     *
     * @param mainFrames 主线程栈帧。
     * @param binderThreadFrames 当前进程 Binder 线程栈帧。
     * @return 疑似阻塞证据快照。
     */
    fun classify(
        mainFrames: List<String>,
        binderThreadFrames: List<String>,
    ): BinderBlockSnapshot {
        val mainEvidence: List<String> = mainFrames.filter { frame: String ->
            isMainThreadBinderFrame(frame = frame)
        }
        val binderEvidence: List<String> = binderThreadFrames.filter { frame: String ->
            isBinderThreadWaitFrame(frame = frame)
        }
        return BinderBlockSnapshot(
            available = true,
            suspected = mainEvidence.isNotEmpty(),
            mainThreadInBinder = mainEvidence.isNotEmpty(),
            binderThreadWaitsMain = binderEvidence.isNotEmpty(),
            mainThreadEvidence = mainEvidence,
            binderThreadEvidence = binderEvidence,
            failureReason = null,
        )
    }

    /**
     * 保留布尔兼容入口，供轻量调用方快速判断是否疑似。
     *
     * @param mainFrames 主线程栈帧。
     * @param binderThreadFrames 当前进程 Binder 线程栈帧。
     * @return 是否疑似 Binder/跨进程阻塞。
     */
    fun isBinderBlockSuspected(
        mainFrames: List<String>,
        binderThreadFrames: List<String>,
    ): Boolean {
        return classify(
            mainFrames = mainFrames,
            binderThreadFrames = binderThreadFrames,
        ).suspected
    }

    // 主线程命中 Binder transact 即可输出跨进程阻塞疑似，Binder 线程等待只作为增强证据。
    private fun isMainThreadBinderFrame(frame: String): Boolean {
        return MAIN_BINDER_PATTERNS.any { pattern: String -> frame.contains(other = pattern) }
    }

    // Binder 线程出现 wait/park/lock 或显式等待 main，才作为跨进程阻塞疑似辅助证据。
    private fun isBinderThreadWaitFrame(frame: String): Boolean {
        return BINDER_WAIT_PATTERNS.any { pattern: String ->
            frame.contains(
                other = pattern,
                ignoreCase = true,
            )
        }
    }

    private companion object {
        /**
         * 主线程 Binder 调用常见栈帧。
         */
        private val MAIN_BINDER_PATTERNS: List<String> = listOf(
            "android.os.BinderProxy.transactNative",
            "android.os.BinderProxy.transact",
            "android.os.Binder.transact",
        )

        /**
         * Binder 线程等待主线程或锁的常见信号。
         */
        private val BINDER_WAIT_PATTERNS: List<String> = listOf(
            "wait",
            "park",
            "lock",
            "monitor",
            "mainthread",
            "main thread",
            "CountDownLatch.await",
        )
    }
}
