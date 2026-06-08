# Demo BroadcastReceiver 超时场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“BroadcastReceiver 超时”做成可测试、可复现、可通过 SDK JSON 区分“系统广播组件超时”和“Receiver 业务代码阻塞根因”的正式 ANR 场景。

**Architecture:** 本计划新增一个显式应用内广播场景：按钮只负责触发 `BroadcastTimeoutScenario.run()`，场景类只负责发送显式广播，真正阻塞发生在 `BroadcastTimeoutReceiver.onReceive()`。为了让 JVM 单元测试稳定执行，广播发送和 Receiver 阻塞动作都通过小接口拆开测试；真实 Demo 运行时使用 `Context.sendBroadcast()` 触发清单注册的非导出 Receiver，并在主线程阻塞 12 秒制造前台 BroadcastReceiver 超时窗口。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`BroadcastReceiver`、显式 `Intent`、`Context.sendBroadcast()`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 6 的“BroadcastReceiver 超时”场景。

本计划不实现主线程锁等待、Service 超时、Provider、Binder、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。

本计划也不新增 SDK 归因枚举。当前 SDK 已有 `systemAnr.anrType=BROADCAST_FOREGROUND/BROADCAST_BACKGROUND` 用于表达系统确认的广播组件类型，但业务根因仍应从 `mainThread.stackFrames`、`mainThread.current`、`history` 和 `pendingQueue` 中判断。Demo 验收时允许两种结果：

- 疑似 ANR 阶段：`event.eventType=SUSPECT_ANR`，`systemAnr.isConfirmedAnr=false`，`attribution.primary` 通常是 `CURRENT_MESSAGE_SLOW`，主线程栈应包含 `BroadcastTimeoutReceiver.onReceive`。
- 系统确认阶段：`systemAnr.isConfirmedAnr=true`，`systemAnr.anrType=BROADCAST_FOREGROUND` 或 `BROADCAST_BACKGROUND`，同时主线程栈或历史消息仍应能定位到 `BroadcastTimeoutReceiver.onReceive`。

本场景和已完成场景的区别：

- 输入事件当前慢消息：阻塞发生在按钮点击消息内，入口是 `CurrentSlowInputScenario.run`。
- 主线程 CPU 忙等：阻塞发生在按钮点击消息内，但 CPU 证据更高，入口是 `MainThreadCpuBusyScenario.run`。
- 消息风暴：根因是 Pending 队列大量重复消息，入口是 `MessageStormScenario.run`。
- Sync Barrier 泄漏 / nativePollOnce：根因是队头 Sync Barrier，入口是 `SyncBarrierLeakScenario.run` 的 Barrier token 插入栈。
- BroadcastReceiver 超时：按钮只发送广播，根因入口必须落在 `BroadcastTimeoutReceiver.onReceive`，系统侧可能额外确认广播组件超时。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenarioTest.kt`
  - JVM 单元测试，验证场景元数据、广播 action、显式发送动作、Receiver 阻塞动作。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastSender.kt`
  - 可注入广播发送器。测试中记录 action，真实 Demo 中发送显式应用内广播。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/BroadcastTimeoutReceiver.kt`
  - 清单注册的非导出 Receiver。收到 Demo action 后在主线程阻塞 12 秒。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutBlocker.kt`
  - 可测试的 Receiver 阻塞逻辑，真实 Receiver 只负责委托它执行阻塞。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenario.kt`
  - 第六个正式 ANR 场景：按钮点击后发送显式广播。
- Modify: `app/src/main/AndroidManifest.xml`
  - 注册 `BroadcastTimeoutReceiver`，设置 `android:exported="false"`，声明专用 action。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 新增“BroadcastReceiver 超时”按钮。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增按钮文案 `demo_broadcast_timeout`。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 将新按钮接到 `BroadcastTimeoutScenario.run()`。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“BroadcastReceiver 超时场景”的新人验证步骤、JSON 字段读取口径、排除项和修复方向。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 把 BroadcastReceiver 场景状态更新为已实现，并追加第六批次验收说明。

## Implementation Tasks

### Task 1: 用 TDD 实现 BroadcastReceiver 超时场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/BroadcastTimeoutReceiver.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastSender.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutBlocker.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 BroadcastReceiver 超时场景的触发入口和 Receiver 阻塞动作。
 */
class BroadcastTimeoutScenarioTest {
    @Test
    fun runSendsBroadcastWithConfiguredAction(): Unit {
        val sender: RecordingBroadcastSender = RecordingBroadcastSender()
        val scenario: BroadcastTimeoutScenario = BroadcastTimeoutScenario(
            broadcastSender = sender,
        )

        scenario.run()

        assertEquals(
            listOf(BroadcastTimeoutScenario.ACTION_BROADCAST_TIMEOUT),
            sender.actions,
        )
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val sender: RecordingBroadcastSender = RecordingBroadcastSender()
        val scenario: BroadcastTimeoutScenario = BroadcastTimeoutScenario(
            broadcastSender = sender,
        )

        assertEquals("broadcast_receiver_timeout", scenario.id)
        assertEquals("BroadcastReceiver 超时", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + BROADCAST component evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 BroadcastTimeoutReceiver.onReceive"))
        assertTrue(scenario.expectedJsonSignals.contains("systemAnr.anrType = BROADCAST_FOREGROUND 或 BROADCAST_BACKGROUND"))
        assertTrue(scenario.expectedJsonSignals.contains("systemAnr.componentTimeoutMs = 10000 或 60000"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun blockerBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val blocker: BroadcastTimeoutBlocker = BroadcastTimeoutBlocker(
            blockingAction = blockingAction,
            durationMs = 12_345L,
        )

        blocker.block()

        assertEquals(listOf(12_345L), blockingAction.blockedDurations)
    }

    private class RecordingBroadcastSender : BroadcastSender {
        val actions: MutableList<String> = mutableListOf()

        override fun send(action: String): Unit {
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
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenarioTest
```

