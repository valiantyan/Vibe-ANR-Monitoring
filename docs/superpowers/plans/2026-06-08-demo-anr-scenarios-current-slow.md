# Demo ANR 场景矩阵与当前慢消息 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中建立全量 ANR 场景实现路线图，并先把“输入事件触发的当前主线程慢消息”做成第一个可测试、可复现、可通过 SDK JSON 定位根因的正式场景。

**Architecture:** 本计划把“所有 ANR 场景”先沉淀为文档化矩阵，避免一次性把十几个场景都塞进 Activity。首个场景使用独立 `scenario` 包封装，Activity 只负责按钮接线；场景逻辑通过可注入阻塞动作做 JVM 单元测试，真实 Demo 运行时再使用 `Thread.sleep()` 阻塞主线程触发 SDK 报告。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`Handler`/`Looper`/`Thread.sleep()`、本地 JSON 报告。

---

## Scope Check

全量 ANR 场景包含输入事件、Broadcast、Service、Provider、Binder、Barrier、IO、锁等待、CPU 竞争、线程池耗尽、GC 抖动等多个相对独立的复现场景。为了降低风险，本计划只完成两件事：

1. 输出全量 Demo ANR 场景矩阵，明确后续实现顺序、触发方式、期望 JSON 证据和验收口径。
2. 实现第一个场景：输入事件触发的当前主线程慢消息。

后续每个场景都应基于本矩阵单独创建计划、测试、实现和验收记录。

## Full Demo Scenario Roadmap

| 顺序 | 场景 | Demo 触发方式 | 期望 SDK 归因 | 关键 JSON 证据 | 实施批次 |
| --- | --- | --- | --- | --- | --- |
| 1 | 输入事件当前慢消息 | 点击按钮后主线程 `sleep(6000)` | `CURRENT_MESSAGE_SLOW` | `mainThread.current.wallMs >= 3000`，业务栈包含 `CurrentSlowInputScenario.run` | 本计划 |
| 2 | 主线程 CPU 忙等 | 点击按钮后主线程 busy loop 6 秒 | `CURRENT_MESSAGE_SLOW` | 当前消息 wall 高，主线程/进程 CPU 证据更高 | 后续单独计划 |
| 3 | 消息风暴 | 一次性投递大量主线程消息并制造排队 | `MESSAGE_STORM` | `pendingQueue.messages` 中同类 target 密集 | 后续单独计划 |
| 4 | Sync Barrier 泄漏 / nativePollOnce | 插入 Sync Barrier 后不移除 | `SYNC_BARRIER_STUCK` | 队头 `isBarrierLike=true`，`barrierEvidence.stuckTokens`，`nativePollOnceRecords` | 已有基础，后续增强验收 |
| 5 | 主线程锁等待 | 子线程持锁，主线程进入 synchronized 等待 | `CURRENT_MESSAGE_SLOW` 或等待类辅因 | 主线程栈出现锁等待业务帧 | 后续单独计划 |
| 6 | BroadcastReceiver 超时 | 按钮发送显式广播，Receiver 主线程阻塞 | Receiver 组件类 ANR 证据 | `systemAnr.anrType=BROADCAST` 或 ActivityThread Receiver 消息 | 后续单独计划 |
| 7 | Service 超时 | 按钮启动阻塞 Service | Service 组件类 ANR 证据 | `systemAnr.anrType=SERVICE` 或 ActivityThread Service 消息 | 后续单独计划 |
| 8 | ContentProvider 阻塞 | 按钮触发 Provider 查询或初始化阻塞 | Provider 组件类 ANR 证据 | Provider 调用栈、组件消息或系统 ANR 信息 | 后续单独计划 |
| 9 | Binder / 跨进程阻塞 | 主进程调用 remote Service，远端阻塞 | `BINDER_BLOCK_SUSPECTED` | `binderBlock.suspected=true`，主线程 Binder 栈 | 后续单独计划 |
| 10 | IO / 数据库 / 文件阻塞 | 主线程读写大文件或执行慢查询 | `CURRENT_MESSAGE_SLOW` | 主线程栈指向 IO/DB 业务入口 | 后续单独计划 |
| 11 | 线程池耗尽 + 主线程等待 | 占满线程池后主线程等待任务结果 | 等待类当前慢消息 | 主线程等待栈、后台线程证据 | 后续单独计划 |
| 12 | GC / 内存抖动 | 大量分配对象制造 GC 压力 | 低置信环境/资源证据 | `environmentSnapshot`、历史消息抖动、系统 GC 日志辅助 | 后续单独计划 |
| 13 | 进程内 CPU 竞争 | 后台线程打满 CPU 后触发输入延迟 | CPU 竞争辅因 | `threadCpu.topThreads` 指向高 CPU 线程 | 后续单独计划 |
| 14 | SharedPreferences 实战样例 | 不作为 SDK 基础需求按钮 | 使用通用证据人工复盘 | 主线程栈/历史消息/环境证据，不新增 SP 专项归因 | 仅文档说明 |

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 负责人类阅读的场景矩阵，说明每个按钮验证什么、看哪些 JSON 字段、后续按什么顺序实现。
- Modify: `app/build.gradle.kts`
  - 给 app 模块接入 JUnit 单元测试依赖。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/AnrDemoScenario.kt`
  - Demo 场景最小接口，统一表达 id、标题、预期归因和运行入口。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BlockingAction.kt`
  - 可注入阻塞动作，测试中记录调用，Demo 中执行真实 `Thread.sleep()`。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenario.kt`
  - 第一个正式 ANR 场景：点击按钮后在主线程阻塞 6 秒。
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenarioTest.kt`
  - JVM 单元测试，验证场景元数据和阻塞时长。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 将 `currentSlowButton` 接到 `CurrentSlowInputScenario.run()`。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“当前慢消息场景”的新人验证步骤和 JSON 判断口径。
