# Demo Sync Barrier 泄漏 nativePollOnce 场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“Sync Barrier 泄漏 / nativePollOnce”做成第四个可测试、可复现、可通过 SDK JSON 定位“主线程看似停在 nativePollOnce，根因是 Sync Barrier 残留挡住同步消息”的正式 ANR 场景。

**Architecture:** 当前 Demo 已经有 `SyncBarrierLeakScenario` 雏形，可以通过反射插入 Sync Barrier 并记录 token，但它还没有和其他 Demo 场景一样放在 `scenario` 包、实现 `AnrDemoScenario` 元数据，也缺少 JVM 单元测试。本计划把 Barrier 插入、token 记录、同步消息投递、Service 启动和失败提示拆成可注入接口，测试中使用记录器验证证据链，真实 Demo 运行时继续使用 Android `MessageQueue.postSyncBarrier()`、`AnrBarrierDebug.recordPostSyncBarrier()`、`Handler.post()` 和测试 Service 生成真实 nativePollOnce/Barrier 报告。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`MessageQueue.postSyncBarrier()` 反射、`AnrBarrierDebug`、`Handler(Looper.getMainLooper())`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 4 的“Sync Barrier 泄漏 / nativePollOnce”场景闭环。

本计划不实现主线程锁等待、Broadcast、Service 超时、Provider、Binder、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。

本场景和前三个已完成场景的区别：