Expected: FAIL with unresolved references for `BroadcastTimeoutScenario`, `BroadcastSender`, and `BroadcastTimeoutBlocker`.

- [x] **Step 3: Create BroadcastSender**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastSender.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.valiantyan.vibeanrmonitoring.BroadcastTimeoutReceiver

/**
 * 可注入广播发送器，测试中记录 action，真实 Demo 中发送显式应用内广播。
 */
fun interface BroadcastSender {
    /**
     * 发送指定 action 的广播。
     *
     * @param action 广播 action。
     */
    fun send(action: String): Unit
}

/**
 * 使用应用上下文发送显式广播，避免隐式广播限制影响 Demo 可复现性。
 */
class ContextBroadcastSender(
    context: Context,
) : BroadcastSender {
    private val appContext: Context = context.applicationContext

    override fun send(action: String): Unit {
        val intent: Intent = Intent(action).apply {
            component = ComponentName(appContext, BroadcastTimeoutReceiver::class.java)
            setPackage(appContext.packageName)
        }
        appContext.sendBroadcast(intent)
    }
}
```

- [x] **Step 4: Create BroadcastTimeoutBlocker**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutBlocker.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * BroadcastReceiver 内部的阻塞动作，单独拆出便于 JVM 单元测试。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs Receiver 阻塞时长，默认超过前台广播 10 秒超时阈值。
 */
class BroadcastTimeoutBlocker(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    /**
     * 在 Receiver 所在线程阻塞，真实运行时就是主线程。
     */
    fun block(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 12 秒，稳定超过 Android 前台广播常见 10 秒超时阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 12_000L
    }
}
```

- [x] **Step 5: Create BroadcastTimeoutScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 发送显式广播，让清单注册的 BroadcastReceiver 在主线程阻塞并触发组件超时。
 *
 * @param broadcastSender 广播发送器，测试中可替换为记录器。
 */
