# Demo 主线程 CPU 忙等场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“主线程 CPU 忙等”做成第二个可测试、可复现、可通过 SDK JSON 区分“等待阻塞”和“CPU 忙等”的正式 ANR 场景。

**Architecture:** 当前 `MainActivity` 已经有 `currentBusyButton` 和匿名 `runBusyLoop()`，本计划把 busy loop 下沉到独立 `MainThreadCpuBusyScenario`，让 JSON 栈能直接定位到场景类入口。场景类通过可注入 `CpuBusyAction` 做 JVM 单元测试，真实 Demo 运行时使用 `SystemClock.uptimeMillis()` 控制 6 秒主线程忙等，并持续执行 `Math.sqrt()` 让 CPU 证据更明显。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`SystemClock.uptimeMillis()`、`Math.sqrt()`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 2 的“主线程 CPU 忙等”场景。

本计划不实现消息风暴、Sync Barrier、锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。

本场景和“当前消息慢”都可能归因为 `CURRENT_MESSAGE_SLOW`，区别在于：

- 当前消息慢 sleep 场景：`mainThread.current.wallMs` 高，`mainThread.current.cpuMs` 低，栈里有 `Thread.sleep`。
- 主线程 CPU 忙等场景：`mainThread.current.wallMs` 高，`mainThread.current.cpuMs` 也应更高，`threadCpu.topThreads` 中主线程更可疑，栈里能看到 `MainThreadCpuBusyScenario.run` 或 `DefaultCpuBusyAction`.

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CpuBusyAction.kt`
  - 可注入 CPU 忙等动作，测试中记录调用，Demo 中执行真实忙等。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenario.kt`
  - 第二个正式 ANR 场景：点击按钮后主线程 busy loop 6 秒。
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenarioTest.kt`
  - JVM 单元测试，验证场景元数据和 busy action 调用时长。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 将 `currentBusyButton` 接到 `MainThreadCpuBusyScenario.run()`，移除 Activity 内匿名 `runBusyLoop()`。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“当前消息忙等场景”的新人验证步骤和 JSON 判断口径。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 把第 2 个场景从“后续顺序”更新为“当前已实现/可验收”说明。

## Implementation Tasks

### Task 1: 用 TDD 实现主线程 CPU 忙等场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CpuBusyAction.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证主线程 CPU 忙等场景的元数据和忙等动作，确保 JSON 栈能定位到独立场景入口。
 */
class MainThreadCpuBusyScenarioTest {
    @Test
    fun runBurnsCpuForConfiguredDuration(): Unit {
        val cpuBusyAction: RecordingCpuBusyAction = RecordingCpuBusyAction()
        val scenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario(
            cpuBusyAction = cpuBusyAction,
            durationMs = 4_321L,
        )
        scenario.run()
        assertEquals(listOf(4_321L), cpuBusyAction.busyDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val cpuBusyAction: RecordingCpuBusyAction = RecordingCpuBusyAction()
        val scenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario(
            cpuBusyAction = cpuBusyAction,
        )
        assertEquals("main_thread_cpu_busy", scenario.id)
        assertEquals("当前消息忙等", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.cpuMs 明显高于等待类场景"))
        assertTrue(scenario.expectedJsonSignals.contains("threadCpu.topThreads 包含主线程高 CPU 证据"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 MainThreadCpuBusyScenario.run"))
    }

    private class RecordingCpuBusyAction : CpuBusyAction {
        val busyDurations: MutableList<Long> = mutableListOf()

        override fun burn(durationMs: Long): Double {
            busyDurations.add(durationMs)
            return 42.0
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenarioTest
```

Expected: FAIL with unresolved references for `MainThreadCpuBusyScenario` and `CpuBusyAction`.

- [x] **Step 3: Create CpuBusyAction**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CpuBusyAction.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 可注入 CPU 忙等动作，测试中记录调用，Demo 运行时执行真实 CPU 消耗。
 */