- 输入事件当前慢消息：主因是当前点击消息自己阻塞，关键证据是 `mainThread.current.wallMs` 和 `CurrentSlowInputScenario.run`。
- 主线程 CPU 忙等：主因是当前点击消息持续消耗 CPU，关键证据是 `mainThread.current.cpuMs`、`threadCpu.topThreads` 和 `MainThreadCpuBusyScenario.run`。
- 消息风暴：主因是 Pending 队列里有大量同类消息，关键证据是 `attribution.primary=MESSAGE_STORM` 和 `pending repeated target count=...`。
- Sync Barrier 泄漏 / nativePollOnce：主因不是业务回调直接耗时，也不是大量同类消息，而是队头同步屏障 `target=null` 长时间残留，导致同步消息无法出队；系统 Trace 常表现为主线程在 `android.os.MessageQueue.nativePollOnce`。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenarioTest.kt`
  - JVM 单元测试，验证场景元数据、Barrier token 记录、同步消息投递、Service 启动、失败时不伪造证据。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierPoster.kt`
  - 可注入 Barrier 插入器，测试中返回固定 token，真实 Demo 中反射调用 `MessageQueue.postSyncBarrier()`。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierDebugRecorder.kt`
  - 可注入 Barrier token 记录器，测试中记录 token 和栈，真实 Demo 中调用 `AnrBarrierDebug.recordPostSyncBarrier()`。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierBlockedMessagePoster.kt`
  - 可注入同步消息投递器，测试中记录数量，真实 Demo 中使用主线程 `Handler.post()` 投递会被 Barrier 挡住的同步消息。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierLeakComponentStarter.kt`
  - 可注入组件消息触发器，测试中记录调用，真实 Demo 中启动 `BarrierLeakService`。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ScenarioFailureNotifier.kt`
  - 可注入失败提示器，测试中记录异常类型，真实 Demo 中使用 Log 和 Toast 提醒反射失败。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenario.kt`
  - 第四个正式 ANR 场景：插入故意不移除的 Sync Barrier，记录 token，投递同步消息和组件消息，形成 `SYNC_BARRIER_STUCK` JSON 证据链。
- Delete: `app/src/main/java/com/valiantyan/vibeanrmonitoring/SyncBarrierLeakScenario.kt`
  - 删除根包旧实现，避免 Activity 继续引用不可测试版本。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 将导入切换到 `com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenario`，按钮继续调用 `syncBarrierLeakScenario.run()`。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 把现有 Barrier 验证说明扩展成新人可操作步骤：点击按钮、继续点屏幕、拉取 JSON、按字段判断、排除误判、写修复建议。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 增加第四批次“Sync Barrier 泄漏 / nativePollOnce”的触发步骤、JSON 读取口径、排除项和验收记录占位。

## Implementation Tasks

### Task 1: 用 TDD 实现 Sync Barrier 泄漏场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierPoster.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierDebugRecorder.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierBlockedMessagePoster.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierLeakComponentStarter.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ScenarioFailureNotifier.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Sync Barrier 泄漏场景会形成 token、队头 Barrier、nativePollOnce 相关的可读证据链。
 */
class SyncBarrierLeakScenarioTest {
    @Test
    fun runRecordsBarrierTokenAndPostsBlockedWork(): Unit {
        val poster: RecordingSyncBarrierPoster = RecordingSyncBarrierPoster(token = 42)
        val recorder: RecordingBarrierDebugRecorder = RecordingBarrierDebugRecorder()
        val blockedMessagePoster: RecordingBarrierBlockedMessagePoster = RecordingBarrierBlockedMessagePoster()
        val componentStarter: RecordingBarrierLeakComponentStarter = RecordingBarrierLeakComponentStarter()
        val failureNotifier: RecordingScenarioFailureNotifier = RecordingScenarioFailureNotifier()
        val scenario: SyncBarrierLeakScenario = SyncBarrierLeakScenario(
            barrierPoster = poster,
            debugRecorder = recorder,
            blockedMessagePoster = blockedMessagePoster,
            componentStarter = componentStarter,
            failureNotifier = failureNotifier,
            blockedMessageCount = 6,
        )

        scenario.run()

        assertEquals(listOf(42), poster.postCalls)
        assertEquals(listOf(42), recorder.tokens)
        assertTrue(recorder.stackFrames.single().any { frame: String ->
            frame.contains("SyncBarrierLeakScenario.run")
        })
        assertEquals(6, blockedMessagePoster.callbacks.size)
        assertEquals(1, componentStarter.startCount)
        assertTrue(failureNotifier.errors.isEmpty())
    }

    @Test
    fun runDoesNotRecordEvidenceWhenPostingBarrierFails(): Unit {
        val poster: RecordingSyncBarrierPoster = RecordingSyncBarrierPoster(token = null)
        val recorder: RecordingBarrierDebugRecorder = RecordingBarrierDebugRecorder()
        val blockedMessagePoster: RecordingBarrierBlockedMessagePoster = RecordingBarrierBlockedMessagePoster()
        val componentStarter: RecordingBarrierLeakComponentStarter = RecordingBarrierLeakComponentStarter()
        val failureNotifier: RecordingScenarioFailureNotifier = RecordingScenarioFailureNotifier()
        val scenario: SyncBarrierLeakScenario = SyncBarrierLeakScenario(
            barrierPoster = poster,
            debugRecorder = recorder,
            blockedMessagePoster = blockedMessagePoster,
            componentStarter = componentStarter,
            failureNotifier = failureNotifier,
        )

        scenario.run()

        assertTrue(recorder.tokens.isEmpty())
        assertTrue(blockedMessagePoster.callbacks.isEmpty())
        assertEquals(0, componentStarter.startCount)
        assertEquals(listOf("postSyncBarrier failed"), failureNotifier.errors)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val scenario: SyncBarrierLeakScenario = SyncBarrierLeakScenario(
            barrierPoster = RecordingSyncBarrierPoster(token = 7),
            debugRecorder = RecordingBarrierDebugRecorder(),
            blockedMessagePoster = RecordingBarrierBlockedMessagePoster(),
            componentStarter = RecordingBarrierLeakComponentStarter(),
            failureNotifier = RecordingScenarioFailureNotifier(),
        )

        assertEquals("sync_barrier_native_poll_once", scenario.id)
        assertEquals("Sync Barrier 泄漏 / nativePollOnce", scenario.title)
        assertEquals("SYNC_BARRIER_STUCK", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("attribution.primary = SYNC_BARRIER_STUCK"))
        assertTrue(scenario.expectedJsonSignals.contains("pendingQueue.messages[0].isBarrierLike = true"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.alignedWithPendingBarrier = true"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.nativePollOnceRecords 包含 STACK_INFERENCE 或 HOOK"))
    }

    private class RecordingSyncBarrierPoster(
        private val token: Int?,
    ) : SyncBarrierPoster {
        val postCalls: MutableList<Int> = mutableListOf()

        override fun post(): Int? {
            token?.let { value: Int -> postCalls.add(value) }
            return token
        }
    }

    private class RecordingBarrierDebugRecorder : BarrierDebugRecorder {
        val tokens: MutableList<Int> = mutableListOf()
        val stackFrames: MutableList<List<String>> = mutableListOf()

        override fun recordPostSyncBarrier(
            token: Int,
            stackFrames: List<String>,
        ): Unit {
            tokens.add(token)
            this.stackFrames.add(stackFrames)
        }
    }

    private class RecordingBarrierBlockedMessagePoster : BarrierBlockedMessagePoster {
        val callbacks: MutableList<Runnable> = mutableListOf()

        override fun post(callback: Runnable): Unit {
            callbacks.add(callback)
        }
    }

    private class RecordingBarrierLeakComponentStarter : BarrierLeakComponentStarter {
        var startCount: Int = 0

        override fun start(): Unit {
            startCount += 1
        }
    }

    private class RecordingScenarioFailureNotifier : ScenarioFailureNotifier {
        val errors: MutableList<String> = mutableListOf()

        override fun notifyFailure(message: String, error: Throwable?): Unit {
            errors.add(message)
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest
```

