# GC / 内存抖动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中新增“GC / 内存抖动”ANR 验证场景，让 SDK 能抓到大量对象分配引发的主线程停顿现场，并在 JSON 与 logcat 中给出可复盘的内存压力证据。

**Architecture:** 本计划沿用现有 Demo 场景模式：Activity 只负责按钮和 intent 接线，复现逻辑放在 `scenario` 包中的独立类。场景类通过 `MemoryChurnWorkload` 注入可测试工作负载；真实 Demo 运行时在主线程分批分配 byte array、短暂保留对象并主动制造 GC 压力，让 SDK 报告里的当前消息、主线程栈、环境内存快照和 logcat 系统 GC 日志可以串成证据链。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`System.gc()`、本地 JSON 报告、logcat 系统 GC 日志。

---

## Scope Check

“GC / 内存抖动”不是 Sync Barrier、Binder、线程池等待、IO 阻塞或单纯 CPU 忙等。本计划只新增一个 Demo 按钮，用于制造“当前主线程消息里大量分配对象，触发频繁 GC 和内存压力”的场景。

本计划不修改 SDK 归因规则。验收时主归因可以仍然是 `CURRENT_MESSAGE_SLOW`，因为端侧 JSON 里当前没有独立的 `GC_MEMORY_CHURN` 主归因枚举。判断本场景是否成立时，不能只看 `attribution.primary`；需要同时看：

- `mainThread.stackFrames` 能定位到 `GcMemoryChurnScenario.run` 和 `GcMemoryChurnWorkload.churnMemoryOnMainThread`。
- `mainThread.current.wallMs >= 3000`。
- `environmentSnapshot.memory` 可读，并能记录当时可用内存。
- logcat 中同一时间窗出现系统 GC 相关日志，例如 `Background concurrent copying GC`、`Explicit concurrent copying GC`、`Clamp target GC heap`、`Alloc concurrent copying GC`。
- `barrierEvidence`、`binderBlock`、线程池等待等证据不是本次主因。

如果后续要让 SDK 自动输出 `GC_MEMORY_CHURN` 主归因，需要另起 SDK 采集增强计划，引入 GC 计数、Java heap 采样或 runtime memory delta。本计划只实现 Demo 复现与现有 JSON 证据验证。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenarioTest.kt`
  - JVM 单元测试，验证场景元数据和工作负载调用参数。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MemoryChurnWorkload.kt`
  - 可注入的内存抖动工作负载接口。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnWorkload.kt`
  - 真实 Demo 工作负载：在主线程分批分配对象、保留部分对象、周期性调用 `System.gc()`，制造 GC 压力。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenario.kt`
  - 场景入口，暴露 `id`、`title`、`expectedAttribution`、`expectedJsonSignals` 和 `run()`。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 新增场景属性，把按钮和 `anr_demo_scenario=gc_memory_churn` 接到场景类。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 新增“GC / 内存抖动”按钮。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增按钮中文文案。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加 GC / 内存抖动场景验证步骤、JSON 判断口径、logcat 辅助判断和修复建议。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 将场景 12 状态从“待实现”更新为“已实现，待手动验收”，补充本场景批次说明。
- Modify: `README.md`
  - 在“Demo 已覆盖场景”加入新场景，并从后续计划移除 GC/内存抖动。

## Implementation Tasks

### Task 1: 用 TDD 实现 GC / 内存抖动场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MemoryChurnWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 GC / 内存抖动场景的元数据和工作负载入口。
 */
