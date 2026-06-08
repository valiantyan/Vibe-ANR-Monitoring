# Demo 消息风暴场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“消息风暴”做成第三个可测试、可复现、可通过 SDK JSON 定位“主线程 Pending 队列同类消息堆积”的正式 ANR 场景。

**Architecture:** 当前 `MainActivity` 已经有 `messageStormButton` 和内联 `postMessageStorm()`，但逻辑混在 Activity 中，不利于测试和 JSON 栈定位。本计划把消息风暴下沉到独立 `MessageStormScenario`，通过可注入 `MessageStormPoster` 和现有 `BlockingAction` 做 JVM 单元测试；真实 Demo 运行时先通过专属 `MessageStormHandler` 向主线程投递大量同类 `StormRunnable`，再短时间占住当前点击消息，让 SDK 在疑似 ANR 快照中看到大量重复 Pending 消息并优先归因为 `MESSAGE_STORM`。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`Handler(Looper.getMainLooper())`、`Runnable`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 3 的“消息风暴”场景。

本计划不实现 Sync Barrier 泄漏、主线程锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。

本场景和前两个场景的区别：

- 输入事件当前慢消息：主因是当前点击消息自己阻塞，关键证据是 `mainThread.current.wallMs` 和 `CurrentSlowInputScenario.run`。
- 主线程 CPU 忙等：主因是当前点击消息持续消耗 CPU，关键证据是 `mainThread.current.cpuMs`、`threadCpu.topThreads` 和 `MainThreadCpuBusyScenario.run`。
- 消息风暴：主因是主线程 Pending 队列里堆积大量同类消息，关键证据是 `attribution.primary=MESSAGE_STORM`、`pendingQueue.messages` 中重复 `MessageStormHandler` 或 `StormRunnable` 数量超过阈值。即使当前点击消息也有短时间阻塞，SDK 归因规则应优先输出消息风暴。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormPoster.kt`
  - 可注入消息投递器，测试中记录 callback，Demo 中委托专属 `MessageStormHandler.post()`。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenario.kt`
  - 第三个正式 ANR 场景：点击按钮后向主线程投递大量同类 `Runnable`，并阻塞当前点击消息制造 Pending 队列快照窗口。
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenarioTest.kt`
  - JVM 单元测试，验证场景元数据、投递数量、阻塞时长和重复 callback 类型。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 将 `messageStormButton` 接到 `MessageStormScenario.run()`，移除 Activity 内联 `postMessageStorm()` 和 `mainHandler` 字段。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“消息风暴场景”的新人验证步骤和 JSON 判断口径。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 增加第三批次“消息风暴”的触发步骤、JSON 读取口径、排除项和验收记录占位。

## Implementation Tasks

### Task 1: 用 TDD 实现消息风暴场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormPoster.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证消息风暴场景会投递大量同类主线程消息，并保留清晰的 JSON 根因入口。
 */
class MessageStormScenarioTest {
    @Test
    fun runPostsConfiguredStormCallbacksAndBlocksCurrentMessage(): Unit {
        val poster: RecordingMessageStormPoster = RecordingMessageStormPoster()
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val scenario: MessageStormScenario = MessageStormScenario(
            poster = poster,
            blockingAction = blockingAction,
            messageCount = 24,
            blockDurationMs = 4_321L,
        )

        scenario.run()

        assertEquals(24, poster.callbacks.size)
        assertEquals(listOf(4_321L), blockingAction.blockedDurations)
        val firstCallbackClass: Class<out Runnable> = poster.callbacks.first().javaClass
        poster.callbacks.forEach { callback: Runnable ->
            assertSame(firstCallbackClass, callback.javaClass)
        }
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val scenario: MessageStormScenario = MessageStormScenario(
            poster = RecordingMessageStormPoster(),
            blockingAction = RecordingBlockingAction(),
        )

        assertEquals("message_storm", scenario.id)
        assertEquals("消息风暴", scenario.title)
        assertEquals("MESSAGE_STORM", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("attribution.primary = MESSAGE_STORM"))
        assertTrue(scenario.expectedJsonSignals.contains("pendingQueue.messages 中同类 MessageStormHandler 或 StormRunnable 数量 >= 20"))
        assertTrue(scenario.expectedJsonSignals.contains("attribution.evidence 包含 pending repeated target count"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 为空或不是主因"))
    }

    private class RecordingMessageStormPoster : MessageStormPoster {
        val callbacks: MutableList<Runnable> = mutableListOf()

        override fun post(callback: Runnable): Unit {
            callbacks.add(callback)
        }
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
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenarioTest
```

