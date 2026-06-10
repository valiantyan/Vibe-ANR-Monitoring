# 线程池耗尽 + 主线程等待 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中新增“线程池耗尽 + 主线程等待”ANR 场景，让 SDK 可以抓到主线程等待线程池任务结果导致的当前消息慢，并在 JSON 中定位到等待入口。

**Architecture:** 本计划沿用现有 Demo 场景模式：Activity 只负责按钮和 intent 接线，复现逻辑放到 `scenario` 包中的独立类。场景类通过 `ThreadPoolWaitWorkload` 注入可测试工作负载；真实 Demo 运行时使用固定大小线程池先占满 worker，再在主线程同步等待排队任务的 `Future.get()`，从而稳定制造“线程池耗尽后主线程等待”的 ANR 现场。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`ThreadPoolExecutor`、`Future.get()`、`CountDownLatch`、本地 JSON 报告。

---

## Scope Check

“线程池耗尽 + 主线程等待”是等待类 ANR，不是 CPU 忙等、IO/数据库阻塞、Binder 跨进程阻塞或 Sync Barrier 泄漏。为了避免证据混淆，本计划只新增一个 Demo 按钮：先让固定线程池的所有 worker 被长任务占满，再让主线程等待一个排队任务结果。

本计划不修改 SDK 归因规则。验收时主归因可继续表现为 `CURRENT_MESSAGE_SLOW`，关键是 JSON 的 `mainThread.stackFrames` 能定位到 `FutureTask.get`、`ThreadPoolExhaustionWorkload.exhaustPoolAndWait` 和 `ThreadPoolExhaustionWaitScenario.run`。如果后续需要把“线程池耗尽”做成 SDK 专项归因，应另起 SDK 采集增强计划。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenarioTest.kt`
  - JVM 单元测试，验证场景元数据和工作负载调用。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolWaitWorkload.kt`
  - 可注入的线程池耗尽等待工作负载接口。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWorkload.kt`
  - 真实 Demo 工作负载：固定线程池 worker 被长任务占满，主线程等待排队 `Future` 结果。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenario.kt`
  - 场景入口，暴露 `id`、`title`、`expectedAttribution`、`expectedJsonSignals` 和 `run()`。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 新增场景属性，把按钮和 `anr_demo_scenario=thread_pool_exhaustion_wait` 接到场景类。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 新增“线程池耗尽 + 主线程等待”按钮。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增按钮中文文案。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加线程池耗尽等待场景验证步骤、JSON 判断口径、修复建议。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 将场景 11 状态从“待实现”更新为“已实现，待手动验收”，补充本场景批次说明。
- Modify: `README.md`
  - 在“Demo 已覆盖场景”加入新场景，并从后续计划移除线程池耗尽等待。

## Implementation Tasks

### Task 1: 用 TDD 实现线程池耗尽等待场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolWaitWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证线程池耗尽 + 主线程等待场景的元数据和工作负载入口。
 */