Expected: FAIL with unresolved references for `SyncBarrierPoster`, `BarrierDebugRecorder`, `BarrierBlockedMessagePoster`, `BarrierLeakComponentStarter`, `ScenarioFailureNotifier`, and `SyncBarrierLeakScenario`.

- [x] **Step 3: Create SyncBarrierPoster**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierPoster.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Looper
import android.os.MessageQueue
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
    override fun post(): Int? {
        return try {
            val queue: MessageQueue = Looper.getMainLooper().queue
            val method: Method = MessageQueue::class.java.getDeclaredMethod("postSyncBarrier")
            method.isAccessible = true
            method.invoke(queue) as Int
        } catch (error: NoSuchMethodException) {
            null
        } catch (error: IllegalAccessException) {
            null
        } catch (error: InvocationTargetException) {
            null
        } catch (error: SecurityException) {
            null
        } catch (error: ClassCastException) {
            null
        }
    }
}
```

- [x] **Step 4: Create BarrierDebugRecorder**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierDebugRecorder.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import com.valiantyan.anrmonitor.api.AnrBarrierDebug

/**
 * 可注入 Barrier token 记录器，真实 Demo 中把 token 写入 SDK Barrier 增强证据。
 */
fun interface BarrierDebugRecorder {
    /**
     * 记录一次 Sync Barrier 插入。
     *
     * @param token 系统返回的 Barrier token。
     * @param stackFrames 插入 Barrier 时的调用栈。
     */
    fun recordPostSyncBarrier(
        token: Int,
        stackFrames: List<String>,
    ): Unit
}

/**
 * 调用 SDK Debug API 的真实记录器。
 */
object SdkBarrierDebugRecorder : BarrierDebugRecorder {
    override fun recordPostSyncBarrier(
        token: Int,
        stackFrames: List<String>,
    ): Unit {
        AnrBarrierDebug.recordPostSyncBarrier(
            token = token,
            stackFrames = stackFrames,
        )
    }
}
```

- [x] **Step 5: Create BarrierBlockedMessagePoster**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierBlockedMessagePoster.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Handler
import android.os.Looper

/**
 * 可注入同步消息投递器，Barrier 后方的这些消息应在 JSON Pending 队列中保持 blocked 状态。
 */
fun interface BarrierBlockedMessagePoster {
    /**
     * 投递一个同步消息 callback。
     *
     * @param callback 会被 Sync Barrier 挡住的同步任务。
     */
    fun post(callback: Runnable): Unit
}

/**
 * 使用主线程 Handler 投递同步消息的真实实现。
 */
class HandlerBarrierBlockedMessagePoster : BarrierBlockedMessagePoster {
    private val handler: Handler = Handler(Looper.getMainLooper())