Expected: FAIL with unresolved references for `MessageStormScenario` and `MessageStormPoster`.

- [x] **Step 3: Create MessageStormPoster**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormPoster.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 可注入主线程消息投递器，测试中记录 callback，Demo 运行时委托专属 Handler.post。
 */
fun interface MessageStormPoster {
    /**
     * 投递一个待执行的主线程 callback。
     *
     * @param callback 需要进入主线程 Pending 队列的任务。
     */
    fun post(callback: Runnable): Unit
}
```

- [x] **Step 4: Create MessageStormScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Handler
import android.os.Looper

/**
 * 主线程消息风暴场景，点击按钮后投递大量同类消息并短时间占住当前点击消息。
 *
 * @param poster 主线程消息投递器，测试中可替换为记录器。
 * @param blockingAction 当前点击消息的阻塞动作，用于给 Watchdog 和 Pending 快照留下采集窗口。
 * @param messageCount 投递的同类消息数量，默认超过 SDK 消息风暴阈值 20。
 * @param blockDurationMs 当前点击消息阻塞时长，默认超过 Demo SDK 的 3000ms 疑似 ANR 阈值。
 */
class MessageStormScenario(
    private val poster: MessageStormPoster = HandlerMessageStormPoster(),
    private val blockingAction: BlockingAction = BlockingAction { durationMs: Long ->
        Thread.sleep(durationMs)
    },
    private val messageCount: Int = DEFAULT_MESSAGE_COUNT,
    private val blockDurationMs: Long = DEFAULT_BLOCK_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "message_storm"
    override val title: String = "消息风暴"
    override val expectedAttribution: String = "MESSAGE_STORM"
    override val expectedJsonSignals: List<String> = listOf(
        "attribution.primary = MESSAGE_STORM",
        "pendingQueue.messages 中同类 MessageStormHandler 或 StormRunnable 数量 >= 20",
        "attribution.evidence 包含 pending repeated target count",
        "barrierEvidence.stuckTokens 为空或不是主因",
    )

    /**
     * 先投递大量同类 callback，再阻塞当前点击消息，让 SDK 采集到同类 Pending 消息堆积。
     */
    override fun run(): Unit {
        val callback: Runnable = StormRunnable()
        repeat(times = messageCount) {
            poster.post(callback = callback)
        }
        blockingAction.block(durationMs = blockDurationMs)
    }

    private class StormRunnable : Runnable {
        override fun run(): Unit {
            val marker: String = "message_storm_callback"
            marker.length.toString()
        }
    }

    private class HandlerMessageStormPoster : MessageStormPoster {
        private val handler: Handler = MessageStormHandler(looper = Looper.getMainLooper())

        override fun post(callback: Runnable): Unit {
            handler.post(callback)
        }
    }

    private class MessageStormHandler(looper: Looper) : Handler(looper)

    private companion object {
        /**
         * 默认投递 80 条，明显超过 SDK 默认 `messageStormCount=20`，同时避免 Demo 过重。
         */
        private const val DEFAULT_MESSAGE_COUNT: Int = 80

        /**
         * 默认阻塞 6 秒，稳定超过 debug 配置中的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_BLOCK_DURATION_MS: Long = 6_000L
    }
}
```