class BroadcastTimeoutScenario(
    private val broadcastSender: BroadcastSender,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        broadcastSender = ContextBroadcastSender(context = context),
    )

    override val id: String = "broadcast_receiver_timeout"
    override val title: String = "BroadcastReceiver 超时"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + BROADCAST component evidence"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.stackFrames 包含 BroadcastTimeoutReceiver.onReceive",
        "systemAnr.anrType = BROADCAST_FOREGROUND 或 BROADCAST_BACKGROUND",
        "systemAnr.componentTimeoutMs = 10000 或 60000",
        "barrierEvidence.stuckTokens 不是主因",
    )

    /**
     * 发送 Demo 专用显式广播。真正阻塞入口在 BroadcastTimeoutReceiver.onReceive。
     */
    override fun run(): Unit {
        broadcastSender.send(action = ACTION_BROADCAST_TIMEOUT)
    }

    companion object {
        /**
         * Demo 专用 action，只用于应用内显式广播。
         */
        const val ACTION_BROADCAST_TIMEOUT: String =
            "com.valiantyan.vibeanrmonitoring.action.BROADCAST_TIMEOUT"
    }
}
```

- [x] **Step 6: Create BroadcastTimeoutReceiver**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/BroadcastTimeoutReceiver.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutBlocker
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenario

/**
 * Demo 专用阻塞广播接收器，用于复现 BroadcastReceiver 执行超时。
 */
class BroadcastTimeoutReceiver : BroadcastReceiver() {
    /**
     * 收到 Demo action 后在主线程阻塞，形成 BroadcastReceiver 超时现场。
     */
    override fun onReceive(context: Context, intent: Intent): Unit {
        if (intent.action != BroadcastTimeoutScenario.ACTION_BROADCAST_TIMEOUT) {
            return
        }
        BroadcastTimeoutBlocker().block()
    }
}
```

- [x] **Step 7: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenarioTest
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/BroadcastTimeoutReceiver.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastSender.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutBlocker.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BroadcastTimeoutScenario.kt
git commit -m "实现 BroadcastReceiver 超时场景类"
```

### Task 2: 注册 BroadcastTimeoutReceiver 清单

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [x] **Step 1: Register receiver in AndroidManifest**

Modify `app/src/main/AndroidManifest.xml` by adding this receiver entry inside `<application>` after `BarrierLeakService`:

```xml
        <receiver
            android:name=".BroadcastTimeoutReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="com.valiantyan.vibeanrmonitoring.action.BROADCAST_TIMEOUT" />
            </intent-filter>
        </receiver>
```

The `<application>` block should contain both component registrations:

```xml
        <service
            android:name=".BarrierLeakService"
            android:exported="false" />

        <receiver
            android:name=".BroadcastTimeoutReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="com.valiantyan.vibeanrmonitoring.action.BROADCAST_TIMEOUT" />
            </intent-filter>
        </receiver>
```

- [x] **Step 2: Compile app Kotlin and manifest**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:processDebugMainManifest
```

Expected: PASS.

- [x] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "注册 BroadcastReceiver 超时测试组件"
```

### Task 3: 将 BroadcastReceiver 超时按钮接入 Demo

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Add button string**

Modify `app/src/main/res/values/strings.xml` by adding this line after `demo_sync_barrier_leak`:

```xml
    <string name="demo_broadcast_timeout">BroadcastReceiver 超时</string>
```

The file should contain:

```xml
<resources>
    <string name="app_name">Vibe ANR 监控</string>
    <string name="main_title">Vibe ANR 监控</string>
    <string name="main_subtitle">ANR 场景验证</string>
    <string name="demo_current_slow">当前消息慢</string>
    <string name="demo_message_storm">消息风暴</string>
    <string name="demo_current_busy">当前消息忙等</string>
    <string name="demo_binder_like_lock">Binder 模拟等待</string>
    <string name="demo_sync_barrier_leak">Sync Barrier 泄漏 ANR</string>
    <string name="demo_broadcast_timeout">BroadcastReceiver 超时</string>
</resources>
```

- [x] **Step 2: Add button to layout**

Modify `app/src/main/res/layout/activity_main.xml` by adding this button after `syncBarrierLeakButton`:

```xml
    <Button
        android:id="@+id/broadcastTimeoutButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/demo_broadcast_timeout" />
```

The end of the layout should be:

```xml
    <Button
        android:id="@+id/syncBarrierLeakButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/demo_sync_barrier_leak" />

    <Button
        android:id="@+id/broadcastTimeoutButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/demo_broadcast_timeout" />
</LinearLayout>
```

- [x] **Step 3: Wire MainActivity**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`.

Add this import after `android.widget.Button`:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenario
```

Add this property after `syncBarrierLeakScenario`:

```kotlin
    // BroadcastReceiver 超时场景，按钮只负责发送广播，真正阻塞入口在 Receiver。
    private val broadcastTimeoutScenario: BroadcastTimeoutScenario by lazy {
        BroadcastTimeoutScenario(context = this)
    }
```

Add this click listener after `syncBarrierLeakButton`:

```kotlin
        findViewById<Button>(R.id.broadcastTimeoutButton).setOnClickListener {
            broadcastTimeoutScenario.run()
        }