- Modify: `docs-anr/README.md`
  - 挂载新场景计划文档入口。

## Implementation Tasks

### Task 1: 写入全量 Demo ANR 场景矩阵文档

**Files:**
- Create: `docs-anr/105-Demo-ANR场景实现计划.md`
- Modify: `docs-anr/README.md`

- [x] **Step 1: 创建场景矩阵文档**

Create `docs-anr/105-Demo-ANR场景实现计划.md` with this content:

```markdown
# Demo ANR 场景实现计划

本文面向 Demo App 开发和手动验收，目标是把常见 Android ANR 场景拆成一个个可复现按钮，用来验证 ANR 监控 SDK 是否能抓到现场，并帮助新人通过 JSON 定位根因。

## 实施原则

- 一个按钮只验证一种主因，避免一份 JSON 里混入多个根因。
- 每个场景都必须说明触发动作、预期归因、关键 JSON 字段和排除项。
- 每次只实现一个场景，实现完成后再进入下一个场景。
- 第五篇 SharedPreferences 文档只作为实战复盘样例，不作为 SDK 或 Demo 基础需求。

## 场景总览

| 顺序 | 场景 | 触发方式 | 预期归因 | 关键 JSON 字段 |
| --- | --- | --- | --- | --- |
| 1 | 输入事件当前慢消息 | 点击按钮后主线程阻塞 6 秒 | `CURRENT_MESSAGE_SLOW` | `mainThread.current.wallMs`、`mainThread.stackFrames` |
| 2 | 主线程 CPU 忙等 | 点击按钮后主线程 busy loop 6 秒 | `CURRENT_MESSAGE_SLOW` | `threadCpu.topThreads`、当前消息 wall/cpu |
| 3 | 消息风暴 | 大量投递同类主线程消息 | `MESSAGE_STORM` | `pendingQueue.messages` 重复 target |
| 4 | Sync Barrier 泄漏 | 插入 Barrier 后不移除 | `SYNC_BARRIER_STUCK` | `pendingQueue.messages[0].isBarrierLike`、`barrierEvidence.stuckTokens`、`nativePollOnceRecords` |
| 5 | 主线程锁等待 | 子线程持锁，主线程等待锁 | 当前慢消息加等待栈证据 | `mainThread.stackFrames` 中锁等待业务帧 |
| 6 | BroadcastReceiver 超时 | 发送显式广播，Receiver 阻塞 | Broadcast 组件超时 | `systemAnr.anrType`、Receiver 相关 ActivityThread 消息 |
| 7 | Service 超时 | 启动阻塞 Service | Service 组件超时 | `systemAnr.anrType`、Service 相关 ActivityThread 消息 |
| 8 | ContentProvider 阻塞 | 查询阻塞 Provider | Provider 组件阻塞 | Provider 调用栈和系统组件证据 |
| 9 | Binder 跨进程阻塞 | 主进程调用远端阻塞服务 | `BINDER_BLOCK_SUSPECTED` | `binderBlock.suspected`、主线程 Binder 栈 |
| 10 | 主线程 IO/数据库阻塞 | 主线程执行慢 IO 或慢查询 | `CURRENT_MESSAGE_SLOW` | IO/DB 业务栈、当前消息耗时 |
| 11 | 线程池耗尽后主线程等待 | 占满线程池后主线程等待结果 | 等待类当前慢消息 | 主线程等待栈、后台线程证据 |
| 12 | GC / 内存抖动 | 大量分配对象制造 GC 压力 | 环境或资源辅因 | `environmentSnapshot`、历史消息抖动 |
| 13 | 进程内 CPU 竞争 | 后台线程打满 CPU | CPU 竞争辅因 | `threadCpu.topThreads`、`checktime.maxDelayMs` |

## 当前批次：输入事件当前慢消息

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“当前消息慢”。
4. 阻塞期间继续点击屏幕，方便系统 Input 超时窗口也出现。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。最后看 `mainThread.stackFrames`，应能看到 `CurrentSlowInputScenario.run`，说明根因入口是 Demo 当前慢消息场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- `pendingQueue.messages` 不应该以队头 Barrier 作为主证据。

## 后续批次顺序

后续按主线程 CPU 忙等、消息风暴、锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

- [x] **Step 2: 在 README 增加入口**

Modify `docs-anr/README.md` by adding this row after the existing `104` row in the document table:

```markdown
| 105 | Demo ANR 场景实现计划 | [105-Demo-ANR场景实现计划.md](./105-Demo-ANR场景实现计划.md) | 已规划 Demo 全量 ANR 场景和逐个实现顺序 |
```

- [x] **Step 3: 检查文档格式**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md docs-anr/README.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 4: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md docs-anr/README.md
git commit -m "新增 Demo ANR 场景实现计划"
```