fun interface CpuBusyAction {
    /**
     * 在当前线程持续消耗 CPU。
     *
     * @param durationMs 忙等时长，单位毫秒。
     * @return 防止循环计算被过度优化的校验值。
     */
    fun burn(durationMs: Long): Double
}
```

- [x] **Step 4: Create MainThreadCpuBusyScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.SystemClock

/**
 * 输入事件触发的主线程 CPU 忙等场景，点击按钮后故意让主线程持续计算以验证忙等证据。
 *
 * @param cpuBusyAction 实际 CPU 消耗动作，测试中可替换为记录器。
 * @param durationMs 主线程忙等时长，默认超过 Demo SDK 的 3000ms 疑似 ANR 阈值。
 */
class MainThreadCpuBusyScenario(
    private val cpuBusyAction: CpuBusyAction = DefaultCpuBusyAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "main_thread_cpu_busy"
    override val title: String = "当前消息忙等"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.current.cpuMs 明显高于等待类场景",
        "threadCpu.topThreads 包含主线程高 CPU 证据",
        "mainThread.stackFrames 包含 MainThreadCpuBusyScenario.run",
    )

    /**
     * 在按钮点击消息中持续消耗主线程 CPU，制造和 Thread.sleep 不同的当前消息慢现场。
     */
    override fun run(): Unit {
        cpuBusyAction.burn(durationMs = durationMs).toString()
    }

    private class DefaultCpuBusyAction : CpuBusyAction {
        override fun burn(durationMs: Long): Double {
            val endAtMs: Long = SystemClock.uptimeMillis() + durationMs
            var checksum: Double = 0.0
            while (SystemClock.uptimeMillis() < endAtMs) {
                checksum += Math.sqrt(checksum + 42.0)
                if (checksum > CHECKSUM_RESET_THRESHOLD) {
                    checksum = 0.0
                }
            }
            return checksum
        }
    }

    private companion object {
        /**
         * 默认忙等 6 秒，稳定超过 debug 配置中的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 6_000L
        private const val CHECKSUM_RESET_THRESHOLD: Double = 1_000_000.0
    }
}
```

- [x] **Step 5: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenarioTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/CpuBusyAction.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadCpuBusyScenario.kt
git commit -m "实现主线程CPU忙等Demo场景"
```

### Task 2: 将当前消息忙等按钮接入场景类

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Add MainThreadCpuBusyScenario import**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt` imports from:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenario
```

to:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenario
import com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenario
```

- [x] **Step 2: Add scenario field**

In `MainActivity`, add this property immediately after `currentSlowInputScenario`:

```kotlin
    // 当前消息 CPU 忙等场景，用独立类承载根因入口，便于和 Thread.sleep 等待类场景区分。
    private val mainThreadCpuBusyScenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario()
```

The surrounding code should become:

```kotlin
    // 当前慢消息场景，用独立类承载根因入口，便于 JSON 栈中直接定位。
    private val currentSlowInputScenario: CurrentSlowInputScenario = CurrentSlowInputScenario()

    // 当前消息 CPU 忙等场景，用独立类承载根因入口，便于和 Thread.sleep 等待类场景区分。
    private val mainThreadCpuBusyScenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario()

    // Sync Barrier 泄漏场景，单独封装反射和 token 记录逻辑。
    private val syncBarrierLeakScenario: SyncBarrierLeakScenario by lazy {
        SyncBarrierLeakScenario(context = this)
    }
```

- [x] **Step 3: Wire currentBusyButton**

In `MainActivity.onCreate`, replace:

```kotlin
        findViewById<Button>(R.id.currentBusyButton).setOnClickListener {
            runBusyLoop()
        }
```

with:

```kotlin
        findViewById<Button>(R.id.currentBusyButton).setOnClickListener {
            mainThreadCpuBusyScenario.run()
        }
```

- [x] **Step 4: Remove anonymous busy loop method**

Delete this method from `MainActivity`:

```kotlin
    // 主线程持续忙等，用于验收当前消息慢且 CPU 占用较高的场景。
    private fun runBusyLoop(): Unit {
        val endAt: Long = System.currentTimeMillis() + 6_000L
        var ignoredValue: Double = 0.0
        while (System.currentTimeMillis() < endAt) {
            ignoredValue += Math.sqrt(42.0)
        }
        ignoredValue.toString()
    }
```