```

The relevant Activity body should look like:

```kotlin
    // Sync Barrier 泄漏场景，单独封装反射和 token 记录逻辑。
    private val syncBarrierLeakScenario: SyncBarrierLeakScenario by lazy {
        SyncBarrierLeakScenario(context = this)
    }

    // BroadcastReceiver 超时场景，按钮只负责发送广播，真正阻塞入口在 Receiver。
    private val broadcastTimeoutScenario: BroadcastTimeoutScenario by lazy {
        BroadcastTimeoutScenario(context = this)
    }
```

and:

```kotlin
        findViewById<Button>(R.id.syncBarrierLeakButton).setOnClickListener {
            runSyncBarrierLeak()
        }
        findViewById<Button>(R.id.broadcastTimeoutButton).setOnClickListener {
            broadcastTimeoutScenario.run()
        }
```

- [x] **Step 4: Run focused tests and compile**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenarioTest
./gradlew :app:compileDebugKotlin :app:mergeDebugResources
```

Expected: both commands PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_main.xml app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入 BroadcastReceiver 超时按钮"
```

### Task 4: 更新使用说明中的 BroadcastReceiver 验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [x] **Step 1: Add button row**

In `docs-anr/103-ANR监控SDK使用说明.md`, update the Demo 页面按钮 table by adding this row after `Sync Barrier 泄漏 ANR`:

```markdown
| `BroadcastReceiver 超时` | 发送显式应用内广播，Receiver 主线程阻塞 12 秒 | `systemAnr.anrType=BROADCAST_*`、`BroadcastTimeoutReceiver.onReceive`、当前消息耗时 |
```

- [x] **Step 2: Add BroadcastReceiver section**

Add this section after the “Sync Barrier 泄漏 / nativePollOnce 场景” section and before “线上接入清单”:

````markdown
### BroadcastReceiver 超时场景

这个场景用于验证“系统广播组件超时”和“业务 Receiver 阻塞根因”要分开看。按钮本身只发送显式应用内广播，真正阻塞发生在 `BroadcastTimeoutReceiver.onReceive`。

操作步骤：

1. 安装 debug 包并打开 Demo App。
2. 点击“BroadcastReceiver 超时”。
3. 等待 12 秒左右，期间不要切到其他 App。
4. 观察 Logcat 中 `VibeAnrApplication` 是否输出 `suspect ANR captured`、`confirmed ANR report` 或 `ANR report written`。
5. 从设备拉取 `files/anr-monitor-reports/<eventId>.json`。

优先检查这些字段：

```text
mainThread.stackFrames 包含 BroadcastTimeoutReceiver.onReceive
mainThread.current.wallMs >= 3000
systemAnr.isConfirmedAnr = true 或 false
systemAnr.anrType = BROADCAST_FOREGROUND 或 BROADCAST_BACKGROUND 或 UNKNOWN
systemAnr.componentTimeoutMs = 10000 或 60000 或 null
barrierEvidence.stuckTokens = []
binderBlock.suspected = false
```

字段解释：

| 字段 | 期望 | 怎么理解 |
| --- | --- | --- |
| `mainThread.stackFrames` | 包含 `BroadcastTimeoutReceiver.onReceive` | 直接定位 Receiver 业务入口正在阻塞 |
| `systemAnr.isConfirmedAnr` | 可能为 `true` 或 `false` | `false` 代表 SDK 先抓到疑似 ANR，`true` 代表系统也确认了 ANR |
| `systemAnr.anrType` | 系统确认后应为 `BROADCAST_FOREGROUND` 或 `BROADCAST_BACKGROUND` | 说明系统等待广播完成通知超时 |
| `systemAnr.componentTimeoutMs` | 前台广播通常 `10000`，后台广播通常 `60000` | 用来解释系统为什么在该时间点确认 ANR |
| `attribution.primary` | 常见为 `CURRENT_MESSAGE_SLOW` | 表示当前主线程消息正在慢执行，业务根因仍要看栈 |
| `barrierEvidence.stuckTokens` | 空数组或不是主因 | 排除 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` | 排除 Binder / 跨进程等待 |

定位结论写法：

```text
本次 ANR 是 BroadcastReceiver 执行超时。系统侧证据是 systemAnr.anrType 命中 BROADCAST_FOREGROUND/BROADCAST_BACKGROUND 或广播完成通知未及时返回；业务根因证据是主线程栈包含 BroadcastTimeoutReceiver.onReceive，当前消息执行时间超过阈值。Barrier 和 Binder 证据不构成本次主因，因此根因是 Receiver 在主线程执行了耗时阻塞逻辑。
```