- [x] **Step 5: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenarioTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormPoster.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MessageStormScenario.kt
git commit -m "实现消息风暴Demo场景"
```

### Task 2: 将消息风暴按钮接入场景类

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Update imports**

In `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`, remove these imports:

```kotlin
import android.os.Handler
import android.os.Looper
```

Add this import after `MainThreadCpuBusyScenario`:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenario
```

- [x] **Step 2: Remove mainHandler field**

Remove this property from `MainActivity`:

```kotlin
    // 主线程 Handler，用于构造大量 pending message 的验收场景。
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
```

- [x] **Step 3: Add message storm scenario field**

Add this property immediately after `currentSlowInputScenario`:

```kotlin
    // 消息风暴场景，用独立类投递大量同类 Pending 消息，便于 JSON 归因为 MESSAGE_STORM。
    private val messageStormScenario: MessageStormScenario = MessageStormScenario()
```

The surrounding code should become:

```kotlin
    // 当前慢消息场景，用独立类承载根因入口，便于 JSON 栈中直接定位。
    private val currentSlowInputScenario: CurrentSlowInputScenario = CurrentSlowInputScenario()

    // 消息风暴场景，用独立类投递大量同类 Pending 消息，便于 JSON 归因为 MESSAGE_STORM。
    private val messageStormScenario: MessageStormScenario = MessageStormScenario()

    // 当前消息 CPU 忙等场景，用独立类承载根因入口，便于和 Thread.sleep 等待类场景区分。
    private val mainThreadCpuBusyScenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario()
```

- [x] **Step 4: Wire messageStormButton**

Replace this block in `onCreate`:

```kotlin
        findViewById<Button>(R.id.messageStormButton).setOnClickListener {
            postMessageStorm()
        }
```

with:

```kotlin
        findViewById<Button>(R.id.messageStormButton).setOnClickListener {
            messageStormScenario.run()
        }
```

- [x] **Step 5: Remove inline postMessageStorm**

Remove this method from `MainActivity`:

```kotlin
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
```

- [x] **Step 6: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenarioTest
```

Expected: PASS.

- [x] **Step 7: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入消息风暴按钮"
```

### Task 3: 更新使用说明中的消息风暴验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [ ] **Step 1: Add message storm verification section**

In `docs-anr/103-ANR监控SDK使用说明.md`, find the Demo 场景验证 section that already includes “当前消息慢” and “当前消息忙等”. Add this new subsection after “当前消息忙等”:

```markdown
### 消息风暴场景

用于验证“主线程 Pending 队列中堆积大量同类消息”的定位能力。这个场景不是看单条业务代码执行慢，而是看队列里是否出现大量重复 `MessageStormHandler` 或 `StormRunnable`。

操作步骤：

1. 安装并打开 Demo App。
2. 点击“消息风暴”。
3. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON 报告。

重点看这些字段：

| 字段 | 预期 | 怎么理解 |
| --- | --- | --- |
| `attribution.primary` | `MESSAGE_STORM` | SDK 判断本次主因是消息风暴 |
| `attribution.evidence` | 包含 `pending repeated target count=...` | Pending 队列里同类消息数量已经超过阈值 |
| `pendingQueue.messages` | 多条消息的 `targetClass` 包含 `MessageStormHandler`，或 `callbackClass` 包含 `StormRunnable` | 证明主线程不是卡在一个孤立任务，而是被重复消息压住 |
| `mainThread.current.wallMs` | 可能超过 3000ms | Demo 会短时间占住点击消息，目的是给 Pending 快照留下采集窗口 |
| `barrierEvidence.stuckTokens` | 空数组或不是主因 | 说明本次不是 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` | 说明本次不是 Binder 或跨进程等待 |

定位结论写法：

