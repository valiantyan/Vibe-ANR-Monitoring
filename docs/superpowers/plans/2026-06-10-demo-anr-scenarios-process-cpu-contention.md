# Demo 进程内 CPU 竞争场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中新增“进程内 CPU 竞争”ANR 验证场景，让 SDK 能通过 JSON 里的线程 CPU 排名、当前消息耗时和主线程栈判断“后台线程抢占 CPU 资源导致主线程响应变慢”。

**Architecture:** 本计划沿用现有 Demo 场景模式：Activity 只负责按钮和 intent 接线，复现逻辑放在 `scenario` 包中的独立类。场景类通过 `ProcessCpuContentionWorkload` 注入可测试工作负载；真实 Demo 运行时先启动多个命名后台线程持续消耗 CPU，再让当前点击消息进入低 CPU 等待窗口，使报告既能生成，又能在 `threadCpu.topThreads` 里看到后台竞争线程，而不是误判为“主线程 CPU 忙等”。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`Thread`、`CountDownLatch`、`AtomicBoolean`、本地 JSON 报告。

---

## Scope Check

“进程内 CPU 竞争”不是“主线程 CPU 忙等”。主线程 CPU 忙等的根因是按钮点击消息自身持续计算，JSON 里 `mainThread.current.cpuMs` 和主线程 CPU 会很高；本场景的根因是同一进程内后台线程持续消耗 CPU，主线程当前消息主要表现为 wall time 变长、CPU 不一定高，真正高 CPU 线程应出现在 `threadCpu.topThreads`。

本计划只新增一个 Demo 按钮和对应文档，不修改 SDK 归因规则。当前 SDK 没有单独的 `PROCESS_THREAD_CPU_BUSY` 主归因枚举，所以验收时允许 `attribution.primary` 仍为 `CURRENT_MESSAGE_SLOW` 或 `UNKNOWN_INSUFFICIENT_EVIDENCE`，但必须能用以下证据链人工判断 CPU 竞争成立：

- `mainThread.current.wallMs >= 3000`，说明疑似 ANR 抓取窗口成立。
- `mainThread.stackFrames` 包含 `ProcessCpuContentionScenario.run` 或 `DefaultProcessCpuContentionWorkload.createContentionAndWaitOnMainThread`，说明入口是本 Demo 场景。
- `threadCpu.topThreads` 至少出现一个 `DemoCpuContender-` 命名线程，且它的 `totalCpuMs` 高于主线程或位于 Top 线程前列。
- `mainThread.current.cpuMs` 不应接近主线程 CPU 忙等场景的水平；如果当前消息 CPU 也很高，应保守表述为“CPU 竞争与主线程执行共同存在”，不能只归因为后台竞争。
- `barrierEvidence.stuckTokens`、`binderBlock.suspected` 不应成为本次主因。

如果后续要让 SDK 自动输出 `PROCESS_THREAD_CPU_BUSY` 主归因，需要另起 SDK 归因增强计划。本计划只实现 Demo 复现与现有 JSON 证据验证。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenarioTest.kt`
  - JVM 单元测试，验证场景元数据和工作负载调用参数。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionWorkload.kt`
  - 可注入的进程内 CPU 竞争工作负载接口。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/DefaultProcessCpuContentionWorkload.kt`
  - 真实 Demo 工作负载：启动多个 `DemoCpuContender-*` 后台线程 busy loop，并让主线程等待观察窗口。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenario.kt`
  - 场景入口，暴露 `id`、`title`、`expectedAttribution`、`expectedJsonSignals` 和 `run()`。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 新增场景属性，把按钮和 `anr_demo_scenario=process_cpu_contention` 接到场景类。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 新增“进程内 CPU 竞争”按钮。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增按钮中文文案。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加进程内 CPU 竞争场景验证步骤、JSON 判断口径和修复建议。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 将场景 13 状态从“待实现”更新为“已实现，待手动验收”，补充本场景批次说明。
- Modify: `README.md`
  - 在“Demo 已覆盖场景”加入新场景，并从后续计划移除“进程内 CPU 竞争”。

## Implementation Tasks

### Task 1: 用 TDD 实现进程内 CPU 竞争场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/DefaultProcessCpuContentionWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证进程内 CPU 竞争场景的元数据和工作负载入口。
 */