class GcMemoryChurnScenarioTest {
    @Test
    fun runExecutesConfiguredMemoryChurnWorkload(): Unit {
        val workload: RecordingMemoryChurnWorkload = RecordingMemoryChurnWorkload()
        val scenario: GcMemoryChurnScenario = GcMemoryChurnScenario(
            workload = workload,
            targetDurationMs = 4_321L,
        )

        scenario.run()

        assertEquals(listOf(4_321L), workload.targetDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingMemoryChurnWorkload = RecordingMemoryChurnWorkload()
        val scenario: GcMemoryChurnScenario = GcMemoryChurnScenario(
            workload = workload,
        )

        assertEquals("gc_memory_churn", scenario.id)
        assertEquals("GC / 内存抖动", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + GC/memory pressure evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 GcMemoryChurnScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 GcMemoryChurnWorkload.churnMemoryOnMainThread"))
        assertTrue(scenario.expectedJsonSignals.contains("environmentSnapshot.memory 可用"))
        assertTrue(scenario.expectedJsonSignals.contains("logcat 同一时间窗出现系统 GC 日志"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingMemoryChurnWorkload : MemoryChurnWorkload {
        val targetDurations: MutableList<Long> = mutableListOf()

        override fun churnMemoryOnMainThread(targetDurationMs: Long): Unit {
            targetDurations.add(targetDurationMs)
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.GcMemoryChurnScenarioTest
```

Expected: FAIL with unresolved references for `MemoryChurnWorkload` and `GcMemoryChurnScenario`.

- [x] **Step 3: Create MemoryChurnWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MemoryChurnWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 主线程内存抖动工作负载。测试中替换为记录器，Demo 运行时制造真实分配和 GC 压力。
 */
fun interface MemoryChurnWorkload {
    /**
     * 在调用线程分配对象并制造内存抖动。
     *
     * @param targetDurationMs 目标持续时间，必须超过 SDK 疑似 ANR 阈值。
     */
    fun churnMemoryOnMainThread(targetDurationMs: Long): Unit
}
```

- [x] **Step 4: Create GcMemoryChurnWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

/**
 * Demo 专用 GC / 内存抖动工作负载。
 *
 * 这个类故意在主线程持续分配 byte array，并保留一小段时间，促使运行时更频繁地执行 GC。
 * 分配规模被限制在一个滑动窗口内，避免 Demo 直接 OOM。
 */
class GcMemoryChurnWorkload(
    private val allocationSizeBytes: Int = DEFAULT_ALLOCATION_SIZE_BYTES,
    private val retainedBatchCount: Int = DEFAULT_RETAINED_BATCH_COUNT,
    private val gcIntervalMs: Long = DEFAULT_GC_INTERVAL_MS,
) : MemoryChurnWorkload {
    /**
     * 在当前线程制造内存抖动。Demo 按钮会在主线程调用此方法。
     */
    override fun churnMemoryOnMainThread(targetDurationMs: Long): Unit {
        val retainedObjects: ArrayDeque<ByteArray> = ArrayDeque()
        val endUptimeMs: Long = SystemClock.uptimeMillis() + targetDurationMs
        var nextGcUptimeMs: Long = SystemClock.uptimeMillis() + gcIntervalMs
        var allocationCount: Int = 0

        while (SystemClock.uptimeMillis() < endUptimeMs) {
            retainedObjects.add(ByteArray(allocationSizeBytes))
            allocationCount += 1

            while (retainedObjects.size > retainedBatchCount) {
                retainedObjects.removeFirst()
            }

            val nowUptimeMs: Long = SystemClock.uptimeMillis()
            if (nowUptimeMs >= nextGcUptimeMs) {
                Log.w(TAG, "GC / 内存抖动场景请求 GC: allocations=$allocationCount")
                System.gc()
                nextGcUptimeMs = nowUptimeMs + gcIntervalMs
            }
        }

        Log.w(TAG, "GC / 内存抖动场景完成: allocations=$allocationCount retained=${retainedObjects.size}")
        retainedObjects.clear()
        System.gc()
    }

    private companion object {
        /**
         * 单次分配大小。256 KiB 可以产生明显分配压力，又不容易在普通测试机上直接 OOM。
         */
        private const val DEFAULT_ALLOCATION_SIZE_BYTES: Int = 256 * 1024

        /**
         * 保留最近 12 个对象，形成约 3 MiB 的短暂活动集，避免被立即全部回收。
         */
        private const val DEFAULT_RETAINED_BATCH_COUNT: Int = 12

        /**
         * 周期性请求 GC，帮助 logcat 出现可观测系统 GC 日志。
         */
        private const val DEFAULT_GC_INTERVAL_MS: Long = 450L

        /**
         * Demo 场景日志标签。
         */
        private const val TAG: String = "GcMemoryChurn"
    }
}
```

- [x] **Step 5: Create GcMemoryChurnScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * GC / 内存抖动 ANR 场景。
 *
 * 点击按钮后在主线程持续分配对象，制造 GC 压力和当前消息耗时，用于验证 SDK 能否保留
 * 当前消息、业务栈和系统环境内存证据。
 */
class GcMemoryChurnScenario(
    private val workload: MemoryChurnWorkload = GcMemoryChurnWorkload(),
    private val targetDurationMs: Long = DEFAULT_TARGET_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "gc_memory_churn"
    override val title: String = "GC / 内存抖动"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + GC/memory pressure evidence"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 GcMemoryChurnScenario.run",
        "mainThread.stackFrames 包含 GcMemoryChurnWorkload.churnMemoryOnMainThread",
        "environmentSnapshot.memory 可用",
        "logcat 同一时间窗出现系统 GC 日志",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 运行 GC / 内存抖动工作负载。由按钮点击或 adb intent 在主线程调用。
     */
    override fun run(): Unit {
        workload.churnMemoryOnMainThread(targetDurationMs = targetDurationMs)
    }

    private companion object {
        /**
         * 默认持续 6 秒，超过 Demo SDK 的 `suspectAnrMs=3000`。
         */
        private const val DEFAULT_TARGET_DURATION_MS: Long = 6_000L
    }
}
```

- [x] **Step 6: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.GcMemoryChurnScenarioTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MemoryChurnWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/GcMemoryChurnScenario.kt
git commit -m "新增 GC 内存抖动场景类"
```

### Task 2: 将 GC / 内存抖动按钮接入 Demo

**Files:**
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [x] **Step 1: Add string resource**

Modify `app/src/main/res/values/strings.xml` by adding this line after `demo_thread_pool_exhaustion_wait`:

```xml
    <string name="demo_gc_memory_churn">GC / 内存抖动</string>
```

- [x] **Step 2: Add button to layout**

Modify `app/src/main/res/layout/activity_main.xml` by adding this button after `threadPoolExhaustionWaitButton` and before `binderLikeButton`:

```xml
        <Button
            android:id="@+id/gcMemoryChurnButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_gc_memory_churn" />
```

- [x] **Step 3: Wire MainActivity import and property**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt` imports by adding:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.GcMemoryChurnScenario
```

Add this property after `threadPoolExhaustionWaitScenario`:

```kotlin
    // GC / 内存抖动场景，按钮点击后在主线程制造大量对象分配和 GC 压力。
    private val gcMemoryChurnScenario: GcMemoryChurnScenario = GcMemoryChurnScenario()
```

- [x] **Step 4: Wire button click**

Modify `onCreate()` in `MainActivity.kt` by adding this block after `threadPoolExhaustionWaitButton` click listener:

```kotlin
        findViewById<Button>(R.id.gcMemoryChurnButton).setOnClickListener {
            gcMemoryChurnScenario.run()
        }
```

- [x] **Step 5: Wire adb intent trigger**

Modify `runScenarioFromIntent()` in `MainActivity.kt` so the `when` block contains:

```kotlin
        when (scenarioId) {
            ioDatabaseFileBlockScenario.id -> ioDatabaseFileBlockScenario.run()
            threadPoolExhaustionWaitScenario.id -> threadPoolExhaustionWaitScenario.run()
            gcMemoryChurnScenario.id -> gcMemoryChurnScenario.run()
        }
```

- [x] **Step 6: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:mergeDebugResources
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml
git commit -m "接入 GC 内存抖动 Demo 按钮"
```

### Task 3: 更新使用说明中的 GC / 内存抖动验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [x] **Step 1: Add usage section**

Add this section after the `### Demo 场景：线程池耗尽 + 主线程等待` section and before `## 10. 常见问题` in `docs-anr/103-ANR监控SDK使用说明.md`:

```markdown
## Demo 场景：GC / 内存抖动

这个场景用于验证“当前消息里大量分配对象，触发频繁 GC 和内存压力”的 ANR 现场。它不代表 SDK 已经有独立的 `GC_MEMORY_CHURN` 主归因；当前分析应把它看成 `CURRENT_MESSAGE_SLOW` 主因下的资源辅因。

### 触发方式

1. 安装 debug 包并打开 Demo App。
2. 点击“GC / 内存抖动”。
3. 等待 Logcat 出现 `suspect ANR captured` 和 `ANR report written`。
4. 同时保留触发前后 10 秒的 logcat，方便查看系统 GC 日志。
5. 拉取 `files/anr-monitor-reports/<eventId>.json`。

也可以用 adb 触发：

```bash
adb -s <deviceId> shell am start -S -n com.valiantyan.vibeanrmonitoring/.MainActivity --es anr_demo_scenario gc_memory_churn
```

### JSON 判断步骤

1. 看 `attribution.primary`，预期通常是 `CURRENT_MESSAGE_SLOW`。
2. 看 `mainThread.current.wallMs`，应大于 `3000`。
3. 看 `mainThread.stackFrames`，应包含 `GcMemoryChurnScenario.run` 和 `GcMemoryChurnWorkload.churnMemoryOnMainThread`。
4. 看 `environmentSnapshot.memory`，确认 `availableBytes`、`totalBytes` 有值；如果 `availability.memoryAvailable=false`，本次缺少内存环境证据，不能用 JSON 单独证明内存压力。
5. 看 `barrierEvidence.stuckTokens` 和 `binderBlock.suspected`，它们不应该是本次主因。
6. 打开同一时间窗 logcat，先用 `GcMemoryChurn` 确认场景确实请求过 GC，再搜索 `GC`、`concurrent copying`、`Clamp target GC heap`、`Alloc` 等关键词，确认是否出现系统真实 GC 日志。

### 可以写进分析报告的结论模板

本次 ANR 主因是当前主线程消息执行过久。JSON 的主线程栈定位到 `GcMemoryChurnScenario.run` / `GcMemoryChurnWorkload.churnMemoryOnMainThread`，说明按钮点击消息中存在大量对象分配；同一时间窗 logcat 出现场景 GC 请求日志和系统 GC 日志，`environmentSnapshot.memory` 提供了当时内存快照，因此可以把“GC / 内存抖动”作为本次 ANR 的关键辅因。Barrier 和 Binder 证据均不是本次主因。

### 修复建议

- 不要在主线程一次性创建大量短生命周期对象。
- 把批量解析、图片处理、列表构建、序列化等分配密集逻辑移到后台线程。
- 对大对象使用分页、流式处理、对象复用或缓存上限，避免瞬时分配峰值。
- 通过 Android Studio Memory Profiler、Perfetto、logcat 系统 GC 日志确认分配热点。
- 如果业务必须在前台处理大量数据，分批执行并在每批之间让出主线程，避免单条消息持续超过输入超时窗口。
```

- [x] **Step 2: Check markdown formatting**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 3: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "更新 GC 内存抖动使用说明"
```

### Task 4: 更新 Demo 场景矩阵和 README 状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
- Modify: `README.md`

- [x] **Step 1: Update scenario matrix row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the GC / 内存抖动 row with:

```markdown
| 12 | GC / 内存抖动 | 点击“GC / 内存抖动”后主线程分批分配对象并周期性请求 GC | `CURRENT_MESSAGE_SLOW` + GC/内存压力辅因 | `mainThread.current.wallMs`、`mainThread.stackFrames` 包含 `GcMemoryChurnScenario.run` / `GcMemoryChurnWorkload.churnMemoryOnMainThread`、`environmentSnapshot.memory`、同时间窗 logcat 系统 GC 日志 | 已实现，待手动验收 |
```

- [x] **Step 2: Add batch section to scenario matrix**

Append this section after the thread pool batch in `docs-anr/105-Demo-ANR场景实现计划.md`:

```markdown
## 第十二批次：GC / 内存抖动

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“GC / 内存抖动”。
4. 保存触发前后 10 秒 logcat。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期通常为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.stackFrames`，应能看到 `GcMemoryChurnScenario.run` 和 `GcMemoryChurnWorkload.churnMemoryOnMainThread`。最后看 `environmentSnapshot.memory` 与同一时间窗 logcat 系统 GC 日志，用来判断是否存在明显内存抖动辅因。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- `threadCpu.topThreads` 可以有主线程 CPU 消耗，但不能把本场景误判成纯 CPU 忙等。
- 没有系统 GC 日志时，只能写“主线程大量分配导致当前消息慢”，不能强写“GC 已确认导致 ANR”。

### 修复方向

- 把大量对象分配、解析、序列化、图片处理移出主线程。
- 分批处理大数据并让出主线程。
- 减少短生命周期对象和大对象瞬时峰值。
- 用 Memory Profiler、Perfetto、logcat 系统 GC 日志确认分配热点。

### 验收记录

- [ ] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.GcMemoryChurnScenarioTest` 通过。
- [ ] `./gradlew :app:assembleDebug` 通过。
- [ ] 真机或模拟器点击“GC / 内存抖动”后生成 JSON。
- [ ] JSON 中 `attribution.primary=CURRENT_MESSAGE_SLOW`。
- [ ] JSON 中 `mainThread.current.wallMs >= 3000`。
- [ ] JSON 中 `mainThread.stackFrames` 包含 `GcMemoryChurnScenario.run`。
- [ ] JSON 中 `mainThread.stackFrames` 包含 `GcMemoryChurnWorkload.churnMemoryOnMainThread`。
- [ ] JSON 中 `environmentSnapshot.memory` 可读，或记录不可用原因。
- [ ] 同一时间窗 logcat 中存在系统 GC 相关日志。
- [ ] Barrier 和 Binder 证据均不是本次主因。

验收结论：待手动验收后填写。
```

- [x] **Step 3: Update README covered scenarios**

In `README.md`, add this row to the Demo covered scenarios table:

```markdown
| GC / 内存抖动 | 点击“GC / 内存抖动” | `CURRENT_MESSAGE_SLOW` + GC/内存压力辅因，主线程栈定位到 `GcMemoryChurnScenario.run` 和 `GcMemoryChurnWorkload` |
```

Also update the “后续场景计划” sentence from:

```markdown
后续场景计划包括主线程锁等待、GC/内存抖动、进程内 CPU 竞争等。
```

to:

```markdown
后续场景计划包括主线程锁等待、进程内 CPU 竞争等。
```

- [x] **Step 4: Check docs formatting**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md README.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md README.md
git commit -m "更新 GC 内存抖动场景状态"
```

### Task 5: 执行 GC / 内存抖动最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Run unit and build verification**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.GcMemoryChurnScenarioTest
./gradlew :app:assembleDebug
```

Expected: both PASS.

- [x] **Step 2: Install debug app**

Run:

```bash
adb devices
adb -s <deviceId> install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: device is listed and install prints `Success`.

- [x] **Step 3: Clear old reports and logcat**

Run:

```bash
adb -s <deviceId> shell run-as com.valiantyan.vibeanrmonitoring rm -rf files/anr-monitor-reports
adb -s <deviceId> shell run-as com.valiantyan.vibeanrmonitoring mkdir files/anr-monitor-reports
adb -s <deviceId> logcat -c
```

Expected: commands exit successfully.

- [x] **Step 4: Trigger scenario by adb intent**

Run:

```bash
adb -s <deviceId> shell am start -S -n com.valiantyan.vibeanrmonitoring/.MainActivity --es anr_demo_scenario gc_memory_churn
```

Expected: app opens and logcat contains `run demo scenario from intent: gc_memory_churn`.

- [x] **Step 5: Capture logcat around the event**

Run this command in a separate terminal for about 15 seconds after triggering:

```bash
adb -s <deviceId> logcat -v time -s VibeAnrApplication AnrMonitor GcMemoryChurn art zygote64 dalvikvm
```

Expected: output contains `suspect ANR captured`, `ANR report written`, `GcMemoryChurn` scenario logs, and one or more system GC-related lines if the runtime logs GC for this device/Android version.

- [x] **Step 6: Pull latest JSON**

Run:

```bash
adb -s <deviceId> shell run-as com.valiantyan.vibeanrmonitoring ls -t files/anr-monitor-reports
adb -s <deviceId> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > gc-memory-churn.json
```

Expected: local `gc-memory-churn.json` is created.

- [x] **Step 7: Inspect JSON evidence**

Run:

```bash
jq '.attribution.primary, .mainThread.current.wallMs, .mainThread.stackFrames[0:12], .environmentSnapshot.memory, .barrierEvidence.stuckTokens, .binderBlock.suspected' gc-memory-churn.json
```

Expected:

```text
"CURRENT_MESSAGE_SLOW"
wallMs value >= 3000
stack contains GcMemoryChurnScenario.run
stack contains GcMemoryChurnWorkload.churnMemoryOnMainThread
environmentSnapshot.memory is an object, or environmentSnapshot.availability.memoryAvailable explains missing evidence
barrierEvidence.stuckTokens is empty
binderBlock.suspected is false
```

- [x] **Step 8: Update acceptance record**

In `docs-anr/105-Demo-ANR场景实现计划.md`, update the 第十二批次验收记录 checkboxes based on the actual run. Replace `验收结论：待手动验收后填写。` with this template filled with the actual report id and device:

```markdown
验收结论：GC / 内存抖动场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `CURRENT_MESSAGE_SLOW`，当前消息耗时达到 Demo 阈值，主线程栈能定位到 `GcMemoryChurnScenario.run` 与 `GcMemoryChurnWorkload.churnMemoryOnMainThread`；同一时间窗 logcat 出现场景 GC 请求日志和系统 GC 日志，`environmentSnapshot.memory` 提供内存快照或明确不可用原因。Barrier 和 Binder 证据均不是本次主因。因此本次可以写为“按钮点击消息中大量对象分配造成当前消息执行过久，并伴随 GC / 内存压力证据”。
```

- [x] **Step 9: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "完成 GC 内存抖动最终验收"
```

## Self-Review

### Spec coverage

- 覆盖了 `2026-06-08-demo-anr-scenarios-current-slow.md` 中“GC / 内存抖动”待实现项。
- 覆盖了 Demo 按钮、adb intent、单元测试、使用说明、场景矩阵、README 和最终手动验收。
- 明确说明当前 SDK 不新增 `GC_MEMORY_CHURN` 主归因，避免把资源辅因过度承诺为自动根因。

### Placeholder scan

本计划没有占位描述。所有代码步骤均给出完整代码块，所有验证步骤均给出命令和期望结果。

### Type consistency

- 测试和实现统一使用 `MemoryChurnWorkload.churnMemoryOnMainThread(targetDurationMs: Long)`。
- 场景 ID 统一为 `gc_memory_churn`。
- 类名统一为 `GcMemoryChurnScenario` 与 `GcMemoryChurnWorkload`。
- 文档字段统一使用现有 JSON 节点名 `environmentSnapshot.memory`、`barrierEvidence.stuckTokens`、`binderBlock.suspected`。
