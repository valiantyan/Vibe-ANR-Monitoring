package com.valiantyan.anrmonitor.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [AnrMonitor] 对运行时生命周期的接线，避免重复安装或卸载遗漏后台资源。
 */
class AnrMonitorRuntimeLifecycleTest {
    /**
     * 每个用例后重置单例，避免会话状态跨测试泄漏。
     */
    @After
    fun tearDown(): Unit {
        AnrMonitor.uninstall()
    }

    /**
     * 首次安装应启动 runtime，重复安装应复用同一个会话并避免启动第二个 runtime。
     */
    @Test
    fun installRuntimeStartsOnceAndReusesActiveSession(): Unit {
        val firstRuntime = FakeRuntimeHandle()
        val secondRuntime = FakeRuntimeHandle()
        val config = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
        )
        val firstSession: AnrMonitorSession = AnrMonitor.installRuntimeForTesting(
            config = config,
            runtimeHandle = firstRuntime,
        )
        val secondSession: AnrMonitorSession = AnrMonitor.installRuntimeForTesting(
            config = config,
            runtimeHandle = secondRuntime,
        )
        assertSame(firstSession, secondSession)
        assertTrue(firstSession.isRunning)
        assertEquals(1, firstRuntime.startCount)
        assertEquals(0, secondRuntime.startCount)
    }

    /**
     * 卸载应停止当前 runtime 并清空单例状态，后续安装可以创建新的运行时。
     */
    @Test
    fun uninstallStopsRuntimeAndAllowsNewSession(): Unit {
        val firstRuntime = FakeRuntimeHandle()
        val secondRuntime = FakeRuntimeHandle()
        val config = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
        )
        val firstSession: AnrMonitorSession = AnrMonitor.installRuntimeForTesting(
            config = config,
            runtimeHandle = firstRuntime,
        )
        AnrMonitor.uninstall()
        val secondSession: AnrMonitorSession = AnrMonitor.installRuntimeForTesting(
            config = config,
            runtimeHandle = secondRuntime,
        )
        assertFalse(firstSession.isRunning)
        assertTrue(secondSession.isRunning)
        assertEquals(1, firstRuntime.stopCount)
        assertEquals(1, secondRuntime.startCount)
    }

    /**
     * 测试用 runtime，只记录启动和停止次数以验证单例生命周期。
     */
    private class FakeRuntimeHandle : AnrMonitor.RuntimeHandle {
        // [start] 被调用的次数。
        var startCount: Int = 0
            private set

        // [stop] 被调用的次数。
        var stopCount: Int = 0
            private set

        /**
         * 记录启动次数，模拟真实 runtime 的幂等启动入口。
         */
        override fun start(): Unit {
            startCount += 1
        }

        /**
         * 记录停止次数，模拟真实 runtime 的资源释放入口。
         */
        override fun stop(): Unit {
            stopCount += 1
        }
    }
}