修复方向：

- `onReceive()` 中只做参数校验、轻量分发和状态记录，不执行耗时 IO、网络、锁等待或长计算。
- 必须异步处理时使用 `goAsync()` 获取 `PendingResult`，把耗时工作切到后台线程，并确保所有分支都会调用 `finish()`。
- 前台广播按 10 秒预算看待，实际业务应远低于该阈值，建议把主线程 Receiver 执行控制在数百毫秒内。
- 如果广播只是应用内事件，优先考虑更明确的进程内事件分发或后台任务，不要用 Receiver 承载复杂业务。
````

- [x] **Step 3: Check doc formatting**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 4: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充 BroadcastReceiver 超时验证说明"
```

### Task 5: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Update scenario overview row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the BroadcastReceiver row:

```markdown
| 6 | BroadcastReceiver 超时 | 发送显式广播，Receiver 阻塞 | Broadcast 组件超时 | `systemAnr.anrType`、Receiver 相关 ActivityThread 消息 | 待实现 |
```

with:

```markdown
| 6 | BroadcastReceiver 超时 | 点击“BroadcastReceiver 超时”后发送显式应用内广播，Receiver 主线程阻塞 12 秒 | Broadcast 组件超时 + 当前消息慢证据 | `systemAnr.anrType`、`mainThread.stackFrames` 包含 `BroadcastTimeoutReceiver.onReceive`、`mainThread.current.wallMs` | 已实现，待手动验收 |
```

- [x] **Step 2: Add sixth batch section**

Add this section after the fourth batch section and before “后续批次顺序”:

```markdown
## 第六批次：BroadcastReceiver 超时

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“BroadcastReceiver 超时”。
4. 等待 12 秒左右，直到日志出现 `suspect ANR captured`、`confirmed ANR report` 或 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `mainThread.stackFrames`，预期包含 `BroadcastTimeoutReceiver.onReceive`，这是业务根因入口。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。如果系统已经确认 ANR，再看 `systemAnr.anrType`，预期为 `BROADCAST_FOREGROUND` 或 `BROADCAST_BACKGROUND`；`componentTimeoutMs` 应与前台 10 秒或后台 60 秒广播阈值匹配。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果只看到 `systemAnr.anrType=BROADCAST_*`，但主线程栈不包含 `BroadcastTimeoutReceiver.onReceive`，需要继续对照 `history` 和 `stackSamples`，不能只凭系统组件类型下业务根因结论。

### 验收记录

- [ ] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenarioTest` 通过。
- [ ] `./gradlew :app:compileDebugKotlin :app:mergeDebugResources` 通过。
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [ ] 真机或模拟器点击“BroadcastReceiver 超时”后生成 JSON。
- [ ] JSON 中 `mainThread.stackFrames` 能定位到 `BroadcastTimeoutReceiver.onReceive`。
- [ ] 如果系统确认 ANR，JSON 中 `systemAnr.anrType` 为 `BROADCAST_FOREGROUND` 或 `BROADCAST_BACKGROUND`。
```

- [x] **Step 3: Update remaining roadmap sentence**

Replace:

```markdown
后续按锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。
```

with:

```markdown
后续按锁等待、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。
```

- [x] **Step 4: Check doc formatting**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新 BroadcastReceiver 超时场景矩阵"
```

### Task 6: 执行 BroadcastReceiver 超时最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Run full verification commands**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 2: Install debug build**

Run:

```bash
adb devices
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

Expected:

- `adb devices` shows one target device.
- install succeeds.
- app starts on Demo screen.

- [x] **Step 3: Trigger BroadcastReceiver timeout**

Tap the new button. If using the existing emulator layout and the button is below the first five buttons, use a coordinate near the lower part of the screen after the app opens:

```bash
adb -s <device-id> shell input tap 540 1716
```

If the button is not visible on a small screen, scroll/tap manually in the emulator, or adjust the coordinate after checking the screen.

Expected logcat examples:

```text
W VibeAnrApplication: suspect ANR captured: <eventId>
W VibeAnrApplication: ANR report written: <eventId>
```

If the system confirms the ANR before the app is killed or recovered, this log may also appear:

```text
W VibeAnrApplication: confirmed ANR report: <eventId>
```

- [x] **Step 4: Pull latest JSON**