```text
本次 ANR 主因是消息风暴。证据是 attribution.primary=MESSAGE_STORM，pendingQueue.messages 中存在大量 MessageStormHandler/StormRunnable，attribution.evidence 给出 pending repeated target count。当前消息耗时只是 Demo 为了保留采集窗口制造的阻塞，不是本次根因。
```

修复方向：

- 合并重复 `Handler.post` / `sendMessage`，同一类刷新任务只保留最后一次。
- 在投递前使用“是否已在队列中”的状态位做去重。
- 对高频事件增加防抖或节流，避免每次输入、滚动、数据变化都投递主线程任务。
- 页面销毁、数据源切换或请求取消时，及时 `removeCallbacks` / `removeMessages` 清理过期任务。
```

- [ ] **Step 2: Verify markdown mentions MESSAGE_STORM**

Run:

```bash
rg -n "消息风暴场景|MESSAGE_STORM|pending repeated target count" docs-anr/103-ANR监控SDK使用说明.md
```

Expected: output includes the new subsection title, the attribution code, and the evidence phrase.

- [ ] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充消息风暴验证说明"
```

### Task 4: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [ ] **Step 1: Update scenario table row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the row for scenario 3:

```markdown
| 3 | 消息风暴 | 大量投递同类主线程消息 | `MESSAGE_STORM` | `pendingQueue.messages` 重复 target |
```

with:

```markdown
| 3 | 消息风暴 | 点击“消息风暴”后投递大量同类主线程消息 | `MESSAGE_STORM` | `attribution.evidence`、`pendingQueue.messages` 重复 `MessageStormHandler` / `StormRunnable`、`MessageStormScenario.run` |
```

- [ ] **Step 2: Add third batch section**

Add this section immediately before `## 后续批次顺序`:

```markdown
## 第三批次：消息风暴

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“消息风暴”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `MESSAGE_STORM`。再看 `attribution.evidence`，应包含 `pending repeated target count=...`。接着打开 `pendingQueue.messages`，确认多条 Pending 消息的 `targetClass` 包含 `MessageStormHandler`，或 `callbackClass` 包含 `StormRunnable`。最后看 `mainThread.stackFrames`，应能看到 `MessageStormScenario.run`，说明本次消息风暴来自 Demo 按钮场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果 `attribution.primary` 变成 `CURRENT_MESSAGE_SLOW`，优先检查 `pendingQueue.available` 是否为 true，以及 `pendingQueue.messages` 中是否真的采到了超过 20 条同类消息。

### 首次验收记录

验收时间：待执行

验收设备：待执行

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell input tap <message-storm-button-x> <message-storm-button-y>
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = MESSAGE_STORM
attribution.evidence contains pending repeated target count
pendingQueue.messages contains repeated MessageStormHandler or StormRunnable
mainThread.stackFrames contains MessageStormScenario.run
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：待执行。
```

- [ ] **Step 3: Update later batch order**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace:

```markdown
后续按消息风暴、锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

with:

```markdown
后续按锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

- [ ] **Step 4: Verify documentation**

Run:

```bash
rg -n "第三批次：消息风暴|MessageStormScenario|MESSAGE_STORM|后续按锁等待" docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: output includes all four phrases.

- [ ] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新消息风暴场景矩阵"
```

### Task 5: 执行消息风暴最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
- Optional Create: `SDK案例分析/消息风暴/json/<event-id>.json`
- Optional Create: `SDK案例分析/消息风暴/分析结果/<event-id>-ANR根因分析.html`

- [ ] **Step 1: Run full local validation**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Install debug app**

Run:

```bash
adb devices
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: install prints `Success`.

- [ ] **Step 3: Clear old logs and trigger message storm**

Run:

```bash
adb -s <device-id> logcat -c
adb -s <device-id> shell input tap <message-storm-button-x> <message-storm-button-y>
```

Expected: Demo App freezes briefly after tapping “消息风暴”.

- [ ] **Step 4: Read event id from logcat**

Run:

```bash
adb -s <device-id> logcat -d -s VibeAnrApplication AnrMonitor
```

Expected output includes lines like:

```text
W VibeAnrApplication: suspect ANR captured: <event-id>
W VibeAnrApplication: ANR report written: <event-id>
```

- [ ] **Step 5: Pull JSON report**

Run:

```bash
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json > message-storm-<event-id>.json
```

Expected: local `message-storm-<event-id>.json` exists and is not empty.

- [ ] **Step 6: Inspect JSON evidence**

Run:

```bash
rg -n "\"primary\"|MESSAGE_STORM|pending repeated target count|MessageStormScenario|\"pendingQueue\"|\"targetClass\"|\"callbackClass\"|\"binderBlock\"|\"barrierEvidence\"" message-storm-<event-id>.json
```

Expected output contains:

```text
"primary": "MESSAGE_STORM"
"pending repeated target count=..."
"MessageStormScenario.run"
"binderBlock"
"barrierEvidence"
```

Then manually confirm:

```text
attribution.primary = MESSAGE_STORM
pendingQueue.messages contains at least 20 repeated MessageStormHandler or StormRunnable values
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

- [ ] **Step 7: Update acceptance record**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the “第三批次：消息风暴” acceptance placeholders with actual values:

```markdown
验收时间：2026-06-08 HH:mm CST

验收设备：`<device-id>`
```

Replace:

```markdown
验收结论：待执行。
```

with:

```markdown
验收结论：消息风暴场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `MESSAGE_STORM`，`attribution.evidence` 给出 repeated target count，`pendingQueue.messages` 能看到大量 `MessageStormHandler` 或 `StormRunnable` 消息，Binder 和 Barrier 证据均不是本次主因，因此根因可以明确写为“按钮点击后向主线程投递大量重复消息，导致队列拥塞和输入响应延迟”。
```