    override fun post(callback: Runnable): Unit {
        handler.post(callback)
    }
}
```

- [x] **Step 6: Create BarrierLeakComponentStarter**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierLeakComponentStarter.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.content.Intent
import android.util.Log
import com.valiantyan.vibeanrmonitoring.BarrierLeakService

/**
 * 可注入组件消息触发器，用于让 ActivityThread 组件消息也排入 Barrier 后方。
 */
fun interface BarrierLeakComponentStarter {
    /**
     * 触发一个会进入主线程队列的组件动作。
     */
    fun start(): Unit
}

/**
 * 启动 Demo 测试 Service 的真实实现。
 */
class ServiceBarrierLeakComponentStarter(
    private val context: Context,
) : BarrierLeakComponentStarter {
    override fun start(): Unit {
        val intent: Intent = Intent(
            context,
            BarrierLeakService::class.java,
        )
        try {
            context.startService(intent)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Barrier 泄漏测试 Service 启动失败: ${error.message}", error)
        } catch (error: SecurityException) {
            Log.e(TAG, "Barrier 泄漏测试 Service 启动被系统拒绝: ${error.message}", error)
        }
    }

    private companion object {
        private const val TAG: String = "SyncBarrierLeak"
    }
}
```

- [x] **Step 7: Create ScenarioFailureNotifier**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ScenarioFailureNotifier.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * 场景失败提示器，避免反射失败时用户误以为已经制造了 ANR。
 */
fun interface ScenarioFailureNotifier {
    /**
     * 提示场景触发失败。
     *
     * @param message 稳定的失败原因。
     * @param error 具体异常，可能为空。
     */
    fun notifyFailure(
        message: String,
        error: Throwable?,
    ): Unit
}

/**
 * Android Demo 使用的真实失败提示器。
 */
class ToastScenarioFailureNotifier(
    private val context: Context,
) : ScenarioFailureNotifier {
    override fun notifyFailure(
        message: String,
        error: Throwable?,
    ): Unit {
        Log.e(TAG, "Sync Barrier 泄漏场景触发失败: $message", error)
        val detail: String = error?.javaClass?.simpleName ?: message
        Toast.makeText(
            context,
            "Sync Barrier 触发失败：$detail",
            Toast.LENGTH_LONG,
        ).show()
    }

    private companion object {
        private const val TAG: String = "SyncBarrierLeak"
    }
}
```

- [x] **Step 8: Create SyncBarrierLeakScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.os.MessageQueue
import android.util.Log

/**
 * Sync Barrier 泄漏场景，用于复现主线程停在 [MessageQueue.nativePollOnce] 的真实 ANR。
 *
 * @param barrierPoster Sync Barrier 插入器。
 * @param debugRecorder Barrier token 记录器。
 * @param blockedMessagePoster Barrier 后方同步消息投递器。
 * @param componentStarter 组件消息触发器。
 * @param failureNotifier 失败提示器。
 * @param blockedMessageCount Barrier 后方同步消息数量。
 */
class SyncBarrierLeakScenario(
    private val barrierPoster: SyncBarrierPoster,
    private val debugRecorder: BarrierDebugRecorder,
    private val blockedMessagePoster: BarrierBlockedMessagePoster,
    private val componentStarter: BarrierLeakComponentStarter,
    private val failureNotifier: ScenarioFailureNotifier,
    private val blockedMessageCount: Int = DEFAULT_BLOCKED_MESSAGE_COUNT,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        failureNotifier = ToastScenarioFailureNotifier(context = context),
        barrierPoster = ReflectionSyncBarrierPoster(),
        debugRecorder = SdkBarrierDebugRecorder,
        blockedMessagePoster = HandlerBarrierBlockedMessagePoster(),
        componentStarter = ServiceBarrierLeakComponentStarter(context = context),
    )

    override val id: String = "sync_barrier_native_poll_once"
    override val title: String = "Sync Barrier 泄漏 / nativePollOnce"
    override val expectedAttribution: String = "SYNC_BARRIER_STUCK"
    override val expectedJsonSignals: List<String> = listOf(
        "attribution.primary = SYNC_BARRIER_STUCK",
        "pendingQueue.messages[0].isBarrierLike = true",
        "pendingQueue.messages[0].targetClass = null",
        "barrierEvidence.stuckTokens[].token = pendingQueue.messages[0].arg1",
        "barrierEvidence.alignedWithPendingBarrier = true",
        "barrierEvidence.nativePollOnceRecords 包含 STACK_INFERENCE 或 HOOK",
    )

    /**
     * 插入一个故意不移除的 Sync Barrier，并投递会被挡住的同步消息和组件消息。
     */
    override fun run(): Unit {
        val token: Int = barrierPoster.post() ?: run {
            failureNotifier.notifyFailure(message = "postSyncBarrier failed", error = null)
            return
        }
        debugRecorder.recordPostSyncBarrier(
            token = token,
            stackFrames = captureStackFrames(),
        )
        postBlockedSynchronousMessages()
        componentStarter.start()
        Log.w(TAG, "Sync Barrier 泄漏场景已触发，token=$token")
    }

    private fun postBlockedSynchronousMessages(): Unit {
        repeat(times = blockedMessageCount) { index: Int ->
            blockedMessagePoster.post(
                callback = BlockedSynchronousMessage(index = index),
            )
        }
    }

    private fun captureStackFrames(): List<String> {
        return Thread.currentThread().stackTrace
            .drop(n = STACK_FRAMES_TO_DROP)
            .take(n = MAX_STACK_FRAMES)
            .map { element: StackTraceElement -> element.toString() }
    }

    private class BlockedSynchronousMessage(
        private val index: Int,
    ) : Runnable {
        override fun run(): Unit {
            Log.w(TAG, "Sync Barrier 泄漏验证失败，同步消息被执行: index=$index")
        }
    }

    private companion object {
        private const val TAG: String = "SyncBarrierLeak"
        private const val DEFAULT_BLOCKED_MESSAGE_COUNT: Int = 8
        private const val STACK_FRAMES_TO_DROP: Int = 3
        private const val MAX_STACK_FRAMES: Int = 16
    }
}
```