Run:

```bash
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json > broadcast-timeout-<event-id>.json
```

If the device stores upper-case extension, use:

```bash
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON > broadcast-timeout-<event-id>.JSON
```

- [x] **Step 5: Inspect key JSON fields**

Run:

```bash
rg -n "\"primary\"|\"eventType\"|\"isConfirmedAnr\"|\"anrType\"|\"componentTimeoutMs\"|BroadcastTimeoutReceiver|\"wallMs\"|\"binderBlock\"|\"barrierEvidence\"" broadcast-timeout-<event-id>.json
```

Expected evidence:

```text
"eventType":"SUSPECT_ANR" 或 "eventType":"CONFIRMED_ANR"
"primary":"CURRENT_MESSAGE_SLOW" 或其他非 Barrier/Binder 主因
"isConfirmedAnr":true 或 false
"anrType":"BROADCAST_FOREGROUND" 或 "BROADCAST_BACKGROUND" 或 "UNKNOWN"
"componentTimeoutMs":10000 或 60000 或 null
"BroadcastTimeoutReceiver.onReceive"
"wallMs": 大于 3000
"suspected":false
"stuckTokens":[]
```

- [x] **Step 6: Record acceptance result**

Append this acceptance block to the sixth batch section in `docs-anr/105-Demo-ANR场景实现计划.md`, replacing `<...>` with real values:

````markdown
### 首次验收记录

验收时间：<YYYY-MM-DD HH:mm CST>

验收设备：`<device-id>`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s <device-id> shell input tap <x> <y>
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json
```

关键 JSON 字段：

```text
event.eventType = <SUSPECT_ANR 或 CONFIRMED_ANR>
attribution.primary = <实际主归因>
systemAnr.isConfirmedAnr = <true 或 false>
systemAnr.anrType = <BROADCAST_FOREGROUND / BROADCAST_BACKGROUND / UNKNOWN>
systemAnr.componentTimeoutMs = <10000 / 60000 / null>
mainThread.current.wallMs = <实际值>
mainThread.stackFrames contains BroadcastTimeoutReceiver.onReceive
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：BroadcastReceiver 超时场景验收通过。SDK 能捕获疑似或系统确认 ANR，JSON 能把系统广播组件超时和业务 Receiver 阻塞入口分开表达；根因可以明确写为“`BroadcastTimeoutReceiver.onReceive` 在主线程执行耗时阻塞，导致广播完成通知无法及时返回”。
````

- [x] **Step 7: Run final formatting check**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0`.

- [x] **Step 8: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录 BroadcastReceiver 超时场景验收"
```

## Manual Test Notes

BroadcastReceiver 超时比前几个按钮更容易受到系统版本影响。执行时注意：

- 前台广播常见阈值是 10 秒，本计划让 Receiver 阻塞 12 秒，目的是尽量稳定触发系统确认。
- 如果只生成疑似 ANR，没有生成 `systemAnr.isConfirmedAnr=true`，先确认 JSON 中是否已经能看到 `BroadcastTimeoutReceiver.onReceive`。这仍然说明 SDK 抓到了业务根因现场。
- 如果系统直接弹出 ANR 对话框或杀掉进程，先拉取已经写出的本地 JSON，再判断是否需要延长或缩短阻塞时长。
- 如果 `systemAnr.anrType=UNKNOWN`，不要直接判定失败；系统错误状态可能尚未返回或 ROM 文案不同。先看 `mainThread.stackFrames` 和 `history` 是否能定位 Receiver。

## Self-Review

### Spec Coverage

- 覆盖了 Demo 场景矩阵中的“BroadcastReceiver 超时”。
- 覆盖了应用内显式广播触发、Receiver 主线程阻塞、清单注册、按钮接线、使用说明、矩阵状态、手动验收记录。
- 明确了当前 SDK 不新增 `BROADCAST_TIMEOUT` 业务归因码，先用 `systemAnr.anrType` 表达系统组件类型，用主线程栈表达业务根因。

### Placeholder Scan

- 未留下待补内容、空泛实现要求或“稍后补充”式描述。
- 手动验收记录中的 `<device-id>`、`<event-id>` 是执行时必须替换的实际运行值，不属于未定义需求。

### Type Consistency