class ProcessCpuContentionScenarioTest {
    @Test
    fun runExecutesConfiguredCpuContentionWorkload(): Unit {
        val workload: RecordingProcessCpuContentionWorkload = RecordingProcessCpuContentionWorkload()
        val scenario: ProcessCpuContentionScenario = ProcessCpuContentionScenario(
            workload = workload,
            contenderCount = 6,
            contentionDurationMs = 7_000L,
            mainThreadWaitMs = 4_321L,
        )

        scenario.run()

        assertEquals(listOf(RecordedCall(6, 7_000L, 4_321L)), workload.recordedCalls)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingProcessCpuContentionWorkload = RecordingProcessCpuContentionWorkload()
        val scenario: ProcessCpuContentionScenario = ProcessCpuContentionScenario(
            workload = workload,
        )

        assertEquals("process_cpu_contention", scenario.id)
        assertEquals("进程内 CPU 竞争", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + process thread CPU contention evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ProcessCpuContentionScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("threadCpu.topThreads 包含 DemoCpuContender-"))
        assertTrue(scenario.expectedJsonSignals.contains("后台竞争线程 totalCpuMs 位于 Top 线程前列"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.cpuMs 低于纯主线程忙等场景"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingProcessCpuContentionWorkload : ProcessCpuContentionWorkload {
        val recordedCalls: MutableList<RecordedCall> = mutableListOf()

        override fun createContentionAndWaitOnMainThread(
            contenderCount: Int,
            contentionDurationMs: Long,
            mainThreadWaitMs: Long,
        ): Unit {
            recordedCalls.add(
                RecordedCall(
                    contenderCount = contenderCount,
                    contentionDurationMs = contentionDurationMs,
                    mainThreadWaitMs = mainThreadWaitMs,
                ),
            )
        }
    }

    private data class RecordedCall(
        val contenderCount: Int,
        val contentionDurationMs: Long,
        val mainThreadWaitMs: Long,
    )
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenarioTest
```

Expected: FAIL with unresolved references for `ProcessCpuContentionWorkload` and `ProcessCpuContentionScenario`.

- [x] **Step 3: Create ProcessCpuContentionWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 进程内 CPU 竞争工作负载。测试中替换为记录器，Demo 运行时启动真实后台竞争线程。
 */
fun interface ProcessCpuContentionWorkload {
    /**
     * 启动后台 CPU 竞争线程，并在调用线程维持一个可观测等待窗口。
     *
     * @param contenderCount 后台竞争线程数量。
     * @param contentionDurationMs 后台竞争线程持续消耗 CPU 的时长。
     * @param mainThreadWaitMs 主线程当前消息保持阻塞的时长，用于稳定触发 SDK 报告。
     */
    fun createContentionAndWaitOnMainThread(
        contenderCount: Int,
        contentionDurationMs: Long,
        mainThreadWaitMs: Long,
    ): Unit
}
```

- [x] **Step 4: Create DefaultProcessCpuContentionWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/DefaultProcessCpuContentionWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demo 专用进程内 CPU 竞争工作负载。
 *
 * 这个类会启动多个命名后台线程持续 busy loop，制造进程内 CPU 抢占证据。
 * 调用线程会进入低 CPU 等待窗口，确保 SDK 有机会生成疑似 ANR 报告。
 */
class DefaultProcessCpuContentionWorkload : ProcessCpuContentionWorkload {
    /**
     * 启动后台竞争线程，并在当前线程等待指定窗口。Demo 按钮会在主线程调用此方法。
     */
    override fun createContentionAndWaitOnMainThread(
        contenderCount: Int,
        contentionDurationMs: Long,
        mainThreadWaitMs: Long,
    ): Unit {
        val running: AtomicBoolean = AtomicBoolean(true)
        val startedLatch: CountDownLatch = CountDownLatch(contenderCount)
        val workerThreads: List<Thread> = createContenderThreads(
            contenderCount = contenderCount,
            contentionDurationMs = contentionDurationMs,
            running = running,
            startedLatch = startedLatch,
        )
        workerThreads.forEach { thread: Thread ->
            thread.start()
        }
        try {
            val allStarted: Boolean = startedLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            check(allStarted) {
                "进程内 CPU 竞争场景启动失败，竞争线程未在 ${START_TIMEOUT_MS}ms 内全部开始"
            }
            Log.w(TAG, "进程内 CPU 竞争场景开始: contenders=$contenderCount")
            waitOnMainThread(waitDurationMs = mainThreadWaitMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            running.set(false)
            workerThreads.forEach { thread: Thread ->
                thread.interrupt()
            }
            workerThreads.forEach { thread: Thread ->
                joinQuietly(thread = thread)
            }
            Log.w(TAG, "进程内 CPU 竞争场景结束")
        }
    }

    private fun createContenderThreads(
        contenderCount: Int,
        contentionDurationMs: Long,
        running: AtomicBoolean,
        startedLatch: CountDownLatch,
    ): List<Thread> {
        val nextThreadId: AtomicInteger = AtomicInteger(1)
        return List(contenderCount) {
            val threadName: String = "DemoCpuContender-${nextThreadId.getAndIncrement()}"
            Thread(
                {
                    runCpuContentionLoop(
                        threadName = threadName,
                        contentionDurationMs = contentionDurationMs,
                        running = running,
                        startedLatch = startedLatch,
                    )
                },
                threadName,
            ).apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    }

    private fun runCpuContentionLoop(
        threadName: String,
        contentionDurationMs: Long,
        running: AtomicBoolean,
        startedLatch: CountDownLatch,
    ): Unit {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (error: SecurityException) {
            Log.w(TAG, "$threadName 无法提升线程优先级，继续使用默认优先级", error)
        }
        val endUptimeMs: Long = SystemClock.uptimeMillis() + contentionDurationMs
        var checksum: Double = CHECKSUM_SEED
        startedLatch.countDown()
        while (running.get() && SystemClock.uptimeMillis() < endUptimeMs) {
            checksum += Math.sqrt(checksum + CHECKSUM_OFFSET)
            if (checksum > CHECKSUM_RESET_THRESHOLD) {
                checksum = CHECKSUM_SEED
            }
        }
        Log.w(TAG, "$threadName finished checksum=$checksum")
    }

    private fun waitOnMainThread(waitDurationMs: Long): Unit {
        val endUptimeMs: Long = SystemClock.uptimeMillis() + waitDurationMs
        while (SystemClock.uptimeMillis() < endUptimeMs) {
            try {
                Thread.sleep(MAIN_THREAD_SLEEP_SLICE_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun joinQuietly(thread: Thread): Unit {
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /**
         * 等待竞争线程全部启动的最长时间。
         */
        private const val START_TIMEOUT_MS: Long = 1_500L

        /**
         * 主线程睡眠切片，保持低 CPU 等待特征，避免和主线程 busy loop 混淆。
         */
        private const val MAIN_THREAD_SLEEP_SLICE_MS: Long = 250L

        /**
         * 停止竞争线程后的 join 等待时间。
         */
        private const val JOIN_TIMEOUT_MS: Long = 300L

        /**
         * 校验值初始值。
         */
        private const val CHECKSUM_SEED: Double = 1.0

        /**
         * 每轮计算偏移，避免循环被过度优化。
         */
        private const val CHECKSUM_OFFSET: Double = 31.0

        /**
         * 校验值上限，防止长时间运行时数值无限增大。
         */
        private const val CHECKSUM_RESET_THRESHOLD: Double = 1_000_000.0

        /**
         * Demo 场景日志标签。
         */
        private const val TAG: String = "ProcessCpuContention"
    }
}
```

- [x] **Step 5: Create ProcessCpuContentionScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 进程内 CPU 竞争 ANR 场景。
 *
 * 点击按钮后启动多个后台 CPU 竞争线程，再让当前主线程消息保持低 CPU 等待窗口，
 * 用于验证 SDK 能否通过线程 CPU 排名识别进程内后台线程抢占。
 */
class ProcessCpuContentionScenario(
    private val workload: ProcessCpuContentionWorkload = DefaultProcessCpuContentionWorkload(),
    private val contenderCount: Int = defaultContenderCount(),
    private val contentionDurationMs: Long = DEFAULT_CONTENTION_DURATION_MS,
    private val mainThreadWaitMs: Long = DEFAULT_MAIN_THREAD_WAIT_MS,
) : AnrDemoScenario {
    /** 场景唯一标识，后续文档、intent 触发和分析报告用它区分 Demo 类型。 */
    override val id: String = "process_cpu_contention"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "进程内 CPU 竞争"

    /** 预期归因说明，当前 SDK 以当前消息慢或未知为主，并结合线程 CPU 排名人工确认。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + process thread CPU contention evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 ProcessCpuContentionScenario.run",
        "threadCpu.topThreads 包含 DemoCpuContender-",
        "后台竞争线程 totalCpuMs 位于 Top 线程前列",
        "mainThread.current.cpuMs 低于纯主线程忙等场景",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发进程内后台线程 CPU 竞争，并让当前点击消息形成可观测等待窗口。
     */
    override fun run(): Unit {
        workload.createContentionAndWaitOnMainThread(
            contenderCount = contenderCount,
            contentionDurationMs = contentionDurationMs,
            mainThreadWaitMs = mainThreadWaitMs,
        )
    }

    private companion object {
        /**
         * 后台竞争线程默认持续 8 秒，覆盖 SDK 疑似 ANR 抓取窗口。
         */
        private const val DEFAULT_CONTENTION_DURATION_MS: Long = 8_000L

        /**
         * 主线程等待 6 秒，稳定超过 Demo SDK 的 `suspectAnrMs=3000`。
         */
        private const val DEFAULT_MAIN_THREAD_WAIT_MS: Long = 6_000L

        /**
         * 根据 CPU 核数创建竞争线程，至少 4 个，最多 8 个，避免低端设备线程过多。
         */
        private fun defaultContenderCount(): Int {
            val availableProcessors: Int = Runtime.getRuntime().availableProcessors()
            return (availableProcessors + 1).coerceIn(4, 8)
        }
    }
}
```

- [x] **Step 6: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenarioTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/DefaultProcessCpuContentionWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ProcessCpuContentionScenario.kt
git commit -m "实现进程内CPU竞争Demo场景"
```

### Task 2: 将进程内 CPU 竞争按钮接入 Demo

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Update MainActivity imports and property**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`.

Add this import with other scenario imports:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenario
```

Add this property after `gcMemoryChurnScenario`:

```kotlin
    // 进程内 CPU 竞争场景，按钮点击后启动后台 CPU 竞争线程并保留当前消息观察窗口。
    private val processCpuContentionScenario: ProcessCpuContentionScenario =
        ProcessCpuContentionScenario()
```

- [ ] **Step 2: Wire the button and intent extra**

In `onCreate`, add this block after the `gcMemoryChurnButton` listener:

```kotlin
        findViewById<Button>(R.id.processCpuContentionButton).setOnClickListener {
            processCpuContentionScenario.run()
        }
```

In `runScenarioFromIntent`, add this branch after `gcMemoryChurnScenario.id`:

```kotlin
            processCpuContentionScenario.id -> processCpuContentionScenario.run()
```

- [ ] **Step 3: Add layout button**

Modify `app/src/main/res/layout/activity_main.xml`. Add this button after `gcMemoryChurnButton`:

```xml
        <Button
            android:id="@+id/processCpuContentionButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_process_cpu_contention" />
```

- [ ] **Step 4: Add Chinese string**

Modify `app/src/main/res/values/strings.xml`. Add this string after `demo_gc_memory_churn`:

```xml
    <string name="demo_process_cpu_contention">进程内 CPU 竞争</string>
```

- [ ] **Step 5: Run compile and unit test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenarioTest
./gradlew :app:compileDebugKotlin
```

Expected: both commands PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml
git commit -m "接入进程内CPU竞争Demo按钮"
```

### Task 3: 更新使用说明中的进程内 CPU 竞争验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [ ] **Step 1: Add scenario section**

Add this section after the GC / 内存抖动验证步骤 in `docs-anr/103-ANR监控SDK使用说明.md`:

```markdown
### 进程内 CPU 竞争

这个场景用于验证“主线程本身不是持续计算，但同一进程内后台线程打满 CPU，导致主线程调度和输入响应变慢”的问题。用户点击按钮后，Demo 会启动多个 `DemoCpuContender-*` 后台线程持续计算，同时当前点击消息保持 6 秒低 CPU 等待窗口，方便 SDK 生成报告。

#### 操作步骤

1. 安装 debug 包并打开 Demo App。
2. 点击“进程内 CPU 竞争”。
3. 等待 logcat 输出 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON：

```bash
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON > process-cpu-contention.json
```

#### JSON 判断口径

1. 看 `mainThread.current.wallMs`，应大于 `3000`，说明 SDK 的疑似 ANR 窗口成立。
2. 看 `mainThread.stackFrames`，应包含 `ProcessCpuContentionScenario.run` 或 `DefaultProcessCpuContentionWorkload.createContentionAndWaitOnMainThread`。
3. 看 `threadCpu.topThreads`，应出现 `DemoCpuContender-1`、`DemoCpuContender-2` 等后台竞争线程，且它们位于 CPU 排名前列。
4. 对比 `mainThread.current.cpuMs` 和 `threadCpu.topThreads`：如果主线程 CPU 不高，而后台竞争线程 CPU 高，本次更像进程内 CPU 竞争；如果主线程 CPU 同样很高，要按“主线程执行 + 后台竞争共同存在”保守表述。
5. 看 `barrierEvidence.stuckTokens` 和 `binderBlock.suspected`，它们不应成为本次主因。

#### 结论模板

本次报告由 Demo 进程内 CPU 竞争场景触发。当前消息 wall time 超过阈值，主线程栈定位到 `ProcessCpuContentionScenario`，同时 `threadCpu.topThreads` 中出现多个 `DemoCpuContender-*` 高 CPU 后台线程。Barrier 和 Binder 证据不构成本次主因，因此根因可以写为：同一进程内后台线程持续占用 CPU，造成主线程调度和输入响应延迟。

#### 修复建议

- 限制后台 CPU 密集任务并发数，不要按请求数无限启动计算线程。
- 后台批处理改为分片执行，并在批次之间让出调度窗口。
- CPU 密集任务降低线程优先级，避免与主线程争抢前台调度资源。
- 在线上结合线程名、任务名、耗时和 CPU 排名做采样上报，避免只看到主线程 `nativePollOnce` 或等待栈却找不到真正消耗资源的线程。
```

- [ ] **Step 2: Check markdown**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0`.

- [ ] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充进程内CPU竞争验证说明"
```

### Task 4: 更新 Demo 场景矩阵和 README 状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
- Modify: `README.md`

- [ ] **Step 1: Update scenario matrix row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the scenario 13 row with:

```markdown
| 13 | 进程内 CPU 竞争 | 点击“进程内 CPU 竞争”后启动多个 `DemoCpuContender-*` 后台线程并保留当前消息观察窗口 | `CURRENT_MESSAGE_SLOW` + 进程内线程 CPU 竞争辅因 | `mainThread.current.wallMs`、`mainThread.stackFrames` 包含 `ProcessCpuContentionScenario.run` / `DefaultProcessCpuContentionWorkload`、`threadCpu.topThreads` 包含 `DemoCpuContender-*` | 已实现，待手动验收 |
```

- [ ] **Step 2: Add scenario batch section**

Append this section near the end of `docs-anr/105-Demo-ANR场景实现计划.md`, after the GC / 内存抖动批次 section:

```markdown
## 第十三批次：进程内 CPU 竞争

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“进程内 CPU 竞争”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。再看 `mainThread.stackFrames`，应能看到 `ProcessCpuContentionScenario.run` 或 `DefaultProcessCpuContentionWorkload.createContentionAndWaitOnMainThread`，说明入口是 Demo 进程内 CPU 竞争场景。接着看 `threadCpu.topThreads`，应出现 `DemoCpuContender-*` 线程，并且这些后台线程位于 CPU 排名前列。最后对比 `mainThread.current.cpuMs`，如果主线程 CPU 不高而后台竞争线程 CPU 高，本次根因应写为进程内后台线程 CPU 竞争。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果 `mainThread.current.cpuMs` 和主线程线程 CPU 都很高，应检查是否点错了“当前消息忙等”，或是否存在主线程执行与后台竞争混合问题。
- 如果 `threadCpu.topThreads` 中没有 `DemoCpuContender-*`，应检查线程 CPU 采集是否失败，或当前设备 CPU 核数/调度导致竞争证据不明显。
```

- [ ] **Step 3: Update README covered scenarios**

In `README.md`, add this row after `GC / 内存抖动` in “Demo 已覆盖场景”:

```markdown
| 进程内 CPU 竞争 | 点击“进程内 CPU 竞争” | `CURRENT_MESSAGE_SLOW` + 进程内线程 CPU 竞争辅因，`threadCpu.topThreads` 出现 `DemoCpuContender-*` 高 CPU 线程 |
```

Replace:

```markdown
后续场景计划包括主线程锁等待、进程内 CPU 竞争等。
```

With:

```markdown
后续场景计划包括主线程锁等待等。
```

- [ ] **Step 4: Check docs**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md README.md
```

Expected: command exits with code `0`.

- [ ] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md README.md
git commit -m "更新进程内CPU竞争场景状态"
```

### Task 5: 执行进程内 CPU 竞争最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
- Optional create after manual pull: `SDK案例分析/进程内 CPU 竞争/JSON/<event-id>.json`
- Optional create after manual analysis: `SDK案例分析/进程内 CPU 竞争/分析结果/<event-id>-ANR根因分析.html`

- [ ] **Step 1: Run automated checks**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenarioTest
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: all commands PASS.

- [ ] **Step 2: Install Demo**

Run:

```bash
adb devices
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
```

Expected: install succeeds and logcat is cleared.

- [ ] **Step 3: Trigger scenario by intent**

Run:

```bash
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity --es anr_demo_scenario process_cpu_contention
```

Expected: logcat contains:

```text
W MainActivity: run demo scenario from intent: process_cpu_contention
W ProcessCpuContention: 进程内 CPU 竞争场景开始: contenders=
W VibeAnrApplication: suspect ANR captured: <event-id>
W VibeAnrApplication: ANR report written: <event-id>
```

- [ ] **Step 4: Pull latest JSON**

Run:

```bash
mkdir -p SDK案例分析/进程内\ CPU\ 竞争/JSON SDK案例分析/进程内\ CPU\ 竞争/分析结果
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls -t files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON > SDK案例分析/进程内\ CPU\ 竞争/JSON/<event-id>.json
```

Expected: JSON file is pulled into `SDK案例分析/进程内 CPU 竞争/JSON/`.

- [ ] **Step 5: Verify JSON evidence**

Open the pulled JSON and verify these fields:

```text
event.eventType = SUSPECT_ANR
mainThread.current.wallMs >= 3000
mainThread.stackFrames contains ProcessCpuContentionScenario.run
mainThread.stackFrames contains DefaultProcessCpuContentionWorkload.createContentionAndWaitOnMainThread
threadCpu.topThreads contains DemoCpuContender-
at least one DemoCpuContender-* totalCpuMs is near the top of threadCpu.topThreads
barrierEvidence.stuckTokens = []
binderBlock.suspected = false
```

If `attribution.primary` is `CURRENT_MESSAGE_SLOW`, record it as current SDK primary attribution plus CPU contention auxiliary evidence. If `attribution.primary` is `UNKNOWN_INSUFFICIENT_EVIDENCE`, record it as acceptable only when thread CPU evidence clearly shows `DemoCpuContender-*` threads.

- [ ] **Step 6: Append acceptance record**

Append this template to the “第十三批次：进程内 CPU 竞争” section in `docs-anr/105-Demo-ANR场景实现计划.md`, replacing the concrete values with the actual event:

````markdown
### 首次验收记录

验收时间：2026-06-10 HH:mm CST

验收设备：`<device-id>`

执行命令：

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenarioTest
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity --es anr_demo_scenario process_cpu_contention
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls -t files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = <CURRENT_MESSAGE_SLOW or UNKNOWN_INSUFFICIENT_EVIDENCE>
mainThread.current.wallMs = <actual-wall-ms>
mainThread.current.cpuMs = <actual-cpu-ms>
threadCpu.topThreads contains DemoCpuContender-<n>
DemoCpuContender-<n>.totalCpuMs = <actual-worker-cpu-ms>
mainThread.stackFrames contains ProcessCpuContentionScenario.run
mainThread.stackFrames contains DefaultProcessCpuContentionWorkload.createContentionAndWaitOnMainThread
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：进程内 CPU 竞争场景验收通过。SDK 能捕获疑似 ANR，当前消息 wall time 超过阈值，主线程栈能定位到 `ProcessCpuContentionScenario`，线程 CPU 排名能看到 `DemoCpuContender-*` 后台线程位于 CPU 前列，Binder 和 Barrier 证据均不是本次主因，因此可以把根因写为“同一进程内后台 CPU 密集任务持续抢占调度资源，导致主线程输入响应延迟”。
````

- [ ] **Step 7: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md SDK案例分析/进程内\ CPU\ 竞争/JSON
git commit -m "记录进程内CPU竞争场景验收结果"
```

## 举一反三提问

1. 如果 `attribution.primary=CURRENT_MESSAGE_SLOW`，为什么还能说这是进程内 CPU 竞争？

   可以，但不能只凭归因码下结论。当前 SDK 的主归因可能先识别“主线程当前消息超过阈值”，而进程内 CPU 竞争要继续看 `threadCpu.topThreads`。如果 Top 线程里出现多个 `DemoCpuContender-*`，且这些后台线程 CPU 明显高于主线程，就可以把 `CURRENT_MESSAGE_SLOW` 解释为“主线程被同进程后台 CPU 竞争拖慢后的表象”。

2. 如果主线程栈里看到的是 `Thread.sleep`，这会不会变成“当前消息慢”而不是“CPU 竞争”？

   会有这个风险，所以本场景必须把结论写成证据链：主线程当前消息确实处于等待窗口，但等待窗口只是为了稳定生成 SDK 报告；真正的竞争证据来自 `threadCpu.topThreads` 里的 `DemoCpuContender-*`。如果没有后台高 CPU 证据，就不能把它归因为进程内 CPU 竞争。

3. 为什么不能直接让后台线程打满 CPU 后不阻塞主线程？

   因为 SDK 的疑似 ANR 触发依赖主线程响应延迟。如果主线程仍能及时处理 Watchdog 心跳和 Looper 消息，后台 CPU 很高也可能只是一段资源压力，不一定生成 ANR JSON。本计划用“后台 CPU 竞争 + 主线程低 CPU 等待窗口”组合，是为了在线下 Demo 中稳定得到可分析报告。

4. 如果 `DemoCpuContender-*` 没有出现在 `threadCpu.topThreads`，先查什么？

   先查线程 CPU 采集是否成功、`sdkDiagnostics.failureReasons` 是否有 `/proc` 读取失败，再看设备 CPU 核数和当前负载。如果设备核数多、调度宽松，后台竞争可能不明显，可以重试一次或增加手动点击期间的系统负载，但不能修改 SDK 归因结论凑结果。

5. 为什么 Demo 里会尝试提升后台线程优先级？真实业务能这么做吗？

   Demo 提升优先级只是为了放大 CPU 竞争现象，让测试设备更稳定地产生可观测证据。真实业务修复方向恰好相反：应限制后台 CPU 密集任务并发、降低优先级、分片执行，避免和主线程争抢调度资源。

6. 如果 `mainThread.current.cpuMs` 也很高，结论应该怎么写？

   不能写成纯后台 CPU 竞争。应保守写为“主线程当前消息执行耗时和后台 CPU 竞争共同存在”，再结合栈判断主线程是否也在执行计算。如果主线程栈命中 `MainThreadCpuBusyScenario` 或业务计算函数，应优先分析主线程执行慢。

7. 如果系统 ANR 日志里主线程是 `nativePollOnce`，这份 SDK JSON 还能定位 CPU 竞争吗？

   可以辅助定位，但要看 JSON 的采样时机。若系统 trace 只有 `nativePollOnce`，而 SDK JSON 的线程 CPU 排名显示 `DemoCpuContender-*` 高 CPU，说明主线程可能在等待消息或调度时被进程内 CPU 抢占影响。此时还要排除 `barrierEvidence`，避免把 Sync Barrier 假死误判为 CPU 竞争。

8. 这个场景的修复结论应该怎么写？

   推荐写成：同一进程内后台 CPU 密集任务并发过高，持续占用调度资源，导致主线程输入响应延迟；应控制后台任务并发、降低优先级、分片执行，并给 CPU 密集任务加线程名和耗时埋点。

## 三轮审核

### 第一轮：需求覆盖审核

审核结论：通过，且已补充边界说明。

核对结果：

- 计划只覆盖 `docs-anr/105-Demo-ANR场景实现计划.md` 中的“进程内 CPU 竞争”场景，没有把主线程锁等待、外部系统负载或主线程 CPU 忙等混入同一批次。
- 计划明确当前 SDK 没有 `PROCESS_THREAD_CPU_BUSY` 主归因枚举，验收依赖 `threadCpu.topThreads` 辅助证据，不强行修改 SDK 归因模型。
- 计划明确本场景和“主线程 CPU 忙等”的差异：本场景关键线程是 `DemoCpuContender-*`，不是主线程本身。

执行时重点关注：

- 不要为了追求归因码新增 SDK 枚举或改动 SDK 规则；本计划只做 Demo 复现和文档验收。
- 如果报告只证明“当前消息慢”，但没有后台线程 CPU 证据，本场景不能算通过。

### 第二轮：工程可执行性审核

审核结论：修正后通过。

核对结果：

- 测试、接口、实现和 Activity 接线使用的类型名一致：`ProcessCpuContentionScenario`、`ProcessCpuContentionWorkload`、`DefaultProcessCpuContentionWorkload`。
- `anr_demo_scenario=process_cpu_contention` 与场景 `id` 一致，后续自动化验收可以复用现有 intent 入口。
- 计划已补充 `mkdir -p SDK案例分析/进程内\ CPU\ 竞争/JSON ...`，避免拉取 JSON 时因为目录不存在失败。
- 计划已将 `Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)` 包进 `SecurityException` 降级逻辑，避免部分 ROM 不允许提升线程优先级时直接导致 Demo 崩溃。

执行时重点关注：

- 创建 Kotlin 文件前需要加载 Kotlin 基础、命名、函数、文档、异常处理相关技能，因为本任务会新增接口、实现类和 try/catch。
- `DefaultProcessCpuContentionWorkload` 的后台线程必须在 `finally` 中停止并 join，避免点击一次后后台竞争线程长期污染后续场景。

### 第三轮：验收和新人分析审核

审核结论：通过，验收口径足够给新人复盘。

核对结果：

- 计划要求同时查看 `mainThread.current.wallMs`、`mainThread.current.cpuMs`、`mainThread.stackFrames`、`threadCpu.topThreads`、`barrierEvidence` 和 `binderBlock`，不会让新人只看一个字段。
- 计划明确了通过条件：`DemoCpuContender-*` 必须出现在 CPU Top 线程中，并且当前报告能定位到 `ProcessCpuContentionScenario`。
- 计划明确了失败或弱证据处理：如果 Top 线程里没有竞争线程，应检查 CPU 采集、设备核数、调度环境和 `/proc` 读取失败原因。
- 计划明确了保守表达：如果主线程 CPU 也很高，要写成混合问题，不能硬写成纯后台 CPU 竞争。

执行时重点关注：

- 最终验收记录不要只写“生成 JSON”，必须写出至少一个 `DemoCpuContender-*` 的 `totalCpuMs`。
- 如果系统 trace 显示 `nativePollOnce`，报告仍需先排除 Sync Barrier，再用线程 CPU 排名解释资源竞争，避免错归因。

## 审核后结论

这份计划可以进入实现阶段。执行建议保持 Task 1 到 Task 5 的顺序：先用 TDD 建立独立场景类，再接入按钮和 intent，随后更新使用说明、矩阵和 README，最后用真实设备 JSON 验收 `DemoCpuContender-*` 线程 CPU 证据。当前不需要拆出新的 SDK 归因增强计划；如果后续希望 JSON 自动输出 `PROCESS_THREAD_CPU_BUSY`，再单独规划 SDK 归因模型升级。

## Self-Review

1. Spec coverage: 本计划覆盖 `docs/superpowers/plans/2026-06-08-demo-anr-scenarios-current-slow.md` 中顺序 13 的“进程内 CPU 竞争”场景；实现范围不扩散到主线程锁等待或外部系统负载。
2. Placeholder scan: 计划中没有留空项、延后实现项或跳转式描述；每个代码步骤都给出了完整代码或明确插入片段。
3. Type consistency: `ProcessCpuContentionScenario`、`ProcessCpuContentionWorkload`、`DefaultProcessCpuContentionWorkload`、`createContentionAndWaitOnMainThread`、`process_cpu_contention` 在测试、实现、Activity intent 和文档中保持一致。
4. Root-cause clarity: 验收要求同时检查 `mainThread.current.wallMs`、`mainThread.current.cpuMs`、`threadCpu.topThreads`、业务栈、Barrier 和 Binder 排除项，避免把后台 CPU 竞争误写成主线程忙等。

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-10-demo-anr-scenarios-process-cpu-contention.md`. Two execution options:

1. Subagent-Driven (recommended) - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. Inline Execution - execute tasks in this session using executing-plans, batch execution with checkpoints.