- [x] **Step 9: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest
```

Expected: PASS.

- [x] **Step 10: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierPoster.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierDebugRecorder.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierBlockedMessagePoster.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BarrierLeakComponentStarter.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ScenarioFailureNotifier.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenario.kt
git commit -m "实现 Sync Barrier 泄漏场景类"
```

### Task 2: 将 Sync Barrier 泄漏按钮接入场景包实现

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
- Delete: `app/src/main/java/com/valiantyan/vibeanrmonitoring/SyncBarrierLeakScenario.kt`

- [x] **Step 1: Update MainActivity import**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`.

Add this import after `MessageStormScenario`:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenario
```

Keep the existing lazy field:

```kotlin
private val syncBarrierLeakScenario: SyncBarrierLeakScenario by lazy {
    SyncBarrierLeakScenario(context = this)
}
```

- [x] **Step 2: Delete old root package scenario**

Delete `app/src/main/java/com/valiantyan/vibeanrmonitoring/SyncBarrierLeakScenario.kt`.

- [x] **Step 3: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [x] **Step 4: Run focused app scenario tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/SyncBarrierLeakScenario.kt
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/SyncBarrierLeakScenario.kt
git commit -m "接入 Sync Barrier 泄漏按钮"
```

### Task 3: 更新使用说明中的 Sync Barrier / nativePollOnce 验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [ ] **Step 1: Replace existing Barrier validation block**

In `docs-anr/103-ANR监控SDK使用说明.md`, replace the paragraph from `验证 Sync Barrier 泄漏 ANR` through `同一次主线程卡死在恢复前只会写出第一份报告...` with:

```markdown
### Sync Barrier 泄漏 / nativePollOnce 场景

这个场景用于验证最容易误判的一类 ANR：系统 Trace 看到主线程在 `android.os.MessageQueue.nativePollOnce`，但真实原因不是“主线程空闲”，而是 Sync Barrier 留在队头，普通同步消息被挡住无法执行。

操作步骤：

1. 安装 debug 包并打开 Demo App。
2. 点击“Sync Barrier 泄漏 ANR”。
3. 点击后继续点屏幕 5 到 10 秒，让系统输入事件也进入等待窗口。
4. 观察 Logcat 中 `VibeAnrApplication` 是否输出 `confirmed ANR report: <eventId>`。
5. 从设备拉取 `files/anr-monitor-reports/<eventId>.json`。

优先检查这些字段：

```text
attribution.primary = SYNC_BARRIER_STUCK
pendingQueue.available = true
pendingQueue.messages[0].isBarrierLike = true
pendingQueue.messages[0].targetClass = null
pendingQueue.messages[0].arg1 = <barrier token>
barrierEvidence.available = true
barrierEvidence.alignedWithPendingBarrier = true
barrierEvidence.stuckTokens[].token = pendingQueue.messages[0].arg1
barrierEvidence.stuckTokens[].postStack 包含 SyncBarrierLeakScenario.run
barrierEvidence.nativePollOnceRecords[].source = STACK_INFERENCE 或 HOOK
```