- [x] **Step 5: Run focused app tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenarioTest --tests com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenarioTest
```

Expected: PASS.

- [x] **Step 6: Run app compile check**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入主线程CPU忙等按钮"
```

### Task 3: 更新使用说明中的主线程 CPU 忙等验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [x] **Step 1: Add current busy scenario instructions**

In `docs-anr/103-ANR监控SDK使用说明.md`, add this subsection after the existing `### 当前消息慢场景` subsection:

````markdown
### 当前消息忙等场景

这个场景用于验证“主线程不是在等待，而是在持续消耗 CPU”的当前消息慢问题。用户点击按钮后，按钮点击消息会在主线程执行 6 秒 busy loop。

操作步骤：

1. 安装并打开 debug Demo App。
2. 点击“当前消息忙等”。
3. 等待 logcat 出现 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON 报告。
5. 先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。
6. 再看 `mainThread.current.wallMs`，预期大于 `3000`。
7. 再看 `mainThread.current.cpuMs`，它应明显高于 `当前消息慢` 的 sleep 场景。
8. 再看 `threadCpu.topThreads`，预期主线程在 CPU 排名中更靠前。
9. 最后看 `mainThread.stackFrames`，预期包含 `MainThreadCpuBusyScenario.run` 或 `DefaultCpuBusyAction.burn`。

新人分析结论可以这样写：

```text
本次报告是 Demo 当前消息忙等场景触发的疑似 ANR。当前主线程消息执行时间超过 3000ms，同时当前消息 CPU 耗时和线程 CPU 证据都偏高，主线程栈包含 MainThreadCpuBusyScenario.run 或 DefaultCpuBusyAction.burn。Barrier 和 Binder 证据不构成本次主因，因此根因是点击回调在主线程持续计算，导致输入事件无法及时处理。
```
````

- [x] **Step 2: 检查文档格式**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充主线程CPU忙等验证说明"
```

### Task 4: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Update scenario table row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the row:

```markdown
| 2 | 主线程 CPU 忙等 | 点击按钮后主线程 busy loop 6 秒 | `CURRENT_MESSAGE_SLOW` | `threadCpu.topThreads`、当前消息 wall/cpu |
```

with:

```markdown
| 2 | 主线程 CPU 忙等 | 点击“当前消息忙等”后主线程 busy loop 6 秒 | `CURRENT_MESSAGE_SLOW` | `mainThread.current.wallMs`、`mainThread.current.cpuMs`、`threadCpu.topThreads`、`MainThreadCpuBusyScenario.run` |
```

- [x] **Step 2: Add scenario details**

In `docs-anr/105-Demo-ANR场景实现计划.md`, add this section after the current slow scenario section and before `## 后续批次顺序`:

```markdown
## 第二批次：主线程 CPU 忙等

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“当前消息忙等”。
4. 阻塞期间继续点击屏幕，方便系统 Input 超时窗口也出现。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.current.cpuMs` 和 `threadCpu.topThreads`，它们应比 sleep 等待场景更能体现主线程 CPU 消耗。最后看 `mainThread.stackFrames`，应能看到 `MainThreadCpuBusyScenario.run` 或 `DefaultCpuBusyAction.burn`，说明根因入口是 Demo 主线程 CPU 忙等场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- `mainThread.current.cpuMs` 如果接近 0，应优先检查是否点错了“当前消息慢”按钮，或当前设备 CPU 证据采集是否失败。
```

- [x] **Step 3: Update follow-up order**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace:

```markdown
后续按主线程 CPU 忙等、消息风暴、锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

with:

```markdown
后续按消息风暴、锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

- [x] **Step 4: 检查文档格式**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新主线程CPU忙等场景矩阵"
```

### Task 5: 执行主线程 CPU 忙等最终验收

**Files:**
- No production files modified.

- [x] **Step 1: Run all relevant automated checks**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 2: Install debug app**

Run:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `adb devices` shows the intended device, and install prints `Success`.

- [x] **Step 3: Clear logcat**

Run:

```bash
adb logcat -c
```

Expected: command exits with code `0`.

- [x] **Step 4: Manually trigger scenario**

Open the app on the device and tap `当前消息忙等`. During the 6 second busy loop, tap the screen again once or twice to make the input timeout window easier to observe.