### Task 2: 接入 app 单元测试基础

**Files:**
- Modify: `app/build.gradle.kts`

- [x] **Step 1: 确认当前 app 单元测试任务可执行**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS. 如果当前没有测试用例，Gradle 仍应成功完成 `testDebugUnitTest`。

- [x] **Step 2: 给 app 模块加入 JUnit 依赖**

Modify `app/build.gradle.kts` dependencies block to this exact content:

```kotlin
dependencies {
    implementation(project(":anr-monitor-sdk"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
}
```

- [x] **Step 3: 验证测试任务仍然通过**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "接入 Demo App 单元测试依赖"
```

### Task 3: 用 TDD 实现当前慢消息场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/AnrDemoScenario.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BlockingAction.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证当前慢消息场景的元数据和阻塞动作，避免按钮只触发匿名 sleep 而缺少可读根因入口。
 */
class CurrentSlowInputScenarioTest {
    @Test
    fun runBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val scenario: CurrentSlowInputScenario = CurrentSlowInputScenario(
            blockingAction = blockingAction,
            durationMs = 4_321L,
        )
        scenario.run()
        assertEquals(listOf(4_321L), blockingAction.blockedDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val scenario: CurrentSlowInputScenario = CurrentSlowInputScenario(
            blockingAction = blockingAction,
        )
        assertEquals("current_slow_input", scenario.id)
        assertEquals("当前消息慢", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 CurrentSlowInputScenario.run"))
    }

    private class RecordingBlockingAction : BlockingAction {
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun block(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenarioTest
```

Expected: FAIL with unresolved references for `CurrentSlowInputScenario` and `BlockingAction`.

- [x] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/AnrDemoScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * Demo ANR 场景的最小契约，Activity 只负责触发，具体复现逻辑留在场景类中。
 */
interface AnrDemoScenario {
    val id: String
    val title: String
    val expectedAttribution: String
    val expectedJsonSignals: List<String>

    /**
     * 执行复现场景。此方法通常在主线程由按钮点击触发。
     */
    fun run(): Unit
}
```

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BlockingAction.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 可注入阻塞动作，测试中记录调用，Demo 运行时执行真实阻塞。
 */
fun interface BlockingAction {
    /**
     * 阻塞当前线程指定时间。
     *
     * @param durationMs 阻塞时长，单位毫秒。
     */
    fun block(durationMs: Long): Unit
}
```

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 输入事件触发的当前慢消息场景，点击按钮后故意阻塞主线程以验证 CURRENT_MESSAGE_SLOW 归因。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs 主线程阻塞时长，默认超过 Demo SDK 的 3000ms 疑似 ANR 阈值。
 */
