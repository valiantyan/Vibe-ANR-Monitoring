package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Service 超时场景的触发入口和 Service 内部阻塞动作。
 */
class ServiceTimeoutScenarioTest {
    @Test
    fun runStartsServiceWithConfiguredAction(): Unit {
        val starter: RecordingServiceStarter = RecordingServiceStarter()
        val scenario: ServiceTimeoutScenario = ServiceTimeoutScenario(
            serviceStarter = starter,
        )
        scenario.run()
        assertEquals(
            listOf(ServiceTimeoutScenario.ACTION_SERVICE_TIMEOUT),
            starter.actions,
        )
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val starter: RecordingServiceStarter = RecordingServiceStarter()
        val scenario: ServiceTimeoutScenario = ServiceTimeoutScenario(
            serviceStarter = starter,
        )
        assertEquals("service_timeout", scenario.id)
        assertEquals("Service 超时", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + SERVICE component evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ServiceTimeoutService.onStartCommand"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.targetClass 包含 ActivityThread\$H"))
        assertTrue(scenario.expectedJsonSignals.contains("systemAnr.anrType = SERVICE"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun blockerBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val blocker: ServiceTimeoutBlocker = ServiceTimeoutBlocker(
            blockingAction = blockingAction,
            durationMs = 25_123L,
        )
        blocker.block()
        assertEquals(listOf(25_123L), blockingAction.blockedDurations)
    }

    private class RecordingServiceStarter : ServiceStarter {
        val actions: MutableList<String> = mutableListOf()

        override fun start(action: String): Unit {
            actions.add(action)
        }
    }

    private class RecordingBlockingAction : BlockingAction {
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun block(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }
    }
}