Expected logcat command:

```bash
adb logcat -s VibeAnrApplication AnrMonitor
```

Expected logcat lines:

```text
W VibeAnrApplication: suspect ANR captured: <event-id>
W VibeAnrApplication: ANR report written: <event-id>
```

- [x] **Step 5: Pull JSON reports**

Run:

```bash
mkdir -p manual-anr-reports/main-thread-cpu-busy
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.JSON > manual-anr-reports/main-thread-cpu-busy/<eventId>.JSON
```

Expected: at least one JSON report is available in `files/anr-monitor-reports`. Replace `<eventId>` with the newest file name printed by the `ls` command. The SDK writes reports to the app private `filesDir`, so do not use an external storage directory as the primary path.

- [x] **Step 6: Validate JSON root cause**

Open the latest JSON report and verify these values:

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
mainThread.current.wallMs >= 3000
mainThread.current.cpuMs is noticeably higher than the sleep scenario
threadCpu.topThreads contains main thread or shows main thread as a high CPU contributor
mainThread.stackFrames contains MainThreadCpuBusyScenario.run or DefaultCpuBusyAction.burn
binderBlock.suspected = false
barrierEvidence.stuckTokens is empty or not the primary evidence
```

Expected human conclusion:

```text
当前消息忙等场景验收通过：SDK 捕获到疑似 ANR，归因为 CURRENT_MESSAGE_SLOW，当前消息 wall time 超过阈值，当前消息 CPU 和线程 CPU 证据支持主线程持续计算，主线程栈能定位到 MainThreadCpuBusyScenario.run 或 DefaultCpuBusyAction.burn，说明 JSON 可以区分 CPU 忙等和 Thread.sleep 等待阻塞。
```

- [x] **Step 7: Commit manual acceptance record if docs changed**

If manual validation notes are added to docs, commit them:

```bash
git add docs-anr/103-ANR监控SDK使用说明.md docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录主线程CPU忙等场景验收结果"
```

If no docs changed, skip the commit and leave the working tree clean.

## Self-Review

- Spec coverage: 覆盖了 `docs/superpowers/plans/2026-06-08-demo-anr-scenarios-current-slow.md` 中顺序 2 的“主线程 CPU 忙等”场景；实现范围不扩散到消息风暴、锁等待或后台 CPU 竞争。
- Placeholder scan: 本计划没有使用空占位、延期交付、笼统测试或无代码步骤；每个代码步骤都给出完整文件内容。
- Type consistency: `CpuBusyAction`、`MainThreadCpuBusyScenario`、`MainThreadCpuBusyScenarioTest` 的包名、类名、方法名在测试、实现、Activity 和文档中保持一致。
- Testability: 忙等动作通过 `CpuBusyAction` 注入，JVM 单元测试验证场景元数据和调用时长；真实 ANR 触发通过手动验收验证 JSON。
- Root-cause clarity: 验收要求同时看 `wallMs`、`cpuMs`、`threadCpu.topThreads` 和业务栈，避免只凭 `CURRENT_MESSAGE_SLOW` 把 CPU 忙等和 sleep 等待混在一起。

## Three-Round Review

### Round 1: 范围和场景边界

结论：通过。本计划只处理“主线程 CPU 忙等”这一种 ANR 主因，复用现有 `currentBusyButton`，不新增无关按钮，不改变 SDK 归因规则。

### Round 2: 可执行性和 TDD

结论：已修正后通过。计划先写失败测试，再创建 `CpuBusyAction` 和 `MainThreadCpuBusyScenario`，然后接线 Activity，最后补文档和手动验收；审核中发现“替换整个 MainActivity”会增加覆盖无关改动的风险，已改成 import、属性、按钮回调、旧方法删除四个小步骤。

### Round 3: JSON 证据和新人分析口径

结论：通过。本计划明确要求用 `mainThread.current.wallMs` 证明当前消息慢，用 `mainThread.current.cpuMs` 和 `threadCpu.topThreads` 证明 CPU 忙等，用 `mainThread.stackFrames` 定位业务入口，并排除 Barrier/Binder 干扰。