- `BroadcastTimeoutScenario.ACTION_BROADCAST_TIMEOUT` 在测试、发送器、Receiver 和 Manifest 中保持一致。
- `BroadcastSender.send(action: String)` 在测试和实现中签名一致。
- `BroadcastTimeoutBlocker.block()` 在测试和 Receiver 中调用一致。
- `BroadcastTimeoutScenario.expectedAttribution` 明确是说明字符串，不要求 SDK 新增同名归因枚举。

## 举一反三提问

1. 如果 JSON 中 `systemAnr.anrType=BROADCAST_FOREGROUND`，但主线程栈没有 `BroadcastTimeoutReceiver.onReceive`，能否直接判定 Receiver 业务逻辑就是根因？
2. 如果 SDK 只生成 `SUSPECT_ANR`，系统还没确认广播 ANR，这份 JSON 是否仍然能用于定位业务阻塞入口？
3. 如果 `attribution.primary=CURRENT_MESSAGE_SLOW`，是不是说明这不是 Broadcast ANR？
4. 前台广播 10 秒、后台广播 60 秒阈值不同，Demo 为什么选择阻塞 12 秒？线上分析时应该如何结合 `componentTimeoutMs`？
5. 如果 Receiver 内部调用 `goAsync()` 后后台线程忘记 `finish()`，JSON 里主线程栈可能不再停在 `onReceive`，那应该补哪些证据才能定位？
6. 如果广播消息还在 Pending 队列里，Receiver 根本没开始执行，根因应该写“Receiver 业务慢”还是“前序消息耗尽广播超时预算”？
7. 如果 `barrierEvidence.stuckTokens` 不为空，同时系统又是 Broadcast 超时，应该优先判断 Barrier 还是 Receiver 阻塞？
8. 如果 `binderBlock.suspected=true`，并且 Receiver 栈里有同步 Binder 调用，修复方向应该从 Receiver 轻量化还是跨进程调用超时治理开始？
9. Demo 使用显式应用内广播，真实线上还可能有系统广播、动态注册广播、有序广播，这些场景的分析口径哪些相同、哪些不同？
10. 修复 Receiver 后，除了“不再出现 ANR”，还应该验证哪些 JSON 字段来证明根因真的消失？

## 三轮审核

### 第一轮：需求覆盖审核

结论：通过。计划只覆盖矩阵中的“BroadcastReceiver 超时”单一场景，没有把 Service、Provider、Binder 或锁等待混入同一批次；并且明确按钮只是触发广播，真正根因入口必须从 `BroadcastTimeoutReceiver.onReceive` 或消息时间线中找。

补强点：计划把“系统组件类型”和“业务根因”拆开表达，避免新人看到 `systemAnr.anrType=BROADCAST_*` 就直接把 Receiver 名称当作根因。这个口径符合前面 01、02 文档里“ANR 是系统等待完成通知超时，系统 Reason 不等于业务根因”的原则。

### 第二轮：工程可执行性审核

结论：修正后通过。审核发现原计划中 `ContextBroadcastSender` 在 Task 1 就引用 `BroadcastTimeoutReceiver`，但 Receiver 原本放在 Task 2 创建，会导致 Task 1 的 `:app:testDebugUnitTest` 编译失败。现已把 `BroadcastTimeoutReceiver` 创建前移到 Task 1，Task 2 只负责 Manifest 注册，执行顺序可以闭环。

继续执行时要注意：`BroadcastTimeoutReceiver` 虽然在 Task 1 已创建，但只有 Task 2 注册 Manifest 后，真机点击按钮才会触发 Receiver；因此 Task 1 只能验证代码可编译和场景发送动作，不能提前做手动 ANR 验收。

### 第三轮：验收和评审口径审核

结论：通过。计划给出了单元测试、编译、安装、点击、logcat、拉取 JSON、字段检查和验收记录更新步骤，也允许 `SUSPECT_ANR` 与 `CONFIRMED_ANR` 两种合理结果，避免把系统确认时机差异误判为场景失败。

评审时建议固定使用这个结论：BroadcastReceiver 超时首先是系统等待广播完成通知超时，业务根因要看主线程现场和时间线。如果主线程栈包含 `BroadcastTimeoutReceiver.onReceive` 且当前消息耗时超过阈值，可以判定 Receiver 主线程阻塞；如果只看到 Broadcast 类型但没有 Receiver 栈，则必须继续对照 `history`、`stackSamples` 和 `pendingQueue`，判断是否是前序慢消息、Barrier、Binder 或消息堆积耗尽了广播超时预算。