- [ ] **Step 8: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录消息风暴场景验收结果"
```

## 举一反三提问

1. 真实业务里哪些代码最容易制造消息风暴？
   - 高频 `Handler.post`、滚动刷新、输入联想、播放器进度更新、动画回调、轮询任务、重复网络回调上屏都可能制造大量同类主线程消息。

2. 为什么消息风暴不能只看 `mainThread.current.wallMs`？
   - `mainThread.current.wallMs` 只能说明当前消息慢，不能说明队列后面是否堆了大量重复消息。消息风暴的核心证据必须来自 `pendingQueue.messages` 和 `attribution.evidence`。

3. 为什么 Demo 要短时间阻塞当前点击消息？
   - Watchdog 需要一个采集窗口读取 Pending 队列。如果不阻塞当前点击消息，大量投递的短消息可能很快被主线程消费完，报告里反而看不到队列堆积。

4. 为什么计划要求专属 `MessageStormHandler` / `StormRunnable`？
   - SDK 的重复统计会优先使用 `targetClass`，普通 `Handler` 在 JSON 中太泛，不利于新人判断来源。专属类名能让 `pendingQueue.messages` 更像真实业务入口。

5. 如果最终 JSON 归因为 `CURRENT_MESSAGE_SLOW`，应该先怀疑什么？
   - 先看 `pendingQueue.available` 是否为 true，再看 `pendingQueue.messages` 是否采到超过 20 条同类消息。如果 Pending 证据为空或数量不足，本次复现没有形成可被 SDK 判断的消息风暴。

6. 如果 `pendingQueue.messages` 中重复的是 `android.os.Handler` 而不是 `MessageStormHandler`，说明什么？
   - 说明实现没有使用专属 Handler，或 JSON 反射只采到了父类信息。需要优先检查 `HandlerMessageStormPoster` 是否按计划创建 `MessageStormHandler`。

7. 线上修复消息风暴时，为什么只延长超时时间没有意义？
   - 消息风暴的本质是重复投递和缺少合并，延长阈值只会让卡顿晚一点被发现。正确方向是去重、防抖、节流、合并刷新和及时清理过期消息。

8. 这个 Demo 场景能不能代表所有队列堆积问题？
   - 不能。它代表“大量同类主线程消息重复投递”的队列堆积；锁等待、Barrier、Binder、IO、线程池等待都需要独立场景和证据链。

## 三轮审核

### 第一轮：需求覆盖审核

审核结论：通过。计划只覆盖 `docs-anr/105-Demo-ANR场景实现计划.md` 中的“消息风暴”场景，没有把 Barrier、Binder、锁等待或组件超时混入同一个批次，符合“一个 ANR 场景一个计划、一个按钮只验证一种主因”的执行原则。

本轮修正点：

- 明确消息风暴与前两个场景的差异：它不是只看当前消息慢，而是看 Pending 队列中同类消息堆积。
- 明确 `MESSAGE_STORM` 的关键证据来自 `pendingQueue.messages` 和 `attribution.evidence`。

### 第二轮：工程可执行性审核

审核结论：修正后通过。原计划使用普通 `Handler.post()`，虽然可能触发 `MESSAGE_STORM`，但 JSON 里容易只出现泛化的 `android.os.Handler`，新人读报告时不够直观。计划已改为使用专属 `MessageStormHandler` 和同类 `StormRunnable`，让 Pending 队列证据更清楚。

本轮修正点：

- `HandlerMessageStormPoster` 改为持有 `MessageStormHandler(looper = Looper.getMainLooper())`。
- 测试和文档的 JSON 预期从“重复 target/callback”收敛为“重复 `MessageStormHandler` 或 `StormRunnable`”。
- 保留 `BlockingAction` 注入，让测试不需要真实 sleep，也能验证消息投递数量和阻塞参数。

### 第三轮：验收和评审表达审核

审核结论：通过。计划已经给出安装、点击、logcat、拉取 JSON、字段检查和验收记录更新步骤；也说明了当 `attribution.primary` 不是 `MESSAGE_STORM` 时应该先排查 Pending 队列可用性和同类消息数量，不会让执行者只盯着归因码。

本轮关注点：

- 文档面向新人时，应按“先看归因，再看 Pending 队列，再排除 Barrier/Binder，最后写修复方向”的顺序讲。
- 验收失败时不要直接修改 SDK 归因阈值，应先确认 Demo 是否真的产生了超过阈值的同类 Pending 消息。
- 后续如果为该场景输出案例 HTML，应把根因写成“重复主线程消息投递导致队列拥塞”，不要写成“当前点击消息 sleep 导致 ANR”。

## Self-Review

### 1. Spec coverage

- 覆盖 `docs-anr/105-Demo-ANR场景实现计划.md` 中“消息风暴”场景。
- 覆盖 Demo 按钮接入、独立场景类、TDD 单元测试、使用说明、场景矩阵和最终验收记录。
- 明确本计划不覆盖 Barrier、Binder、锁等待、Broadcast、Service、Provider、IO、线程池、GC、CPU 竞争。

### 2. Placeholder scan

- 计划中的代码、命令、路径和文档片段都给出完整内容，没有留下需要开发者自行补写的实现段落。
- 验收记录中的 `<device-id>`、`<event-id>`、`<message-storm-button-x>`、`<message-storm-button-y>` 是执行时必须由设备和报告实际值替换的命令参数，不是实现缺口。

### 3. Type consistency

- `MessageStormScenario`、`MessageStormPoster`、`BlockingAction`、`AnrDemoScenario` 的类型和方法签名在测试与实现中一致。
- `expectedAttribution` 使用 `MESSAGE_STORM`，与 SDK 归因码一致。
- 文档中的 `pendingQueue.messages`、`targetClass`、`callbackClass`、`attribution.evidence` 与当前 JSON 字段命名一致，且计划要求使用专属 `MessageStormHandler` / `StormRunnable` 提升人工识别度。
