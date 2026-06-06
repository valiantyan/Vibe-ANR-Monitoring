package com.valiantyan.anrmonitor.collector.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Binder 和跨进程阻塞只能输出疑似证据，避免端侧把跨进程死锁强行确认为根因。
 */
class BinderBlockClassifierTest {
    /**
     * 主线程阻塞在 Binder transact 且 Binder 线程等待主线程时，应输出疑似阻塞证据。
     */
    @Test
    fun classifyReturnsSuspectedWhenMainStackContainsBinderProxyAndBinderThreadWaitsMain(): Unit {
        val classifier = BinderBlockClassifier()

        val result = classifier.classify(
            mainFrames = listOf(
                "android.os.BinderProxy.transactNative(Native Method)",
                "com.example.Repository.loadSync(Repository.kt:42)",
            ),
            binderThreadFrames = listOf(
                "java.lang.Object.wait(Native Method)",
                "com.example.Service.waitMainThread(Service.kt:10)",
            ),
        )

        assertTrue(result.available)
        assertTrue(result.suspected)
        assertTrue(result.mainThreadInBinder)
        assertTrue(result.binderThreadWaitsMain)
        assertEquals("android.os.BinderProxy.transactNative(Native Method)", result.mainThreadEvidence.first())
        assertEquals("java.lang.Object.wait(Native Method)", result.binderThreadEvidence.first())
    }

    /**
     * 只有主线程进入 Binder transact 时，仍不能判定跨进程阻塞疑似。
     */
    @Test
    fun classifyDoesNotSuspectWhenOnlyMainThreadIsInBinder(): Unit {
        val classifier = BinderBlockClassifier()

        val result = classifier.classify(
            mainFrames = listOf("android.os.BinderProxy.transactNative(Native Method)"),
            binderThreadFrames = listOf("com.example.Service.handle(Service.kt:20)"),
        )

        assertTrue(result.available)
        assertFalse(result.suspected)
        assertTrue(result.mainThreadInBinder)
        assertFalse(result.binderThreadWaitsMain)
    }

    /**
     * 保留布尔兼容入口，便于计划中的最小用例和轻量调用方使用。
     */
    @Test
    fun isBinderBlockSuspectedKeepsBooleanCompatibility(): Unit {
        val classifier = BinderBlockClassifier()

        val result = classifier.isBinderBlockSuspected(
            mainFrames = listOf("android.os.BinderProxy.transactNative(Native Method)"),
            binderThreadFrames = listOf("com.example.Service.waitMainThread(Service.kt:10)"),
        )

        assertTrue(result)
    }
}