class ThreadPoolExhaustionWaitScenarioTest {
    @Test
    fun runExecutesConfiguredThreadPoolWorkload(): Unit {
        val workload: RecordingThreadPoolWaitWorkload = RecordingThreadPoolWaitWorkload()
        val scenario: ThreadPoolExhaustionWaitScenario = ThreadPoolExhaustionWaitScenario(
            workload = workload,
        )

        scenario.run()

        assertEquals(1, workload.runCount)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingThreadPoolWaitWorkload = RecordingThreadPoolWaitWorkload()
        val scenario: ThreadPoolExhaustionWaitScenario = ThreadPoolExhaustionWaitScenario(
            workload = workload,
        )

        assertEquals("thread_pool_exhaustion_wait", scenario.id)
        assertEquals("线程池耗尽 + 主线程等待", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + thread pool wait stack evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ThreadPoolExhaustionWaitScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ThreadPoolExhaustionWorkload.exhaustPoolAndWait"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 java.util.concurrent.FutureTask.get"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingThreadPoolWaitWorkload : ThreadPoolWaitWorkload {
        var runCount: Int = 0

        override fun exhaustPoolAndWait(): Unit {
            runCount += 1
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ThreadPoolExhaustionWaitScenarioTest
```

Expected: FAIL with unresolved references for `ThreadPoolWaitWorkload` and `ThreadPoolExhaustionWaitScenario`.

- [x] **Step 3: Create ThreadPoolWaitWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolWaitWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 主线程等待线程池结果的工作负载。测试中替换为记录器，Demo 运行时制造真实线程池耗尽。
 */
fun interface ThreadPoolWaitWorkload {
    /**
     * 占满线程池 worker，并在调用线程同步等待排队任务结果。
     */
    fun exhaustPoolAndWait(): Unit
}
```

- [x] **Step 4: Create ThreadPoolExhaustionWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.SystemClock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demo 专用线程池耗尽工作负载。
 *
 * 这个类故意让固定线程池的所有 worker 被长任务占满，然后在调用线程等待排队任务结果，
 * 用于制造可观测的主线程等待类 ANR 现场。
 *
 * @param workerCount 固定线程池 worker 数量。
 * @param workerBlockDurationMs worker 被占用的时长。
 */
class ThreadPoolExhaustionWorkload(
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
    private val workerBlockDurationMs: Long = DEFAULT_WORKER_BLOCK_DURATION_MS,
) : ThreadPoolWaitWorkload {
    /**
     * 先占满 worker，再提交一个排队任务并在当前线程等待它完成。此方法由按钮点击在主线程调用。
     */
    override fun exhaustPoolAndWait(): Unit {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            workerCount,
            workerCount,
            KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            DemoThreadFactory(),
        )
        val workersStarted: CountDownLatch = CountDownLatch(workerCount)
        val blockUntilUptimeMs: Long = SystemClock.uptimeMillis() + workerBlockDurationMs
        var queuedFuture: Future<String>? = null
        repeat(workerCount) {
            executor.execute {
                workersStarted.countDown()
                blockWorkerUntil(targetUptimeMs = blockUntilUptimeMs)
            }
        }
        try {
            val allWorkersStarted: Boolean = workersStarted.await(WORKER_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            check(allWorkersStarted) {
                "线程池耗尽等待场景启动失败，worker 未在 ${WORKER_START_TIMEOUT_MS}ms 内全部开始执行"
            }
            queuedFuture = executor.submit(
                Callable {
                    QUEUED_TASK_RESULT
                },
            )
            queuedFuture?.get(workerBlockDurationMs + FUTURE_WAIT_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            queuedFuture?.cancel(true)
            throw IllegalStateException("线程池耗尽等待场景超时，排队任务未恢复执行", error)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun blockWorkerUntil(targetUptimeMs: Long): Unit {
        while (SystemClock.uptimeMillis() < targetUptimeMs) {
            try {
                Thread.sleep(WORKER_SLEEP_SLICE_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private class DemoThreadFactory : ThreadFactory {
        private val nextId: AtomicInteger = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "DemoPoolExhaustedWorker-${nextId.getAndIncrement()}")
        }
    }

    companion object {
        private const val DEFAULT_WORKER_COUNT: Int = 2
        private const val DEFAULT_WORKER_BLOCK_DURATION_MS: Long = 10_000L
        private const val WORKER_START_TIMEOUT_MS: Long = 2_000L
        private const val WORKER_SLEEP_SLICE_MS: Long = 250L
        private const val FUTURE_WAIT_GRACE_MS: Long = 2_000L
        private const val KEEP_ALIVE_MS: Long = 0L
        private const val QUEUED_TASK_RESULT: String = "thread-pool-result"
    }
}
```

- [x] **Step 5: Create ThreadPoolExhaustionWaitScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 在线程池耗尽后让主线程同步等待排队任务结果，用于复现等待类当前消息慢。
 *
 * @param workload 可测试工作负载，真实 Demo 中执行固定线程池耗尽和 Future 等待。
 */
class ThreadPoolExhaustionWaitScenario(
    private val workload: ThreadPoolWaitWorkload = ThreadPoolExhaustionWorkload(),
) : AnrDemoScenario {
    /** 场景唯一标识，后续文档、intent 触发和分析报告用它区分 Demo 类型。 */
    override val id: String = "thread_pool_exhaustion_wait"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "线程池耗尽 + 主线程等待"

    /** 预期归因说明，线程池等待通常先表现为当前消息慢，并依靠等待栈定位根因。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + thread pool wait stack evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 ThreadPoolExhaustionWaitScenario.run",
        "mainThread.stackFrames 包含 ThreadPoolExhaustionWorkload.exhaustPoolAndWait",
        "mainThread.stackFrames 包含 java.util.concurrent.FutureTask.get",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发线程池耗尽并让当前点击消息在主线程等待排队任务结果。
     */
    override fun run(): Unit {
        workload.exhaustPoolAndWait()
    }
}
```

- [x] **Step 6: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ThreadPoolExhaustionWaitScenarioTest
```

Expected: PASS.

- [x] **Step 7: Run app unit test suite**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolWaitWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ThreadPoolExhaustionWaitScenario.kt
git commit -m "新增线程池耗尽等待场景类"
```

### Task 2: 将线程池耗尽等待按钮接入 Demo

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [x] **Step 1: Add import and scenario property**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt` imports and properties so the import block contains:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.ThreadPoolExhaustionWaitScenario
```

Add this property after `ioDatabaseFileBlockScenario`:

```kotlin
    // 线程池耗尽 + 主线程等待场景，按钮点击后占满线程池并等待排队 Future 结果。
    private val threadPoolExhaustionWaitScenario: ThreadPoolExhaustionWaitScenario =
        ThreadPoolExhaustionWaitScenario()
```

- [x] **Step 2: Wire the button click**

In `MainActivity.onCreate`, after the `ioDatabaseFileBlockButton` click listener, add:

```kotlin
        findViewById<Button>(R.id.threadPoolExhaustionWaitButton).setOnClickListener {
            threadPoolExhaustionWaitScenario.run()
        }
```

- [x] **Step 3: Wire adb intent trigger**

Replace `runScenarioFromIntent` in `MainActivity.kt` with:

```kotlin
    // 只暴露 Demo 验收入口，实际执行仍复用按钮背后的同一个场景类。
    private fun runScenarioFromIntent(intent: Intent?): Unit {
        val scenarioId: String = intent?.getStringExtra(EXTRA_DEMO_SCENARIO) ?: return
        Log.w(TAG, "run demo scenario from intent: $scenarioId")
        when (scenarioId) {
            ioDatabaseFileBlockScenario.id -> ioDatabaseFileBlockScenario.run()
            threadPoolExhaustionWaitScenario.id -> threadPoolExhaustionWaitScenario.run()
        }
    }
```

- [x] **Step 4: Add string resource**

Modify `app/src/main/res/values/strings.xml` by adding this string after `demo_io_database_file_block`:

```xml
    <string name="demo_thread_pool_exhaustion_wait">线程池耗尽 + 主线程等待</string>
```

- [x] **Step 5: Add layout button**

Modify `app/src/main/res/layout/activity_main.xml` by adding this button immediately after `ioDatabaseFileBlockButton`:

```xml
        <Button
            android:id="@+id/threadPoolExhaustionWaitButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_thread_pool_exhaustion_wait" />
```

- [x] **Step 6: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:mergeDebugResources
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml
git commit -m "接入线程池耗尽等待 Demo 按钮"
```

### Task 3: 更新使用说明中的线程池耗尽等待验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [x] **Step 1: Add scenario guide**

In `docs-anr/103-ANR监控SDK使用说明.md`, add this section before `### Binder / 跨进程阻塞场景`:

```markdown
### Demo 场景：线程池耗尽 + 主线程等待

这个场景用于验证“线程池所有 worker 被长任务占满，主线程同步等待排队任务结果”导致的当前消息慢。按钮点击后，Demo 会创建固定大小线程池，先用长任务占满所有 worker，再提交一个排队任务并在主线程调用 `Future.get()` 等待结果。

#### 操作步骤

1. 安装 debug 包并打开 Demo App。
2. 点击“线程池耗尽 + 主线程等待”。
3. 等待 logcat 输出 `suspect ANR captured` 和 `ANR report written`。
4. 拉取 `files/anr-monitor-reports` 目录下最新 JSON。
5. 按下面字段顺序分析。

#### JSON 判断口径

| 字段 | 期望 | 含义 |
| --- | --- | --- |
| `event.eventType` | `SUSPECT_ANR` 或系统确认 ANR | SDK 已捕获一次 ANR 现场 |
| `attribution.primary` | `CURRENT_MESSAGE_SLOW` | 主因是当前主线程消息执行过慢 |
| `mainThread.current.wallMs` | 大于 `3000` | 当前消息超过 Demo 疑似 ANR 阈值 |
| `mainThread.stackFrames` | 包含 `ThreadPoolExhaustionWaitScenario.run` | 能定位到 Demo 场景入口 |
| `mainThread.stackFrames` | 包含 `ThreadPoolExhaustionWorkload.exhaustPoolAndWait` | 能定位到线程池耗尽工作负载 |
| `mainThread.stackFrames` | 包含 `FutureTask.get`、`Object.wait` 或 `LockSupport.park` | 说明主线程正在等待排队任务结果 |
| `barrierEvidence.stuckTokens` | 空数组或不是主证据 | 本次不是 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` 或不是主证据 | 本次不是 Binder 跨进程阻塞 |

#### 根因写法

可以写成：

```text
本次 ANR 是线程池耗尽后主线程同步等待任务结果。证据是 attribution.primary=CURRENT_MESSAGE_SLOW，当前消息耗时超过阈值，主线程栈能回溯到 ThreadPoolExhaustionWaitScenario.run 和 ThreadPoolExhaustionWorkload.exhaustPoolAndWait，并停在 FutureTask.get / Object.wait / LockSupport.park 等等待调用。Barrier 和 Binder 证据不是本次主因。
```

不要写成：

```text
线程池导致 ANR。
```

更准确的说法是：业务代码把后台线程池 worker 全部占满后，又在主线程同步等待同一个线程池里的排队任务，形成“主线程等后台、后台无空闲 worker”的等待链。

#### 修复建议

- 不在主线程调用 `Future.get()`、`CountDownLatch.await()`、`CompletableFuture.get()` 或阻塞式协程桥接等待后台结果。
- 不让 UI 关键路径依赖容量很小且可能被长任务占满的共享线程池。
- 将长任务和短任务拆到不同 executor，给用户交互链路保留独立调度资源。
- 使用异步回调、协程 `suspend`、LiveData/Flow 或状态机更新 UI，不阻塞主线程等待结果。
- 对必须等待的任务设置业务超时、降级和取消能力，并把等待链路打点到日志中。
```

- [x] **Step 2: Check markdown format**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [ ] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充线程池耗尽等待使用说明"
```

### Task 4: 更新 Demo 场景矩阵和 README 状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
- Modify: `README.md`

- [x] **Step 1: Update scenario matrix row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the row for scenario 11 with:

```markdown
| 11 | 线程池耗尽后主线程等待 | 点击“线程池耗尽 + 主线程等待”后占满固定线程池 worker，主线程同步等待排队 `Future` 结果 | `CURRENT_MESSAGE_SLOW` + 等待栈证据 | `mainThread.current.wallMs`、`mainThread.stackFrames` 包含 `ThreadPoolExhaustionWaitScenario.run` / `ThreadPoolExhaustionWorkload.exhaustPoolAndWait` / `FutureTask.get` | 已实现，待手动验收 |
```

- [x] **Step 2: Add scenario batch section**

In `docs-anr/105-Demo-ANR场景实现计划.md`, add this section after the IO / 数据库 / 文件阻塞批次:

```markdown
## 第十一批次：线程池耗尽 + 主线程等待

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“线程池耗尽 + 主线程等待”。
4. 等待线程池 worker 被长任务占满，主线程停在排队任务 `Future.get()`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。最后看 `mainThread.stackFrames`，应能看到 `ThreadPoolExhaustionWaitScenario.run`、`ThreadPoolExhaustionWorkload.exhaustPoolAndWait` 和 `FutureTask.get` / `Object.wait` / `LockSupport.park` 等等待帧，说明根因入口是 Demo 线程池耗尽等待场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果主线程栈只看到普通 `Thread.sleep`，说明没有命中线程池等待场景，应检查是否点错按钮或报告取错。
```

- [x] **Step 3: Update README covered scenarios**

In `README.md`, add this row after `IO / 数据库 / 文件阻塞` in “Demo 已覆盖场景”:

```markdown
| 线程池耗尽 + 主线程等待 | 点击“线程池耗尽 + 主线程等待” | `CURRENT_MESSAGE_SLOW`，主线程栈定位到 `ThreadPoolExhaustionWaitScenario.run`、`ThreadPoolExhaustionWorkload` 和 `FutureTask.get` |
```

Replace:

```markdown
后续场景计划包括主线程锁等待、线程池耗尽等待、GC/内存抖动、进程内 CPU 竞争等。
```

with:

```markdown
后续场景计划包括主线程锁等待、GC/内存抖动、进程内 CPU 竞争等。
```

- [x] **Step 4: Check docs whitespace**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md README.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [ ] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md README.md
git commit -m "更新线程池耗尽等待场景文档"
```

### Task 5: 执行线程池耗尽等待最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [ ] **Step 1: Run full local verification**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Install Demo app**

Run:

```bash
adb devices
DEVICE_ID="$(adb devices | awk 'NR == 2 && $2 == "device" { print $1 }')"
test -n "$DEVICE_ID"
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: install succeeds with `Success`.

- [ ] **Step 3: Clear old app reports**

Run:

```bash
adb -s "$DEVICE_ID" shell run-as com.valiantyan.vibeanrmonitoring rm -rf files/anr-monitor-reports
adb -s "$DEVICE_ID" shell run-as com.valiantyan.vibeanrmonitoring mkdir files/anr-monitor-reports
adb -s "$DEVICE_ID" logcat -c
```

Expected: commands exit with code `0`.

- [ ] **Step 4: Trigger scenario by intent**

Run:

```bash
adb -s "$DEVICE_ID" shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity --es anr_demo_scenario thread_pool_exhaustion_wait
```

Expected: logcat contains `run demo scenario from intent: thread_pool_exhaustion_wait`.

- [ ] **Step 5: Wait for ANR report**

Run:

```bash
adb -s "$DEVICE_ID" logcat -d | rg "suspect ANR captured|ANR report written|confirmed ANR report|thread_pool_exhaustion_wait"
```

Expected: at least one `suspect ANR captured` or `ANR report written` line appears.

- [ ] **Step 6: Pull latest JSON**

Run:

```bash
adb -s "$DEVICE_ID" shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
EVENT_FILE="$(adb -s "$DEVICE_ID" shell run-as com.valiantyan.vibeanrmonitoring ls -t files/anr-monitor-reports | head -n 1 | tr -d '\r')"
test -n "$EVENT_FILE"
adb -s "$DEVICE_ID" exec-out run-as com.valiantyan.vibeanrmonitoring cat "files/anr-monitor-reports/$EVENT_FILE" > /tmp/thread-pool-exhaustion-wait.json
```

Expected: JSON file is written to `/tmp/thread-pool-exhaustion-wait.json`.

- [ ] **Step 7: Inspect key JSON fields**

Run:

```bash
rg -n "\"primary\"|\"wallMs\"|ThreadPoolExhaustion|FutureTask|get\\(|Object.wait|LockSupport|\"suspected\"|\"stuckTokens\"" /tmp/thread-pool-exhaustion-wait.json
```

Expected output contains these signals:

```text
"primary":"CURRENT_MESSAGE_SLOW"
"wallMs":...
ThreadPoolExhaustionWaitScenario.run
ThreadPoolExhaustionWorkload.exhaustPoolAndWait
FutureTask.get
"stuckTokens":[]
"suspected":false
```

- [ ] **Step 8: Print values for acceptance record**

Run:

```bash
date '+%Y-%m-%d %H:%M CST'
adb -s "$DEVICE_ID" shell getprop ro.build.version.release
printf 'deviceId=%s\n' "$DEVICE_ID"
printf 'eventFile=%s\n' "$EVENT_FILE"
rg -n "\"eventType\"|\"primary\"|\"wallMs\"|ThreadPoolExhaustionWaitScenario.run|ThreadPoolExhaustionWorkload.exhaustPoolAndWait|FutureTask.get|\"stuckTokens\"|\"suspected\"" /tmp/thread-pool-exhaustion-wait.json
```

Expected: the command prints the date, Android version, device id, event file, and the key JSON fields needed for the acceptance record.

- [ ] **Step 9: Record acceptance result**

Append an acceptance record to the “第十一批次：线程池耗尽 + 主线程等待” section in `docs-anr/105-Demo-ANR场景实现计划.md`. The record must use the real date, device id, Android version, event file and JSON values printed in Step 8. Do not paste sample ids or invented values.

````markdown
### 首次验收记录

验收时间：写入 Step 8 打印的真实时间

验收设备：写入 Step 8 打印的真实设备 id 和 Android 版本

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s 写入真实设备 id install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 写入真实设备 id shell run-as com.valiantyan.vibeanrmonitoring rm -rf files/anr-monitor-reports
adb -s 写入真实设备 id shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity --es anr_demo_scenario thread_pool_exhaustion_wait
adb -s 写入真实设备 id exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/写入真实 event 文件名
```

关键 JSON 字段：

```text
event.eventType = 写入真实 eventType
attribution.primary = CURRENT_MESSAGE_SLOW
mainThread.current.wallMs = 写入真实 wallMs
mainThread.stackFrames contains ThreadPoolExhaustionWaitScenario.run
mainThread.stackFrames contains ThreadPoolExhaustionWorkload.exhaustPoolAndWait
mainThread.stackFrames contains FutureTask.get
barrierEvidence.stuckTokens = []
binderBlock.suspected = false
```

验收结论：线程池耗尽 + 主线程等待场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `CURRENT_MESSAGE_SLOW`，主线程栈能定位到 `FutureTask.get` 和 Demo 线程池等待入口，Barrier 和 Binder 证据均不是本次主因。
````

- [ ] **Step 10: Update scenario matrix status**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace `已实现，待手动验收` in scenario 11 row with:

```markdown
已验收
```

- [ ] **Step 11: Run final checks**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
git diff --check
```

Expected: Gradle tasks PASS, `git diff --check` exits with code `0`.

- [ ] **Step 12: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "完成线程池耗尽等待最终验收"
```

## Review Checklist

- [ ] TDD 测试先失败后通过，且覆盖场景元数据和 `run()` 工作负载调用。
- [ ] Demo 按钮文案是中文，布局中有独立按钮，不复用其他场景按钮。
- [ ] `MainActivity` 支持按钮触发和 `anr_demo_scenario=thread_pool_exhaustion_wait` intent 触发。
- [ ] JSON 判断口径明确区分“线程池耗尽等待”和 CPU 忙等、IO/DB、Binder、Barrier。
- [ ] 使用说明包含新人可直接照做的操作步骤、根因写法和修复建议。
- [ ] 场景矩阵、README 和最终验收记录保持一致。
- [ ] 最终运行 `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 和 `git diff --check`。

## 举一反三问题

1. 如果线上业务不是固定线程池，而是协程 dispatcher、RxJava scheduler 或自研任务队列，JSON 中应该优先看哪些等待栈？
   - 优先看 `mainThread.stackFrames` 是否出现 `FutureTask.get`、`CompletableFuture.get`、`CountDownLatch.await`、`LockSupport.park`、`Object.wait`、`runBlocking` 或业务封装的同步等待方法。
   - 再看这些等待帧上方是否能回溯到业务入口，例如点击回调、页面初始化、Receiver、Service 或 Provider 回调。

2. 如果 `attribution.primary` 只是 `CURRENT_MESSAGE_SLOW`，是否足以说明是线程池耗尽？
   - 不足以单独确认。`CURRENT_MESSAGE_SLOW` 只说明当前主线程消息慢。
   - 必须结合主线程等待栈、Demo 入口栈、线程池 worker 线程名、排除 Barrier/Binder/IO/CPU 忙等后，才能写成“线程池耗尽 + 主线程等待”。

3. 如果 JSON 中看不到后台线程池 worker 栈，是否代表场景失败？
   - 不一定。当前 SDK 主证据是主线程栈和当前消息耗时，后台线程证据属于增强证据。
   - 本计划的验收下限是主线程栈能看到 `ThreadPoolExhaustionWorkload.exhaustPoolAndWait` 和 `FutureTask.get`。

4. 如果主线程栈显示 `nativePollOnce`，这个场景还成立吗？
   - 需要继续回看 `mainThread.current`、`historyMessages` 和 `stackSamples`。
   - 如果当前采集点刚好发生在消息结束后的 Looper 取消息阶段，单次主线程栈可能只有 `nativePollOnce`；此时要看慢消息期间的 `stackSamples` 是否命中过 `FutureTask.get`。

5. 这个场景和 Binder 跨进程阻塞有什么区别？
   - Binder 场景主线程通常命中 `BinderProxy.transact`，`binderBlock.suspected=true`。
   - 线程池耗尽等待场景主线程命中 `FutureTask.get` / `Object.wait` / `LockSupport.park`，并且 `binderBlock.suspected` 不应成为主证据。

6. 修复方向是否只是“把线程池调大”？
   - 不是。调大线程池只能缓解，不能解决主线程同步等待的问题。
   - 根本修复是取消主线程阻塞等待，拆分长短任务线程池，为 UI 链路保留调度资源，并给等待链路增加超时、取消和降级。

## 三轮审核记录

### 第一轮：场景真实性审核

- 结论：场景方向成立，能覆盖 Android 开发中常见的“后台线程池被长任务占满，主线程同步等待排队任务”问题。
- 修正：明确本计划不新增 SDK 专项归因规则，避免把 `CURRENT_MESSAGE_SLOW` 误写成“SDK 已确认线程池耗尽”。
- 修正：验收口径要求同时查看等待栈和排除项，不能只看 `attribution.primary`。

### 第二轮：可执行性审核

- 结论：TDD、按钮接线、intent 触发、文档更新和最终验收拆分合理，可以逐任务执行。
- 修正：`ThreadPoolExhaustionWorkload` 增加 worker 启动检查，避免 worker 未占满时排队任务直接执行，导致场景不稳定。
- 修正：`queuedFuture` 使用可空变量跨 `try/catch/finally` 管理，确保示例代码可编译，并且超时时能取消排队任务。

### 第三轮：新人分析友好度审核

- 结论：使用说明和矩阵能告诉新人“先看当前消息，再看等待栈，再排除 Barrier/Binder/CPU/IO”。
- 修正：补充举一反三问题，覆盖协程、RxJava、自研队列、`nativePollOnce` 表象和 Binder 混淆场景。
- 修正：最终验收步骤要求打印真实设备、真实 event 文件和真实 JSON 字段，避免把示例值当成正式验收结果。