再做排除检查：

| 字段 | 期望 | 说明 |
| --- | --- | --- |
| `attribution.primary` | `SYNC_BARRIER_STUCK` | SDK 已把队头 Barrier 识别为主因 |
| `pendingQueue.messages[0].isBarrierLike` | `true` | 队头消息像 Sync Barrier，普通同步消息会被挡住 |
| `pendingQueue.messages[0].targetClass` | `null` | Sync Barrier 的结构特征是没有 target |
| `barrierEvidence.alignedWithPendingBarrier` | `true` | token 证据能和 Pending 队头 Barrier 对上 |
| `barrierEvidence.stuckTokens[].postStack` | 包含 `SyncBarrierLeakScenario.run` | 能定位是谁插入了未移除的 Barrier |
| `binderBlock.suspected` | `false` | 排除 Binder / 跨进程等待主因 |
| `attribution.primary` | 不是 `MESSAGE_STORM` | 排除大量同类消息堆积主因 |

定位结论写法：

```text
本次 ANR 主因是 Sync Barrier 泄漏。证据是 attribution.primary=SYNC_BARRIER_STUCK，Pending 队头消息 isBarrierLike=true 且 targetClass=null，barrierEvidence.stuckTokens 中的 token 与 pendingQueue.messages[0].arg1 对齐，postStack 指向 SyncBarrierLeakScenario.run。主线程停在 nativePollOnce 是结果，不是根因；根因是未移除的 Sync Barrier 挡住了后续同步消息。
```

修复方向：

- 检查所有 `postSyncBarrier` 和 `removeSyncBarrier` 是否严格成对执行。
- 不要在异常分支、页面销毁、动画取消、任务取消时漏掉 `removeSyncBarrier`。
- 如果业务封装了 UI 调度、绘制、刷新合批能力，必须把 token 生命周期放进同一个 owner 中管理。
- 为 Barrier token 增加超时告警，超过安全阈值时输出插入栈和页面信息。
- 修复后重新验证同一场景，预期 `barrierEvidence.stuckTokens` 不再出现长期存活 token，Pending 队头不再是 `isBarrierLike=true`。

同一次主线程卡死在恢复前只会写出第一份报告。若看到多份报告，先比较 `pendingQueue.messages[0].arg1`、`barrierEvidence.stuckTokens[].token` 和 `event.timeUptimeMs`：token 相同且时间递增，通常是同一轮卡死的历史报告；token 不同或心跳恢复后再次卡死，才按新事件处理。
```

- [ ] **Step 2: Check markdown formatting**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [ ] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充 Sync Barrier 场景验证说明"
```

### Task 4: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [ ] **Step 1: Update scenario overview row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, update the Sync Barrier row to:

```markdown
| 4 | Sync Barrier 泄漏 / nativePollOnce | 点击按钮反射插入 Sync Barrier 且故意不移除 | `SYNC_BARRIER_STUCK` | `pendingQueue.messages[0].isBarrierLike=true`、`barrierEvidence.alignedWithPendingBarrier=true`、`nativePollOnceRecords` | 已实现，待手动验收 |
```

- [ ] **Step 2: Add fourth batch section**

Append this section after the existing message storm batch section:

```markdown
## 第四批次：Sync Barrier 泄漏 / nativePollOnce

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“Sync Barrier 泄漏 ANR”。
4. 持续点击屏幕 5 到 10 秒，制造输入等待窗口。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `SYNC_BARRIER_STUCK`。再看 `pendingQueue.messages[0]`，预期 `isBarrierLike=true` 且 `targetClass=null`。然后看 `barrierEvidence.stuckTokens`，预期 token 能和 `pendingQueue.messages[0].arg1` 对齐。最后看 `barrierEvidence.nativePollOnceRecords`，如果存在 `source=STACK_INFERENCE` 或 `source=HOOK`，说明 SDK 已把 nativePollOnce 表象和队头 Barrier 证据串起来。

### 排除项

- `binderBlock.suspected` 不应该为 true。
- `attribution.primary` 不应该是 `MESSAGE_STORM`。
- 不能只凭 `mainThread.stackFrames` 中出现 `nativePollOnce` 下结论，必须同时看到 Pending 队头 Barrier 和 token 对齐证据。

### 修复方向

- 检查 Barrier token 的 post/remove 配对。
- 重点排查插入栈中的业务 owner、UI 刷新、动画或调度封装。
- 确认异常分支、取消分支和页面销毁分支都会移除 Barrier。

### 验收记录

- [ ] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest` 通过。
- [ ] `./gradlew :app:compileDebugKotlin` 通过。
- [ ] 真机或模拟器点击“Sync Barrier 泄漏 ANR”后生成 JSON。
- [ ] JSON 中 `attribution.primary=SYNC_BARRIER_STUCK`。
- [ ] JSON 中 `pendingQueue.messages[0].isBarrierLike=true`。
- [ ] JSON 中 `barrierEvidence.alignedWithPendingBarrier=true`。
- [ ] JSON 中 `barrierEvidence.stuckTokens[].postStack` 能定位到 `SyncBarrierLeakScenario.run`。
```

- [ ] **Step 3: Check markdown formatting**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [ ] **Step 4: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新 Sync Barrier 场景矩阵"
```

### Task 5: 执行 Sync Barrier / nativePollOnce 最终验收

**Files:**
- Modify: `docs/superpowers/plans/2026-06-08-demo-anr-scenarios-sync-barrier-nativepollonce.md`
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [ ] **Step 1: Run focused unit test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest
```

Expected: PASS.

- [ ] **Step 2: Run app compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Run final regression**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Install and trigger scenario**

Run:

```bash
adb devices
./gradlew :app:installDebug
adb shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

Then tap the `Sync Barrier 泄漏 ANR` button in the Demo App and keep tapping the screen for 5 to 10 seconds.

Expected Logcat contains:

```text
VibeAnrApplication  W  suspect ANR captured: <eventId>
VibeAnrApplication  W  confirmed ANR report: <eventId>
AnrReportStore      I  ANR report written: .../<eventId>.json
```

- [ ] **Step 5: Pull latest JSON report**