class CurrentSlowInputScenario(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "current_slow_input"
    override val title: String = "当前消息慢"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 CurrentSlowInputScenario.run",
    )

    /**
     * 在按钮点击消息中阻塞主线程，制造最基础的输入事件无响应窗口。
     */
    override fun run(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 6 秒，稳定超过 debug 配置中的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 6_000L
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenarioTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/AnrDemoScenario.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BlockingAction.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CurrentSlowInputScenario.kt
git commit -m "实现当前慢消息 Demo 场景"
```

### Task 4: 将当前慢消息按钮接入场景类

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Modify MainActivity**

Replace the full content of `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt` with:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenario

/**
 * ANR SDK 示例入口，提供全量验收所需的主线程慢消息、消息风暴、忙等和等待类场景。
 */
class MainActivity : AppCompatActivity() {
    // 主线程 Handler，用于构造大量 pending message 的验收场景。
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    // 当前慢消息场景，用独立类承载根因入口，便于 JSON 栈中直接定位。
    private val currentSlowInputScenario: CurrentSlowInputScenario = CurrentSlowInputScenario()

    // Sync Barrier 泄漏场景，单独封装反射和 token 记录逻辑。
    private val syncBarrierLeakScenario: SyncBarrierLeakScenario by lazy {
        SyncBarrierLeakScenario(context = this)
    }

    /**
     * 初始化 demo 按钮，让手动验收可以直接触发不同 ANR 证据路径。
     */
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.currentSlowButton).setOnClickListener {
            currentSlowInputScenario.run()
        }
        findViewById<Button>(R.id.messageStormButton).setOnClickListener {
            postMessageStorm()
        }
        findViewById<Button>(R.id.currentBusyButton).setOnClickListener {
            runBusyLoop()
        }
        findViewById<Button>(R.id.binderLikeButton).setOnClickListener {
            waitBinderLikeLock()
        }
        findViewById<Button>(R.id.syncBarrierLeakButton).setOnClickListener {
            runSyncBarrierLeak()
        }
    }

    // 快速投递大量主线程消息，用于验证 pending 队列和历史消息窗口。
    private fun postMessageStorm(): Unit {
        repeat(times = 2_000) { index: Int ->
            mainHandler.post {
                val ignoredValue: Int = index * index
                ignoredValue.toString()
            }
        }
        Thread.sleep(6_000L)
    }

    // 主线程持续忙等，用于验收当前消息慢且 CPU 占用较高的场景。
    private fun runBusyLoop(): Unit {
        val endAt: Long = System.currentTimeMillis() + 6_000L
        var ignoredValue: Double = 0.0
        while (System.currentTimeMillis() < endAt) {
            ignoredValue += Math.sqrt(42.0)
        }
        ignoredValue.toString()
    }

    // 模拟同步跨进程调用中的等待窗口，用于手动观察等待类主线程栈证据。
    private fun waitBinderLikeLock(): Unit {
        val lock: Any = Any()
        synchronized(lock) {
            Thread.sleep(6_000L)
        }
    }

    // 泄漏 Sync Barrier，用于验证 nativePollOnce 表象背后的队列根因。
    private fun runSyncBarrierLeak(): Unit {
        syncBarrierLeakScenario.run()
    }
}
```

- [x] **Step 2: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenarioTest
```

Expected: PASS.

- [x] **Step 3: Run app compile check**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: PASS.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入当前慢消息场景按钮"
```

### Task 5: 更新使用说明中的首个场景验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [ ] **Step 1: Add current slow scenario instructions**

In `docs-anr/103-ANR监控SDK使用说明.md`, add this subsection under the Demo validation section:

````markdown
### 当前消息慢场景

这个场景用于验证最基础的输入事件无响应：用户点击按钮后，当前主线程消息被业务代码阻塞超过疑似 ANR 阈值。

操作步骤：

1. 安装并打开 debug Demo App。
2. 点击“当前消息慢”。
3. 等待 logcat 出现 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON 报告。
5. 先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。
6. 再看 `mainThread.current.wallMs`，预期大于 `3000`。
7. 最后看 `mainThread.stackFrames`，预期包含 `CurrentSlowInputScenario.run`。

新人分析结论可以这样写：

```text
本次报告是 Demo 当前慢消息场景触发的疑似 ANR。当前主线程消息执行时间超过 3000ms，主线程栈包含 CurrentSlowInputScenario.run，说明按钮点击消息被业务代码主动阻塞。Barrier 和 Binder 证据不构成本次主因，因此根因是主线程当前消息执行耗时过长。
```
````

- [ ] **Step 2: 检查文档格式**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [ ] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充当前慢消息场景验证说明"
```

### Task 6: 执行首个场景最终验收

**Files:**
- No production files modified.

- [ ] **Step 1: Run all relevant automated checks**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Install debug app**

Run:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `adb devices` shows the intended device, and install prints `Success`.

- [ ] **Step 3: Clear logcat**

Run:

```bash
adb logcat -c
```

Expected: command exits with code `0`.

- [ ] **Step 4: Manually trigger scenario**

Open the app on the device and tap `当前消息慢`. During the 6 second block, tap the screen again once or twice to make the input timeout window easier to observe.

Expected logcat command:

```bash
adb logcat -s VibeAnrApplication AnrMonitor
```

Expected logcat lines:

```text
W VibeAnrApplication: suspect ANR captured: <event-id>
W VibeAnrApplication: ANR report written: <event-id>
```

- [ ] **Step 5: Pull JSON reports**

Run:

```bash
mkdir -p manual-anr-reports
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > manual-anr-reports/<eventId>.json
```

Expected: at least one JSON report is available in `files/anr-monitor-reports`. Replace `<eventId>` with the newest file name printed by the `ls` command. The SDK writes reports to the app private `filesDir`, so do not use an external storage directory as the primary path.

- [ ] **Step 6: Validate JSON root cause**

Open the latest JSON report and verify these values:

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
mainThread.current.wallMs >= 3000
mainThread.stackFrames contains CurrentSlowInputScenario.run
binderBlock.suspected = false
barrierEvidence.stuckTokens is empty or not the primary evidence
```

Expected human conclusion:

```text
当前慢消息场景验收通过：SDK 捕获到疑似 ANR，归因为 CURRENT_MESSAGE_SLOW，主线程栈能定位到 CurrentSlowInputScenario.run，说明 JSON 可以直接解释根因是按钮点击消息阻塞主线程。
```

- [ ] **Step 7: Commit manual acceptance record if docs changed**

If manual validation notes are added to docs, commit them:

```bash
git add docs-anr/103-ANR监控SDK使用说明.md docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录当前慢消息场景验收结果"
```

If no docs changed, skip the commit and leave the working tree clean.

## Self-Review

- Spec coverage: 覆盖了用户要求的“所有 ANR 场景实现计划”和“先实现一个 ANR 场景”；后续场景明确拆成独立批次。
- Placeholder scan: 本计划没有使用空占位、延期实现、笼统异常处理或无内容占位步骤。
- Type consistency: `BlockingAction`、`AnrDemoScenario`、`CurrentSlowInputScenario`、`CurrentSlowInputScenarioTest` 的包名、类名、方法名在所有任务中保持一致。
- Testability: 首个场景的阻塞动作通过 `BlockingAction` 注入，可在 JVM 单元测试中验证时长和元数据；真实 ANR 触发通过手动验收验证 JSON。
- Scope control: 本计划不改 SDK 归因规则，不新增复杂 UI，不把 SharedPreferences 当成基础需求场景。

## Three-Round Review

### Round 1: 范围和场景覆盖

结论：通过。计划覆盖了输入事件、CPU 忙等、消息风暴、Sync Barrier、锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争和 SharedPreferences 实战样例说明，并明确本批次只实现“输入事件当前慢消息”，避免一次实现过多场景。

### Round 2: 可执行性和 TDD

结论：通过。每个任务都有明确文件、步骤、命令、预期结果和提交点；首个场景先写失败测试，再实现 `BlockingAction`、`AnrDemoScenario`、`CurrentSlowInputScenario`，符合 TDD 执行顺序。

### Round 3: 协议字段和手动验收

结论：已修正后通过。审核发现计划中曾使用错误的归因字段名和外部存储报告路径，不符合当前 SDK JSON 编码和本地写入实现；已修正为 `attribution.primary` 和 `run-as ... files/anr-monitor-reports` 私有目录读取路径。