Run:

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > /private/tmp/<eventId>.json
```

Expected: `/private/tmp/<eventId>.json` exists and contains the report.

- [ ] **Step 6: Inspect JSON evidence**

Run:

```bash
rg -n "\"primary\"|SYNC_BARRIER_STUCK|\"isBarrierLike\"|\"targetClass\"|\"alignedWithPendingBarrier\"|\"stuckTokens\"|\"nativePollOnceRecords\"|SyncBarrierLeakScenario" /private/tmp/<eventId>.json
```

Expected output contains all of these facts:

```text
"primary": "SYNC_BARRIER_STUCK"
"isBarrierLike": true
"targetClass": null
"alignedWithPendingBarrier": true
"stuckTokens": [
"nativePollOnceRecords": [
SyncBarrierLeakScenario.run
```

- [ ] **Step 7: Update acceptance record**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the fourth batch `验收记录` checklist with checked items and add the actual `eventId`:

```markdown
### 验收记录

- [x] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest` 通过。
- [x] `./gradlew :app:compileDebugKotlin` 通过。
- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [x] 真机或模拟器点击“Sync Barrier 泄漏 ANR”后生成 JSON：`<eventId>.json`。
- [x] JSON 中 `attribution.primary=SYNC_BARRIER_STUCK`。
- [x] JSON 中 `pendingQueue.messages[0].isBarrierLike=true`。
- [x] JSON 中 `barrierEvidence.alignedWithPendingBarrier=true`。
- [x] JSON 中 `barrierEvidence.stuckTokens[].postStack` 能定位到 `SyncBarrierLeakScenario.run`。
```

- [ ] **Step 8: Mark this plan task complete**

Update this plan file by checking all boxes for the tasks that passed.

- [ ] **Step 9: Commit**

```bash
git add docs/superpowers/plans/2026-06-08-demo-anr-scenarios-sync-barrier-nativepollonce.md docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录 Sync Barrier 场景验收结果"
```

## Self-Review

### 1. Spec coverage

- 覆盖了总矩阵中的“Sync Barrier 泄漏 / nativePollOnce”场景。
- 覆盖了 Demo 按钮触发、单元测试、Activity 接线、使用说明、矩阵状态和最终手动验收。
- 覆盖了关键 JSON 证据：`SYNC_BARRIER_STUCK`、队头 `isBarrierLike=true`、`targetClass=null`、token 对齐、`nativePollOnceRecords`、插入栈。
- 覆盖了排除项：不能只看 `nativePollOnce`，需要排除 Binder 和消息风暴。

### 2. Placeholder scan

本文没有未落地占位描述。每个代码步骤都给出明确文件路径、代码块、命令和预期结果。

### 3. Type consistency

`SyncBarrierPoster`、`BarrierDebugRecorder`、`BarrierBlockedMessagePoster`、`BarrierLeakComponentStarter`、`ScenarioFailureNotifier`、`SyncBarrierLeakScenario` 的包名、类名、方法名在测试、实现和 Activity 接线中保持一致。

## 举一反三提问

1. 如果线上 Trace 只看到 `nativePollOnce`，但没有 Pending 队头和 token 证据，是否能直接判定为 Barrier 泄漏？
2. 如果 `barrierEvidence.stuckTokens` 有 token，但 `pendingQueue.messages[0].arg1` 对不上，应该判断为旧 token、误采集，还是另一轮卡顿？
3. 如果同一次点击生成多份 JSON，应该按 `eventId` 分析，还是按队头 token 和 `event.timeUptimeMs` 合并成同一轮问题？
4. 如果设备 ROM 禁止反射 `postSyncBarrier`，Demo 应该如何让测试者明确知道场景没有真正触发？
5. 如果 `nativePollOnceRecords` 只有 `STACK_INFERENCE` 没有 `HOOK`，评审时应该如何说明证据强度？
6. 如果 Barrier 后方只有业务 `Handler` 消息，没有 Service 组件消息，是否仍然足够证明同步消息被挡住？
7. 如果 `attribution.primary` 是 `SYNC_BARRIER_STUCK`，但 `mainThread.current.wallMs` 也很高，应该优先修当前消息还是先修 Barrier 配对？
8. 如果插入栈只能看到 Demo 场景类，看不到业务 owner，真实 SDK 接入时还需要补哪些上下文？
9. 修复后如何证明问题真的消失：只看没有 ANR，还是要验证 token 生命周期、Pending 队头和报告归因都恢复正常？
10. 这个场景是否需要和“消息风暴”共存测试，验证归因优先级不会被大量同步消息误导？

## 三轮审核

### 第一轮：需求覆盖审核

结论：通过，计划覆盖了“Sync Barrier 泄漏 / nativePollOnce”的核心需求：Demo 按钮可触发、单元测试可验证、JSON 能看到 `SYNC_BARRIER_STUCK`、队头 Barrier、token 对齐、`nativePollOnceRecords` 和插入栈。

补强点：计划明确不能只凭 `nativePollOnce` 下结论，必须同时看 Pending 队头和 Barrier token，这能避免把普通 Looper 空闲误判为 ANR 根因。

### 第二轮：实现可执行性审核

结论：修正后通过。原计划中 `ReflectionSyncBarrierPoster` 和 `SyncBarrierLeakScenario.run()` 都会提示反射失败，真实运行时可能重复 Toast 和日志。现已改为 `ReflectionSyncBarrierPoster` 只返回 `null`，由 `SyncBarrierLeakScenario.run()` 统一调用 `ScenarioFailureNotifier`，测试和真实行为一致。

继续执行时要注意：Task 2 删除根包旧类时，需要确认 `MainActivity` 已导入 `com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenario`，否则会出现 unresolved reference。

### 第三轮：验收和评审口径审核

结论：通过。计划不仅要求单元测试和编译通过，还要求真实设备生成 JSON，并用字段检查证明根因链路完整。

评审时建议固定使用这个结论：主线程停在 `nativePollOnce` 是结果，不是根因；根因是队头 Sync Barrier 未移除，导致同步消息无法出队。证据必须包含 `pendingQueue.messages[0].isBarrierLike=true`、`targetClass=null`、`barrierEvidence.alignedWithPendingBarrier=true`，以及能定位插入点的 `stuckTokens[].postStack`。
