# Demo Service 超时场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“Service 超时”做成可测试、可复现、可通过 SDK JSON 区分“系统 Service 组件超时”和“Service 业务代码阻塞根因”的正式 ANR 场景。

**Architecture:** 本计划新增一个显式启动的应用内 Service 场景：按钮只负责调用 `ServiceTimeoutScenario.run()`，场景类只负责启动 `ServiceTimeoutService`，真正阻塞发生在 `ServiceTimeoutService.onStartCommand()`。为了让 JVM 单元测试稳定执行，Service 启动动作和 Service 内部阻塞动作都通过小接口拆开测试；真实 Demo 运行时使用 `Context.startService()` 启动非导出 Service，并在主线程阻塞 25 秒制造 Service 执行超时窗口。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`Service`、显式 `Intent`、`Context.startService()`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 7 的“Service 超时”场景。

本计划不实现主线程锁等待、Provider、Binder、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。BroadcastReceiver 超时已经有独立实现，本计划会复用它的拆分方式，但不会修改 BroadcastReceiver 行为。

## 场景边界

Service 超时和已有场景的区别必须在实现和 JSON 说明中保持清楚：

- 当前慢消息：阻塞发生在按钮点击消息内，入口是 `CurrentSlowInputScenario.run`。
- 主线程 CPU 忙等：阻塞发生在按钮点击消息内，CPU 证据更高，入口是 `MainThreadCpuBusyScenario.run`。
- 消息风暴：根因是 Pending 队列大量重复消息，入口是 `MessageStormScenario.run`。
- Sync Barrier 泄漏 / nativePollOnce：根因是队头 Sync Barrier，入口是 `SyncBarrierLeakScenario.run` 的 Barrier token 插入栈。
- BroadcastReceiver 超时：按钮只发送广播，根因入口落在 `BroadcastTimeoutReceiver.onReceive`。
- Service 超时：按钮只启动 Service，根因入口必须落在 `ServiceTimeoutService.onStartCommand`，系统侧可能额外确认 Service 组件超时。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenarioTest.kt`
  - JVM 单元测试，验证 Service 场景元数据、启动 action 和阻塞时长。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceStarter.kt`
  - 可注入 Service 启动器，测试中记录 action，真实 Demo 中启动显式 Service。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutBlocker.kt`
  - Service 内部的阻塞动作，默认主线程休眠 25 秒。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenario.kt`
  - Demo 场景类，负责启动 `ServiceTimeoutService`，并声明 JSON 预期证据。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/ServiceTimeoutService.kt`
  - Demo 专用阻塞 Service，`onStartCommand()` 收到专用 action 后阻塞。
- Modify: `app/src/main/AndroidManifest.xml`
  - 注册非导出的 `ServiceTimeoutService`。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增中文按钮文案 `demo_service_timeout`。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 新增“Service 超时”按钮。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 接入 `ServiceTimeoutScenario` 并绑定按钮点击。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“Service 超时场景”的新人验证步骤、JSON 字段读取口径、排除项和修复方向。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 把 Service 场景状态更新为已实现，并追加第七批次验收说明。

## Implementation Tasks

### Task 1: 用 TDD 实现 Service 超时场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceStarter.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutBlocker.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenario.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/ServiceTimeoutService.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenarioTest.kt`:

```kotlin
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
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenarioTest"
```

Expected: FAIL because `ServiceTimeoutScenario`, `ServiceStarter`, and `ServiceTimeoutBlocker` do not exist.

- [x] **Step 3: Create Service starter abstraction**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceStarter.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.valiantyan.vibeanrmonitoring.ServiceTimeoutService

/**
 * 可注入 Service 启动器，测试中记录 action，真实 Demo 中启动显式应用内 Service。
 */
fun interface ServiceStarter {
    /**
     * 使用指定 action 启动 Demo Service。
     *
     * @param action Service 启动 action。
     */
    fun start(action: String): Unit
}

/**
 * 使用应用上下文启动显式 Service，避免持有 Activity 并确保场景只在当前应用内触发。
 *
 * @param context 用于获取应用上下文并启动 Service。
 */
class ContextServiceStarter(
    context: Context,
) : ServiceStarter {
    // 使用 applicationContext，避免场景类长期持有 Activity。
    private val appContext: Context = context.applicationContext

    /**
     * 构造指向 [ServiceTimeoutService] 的显式 Intent，保证测试场景稳定命中 Demo Service。
     *
     * @param action Demo 专用 Service action。
     */
    override fun start(action: String): Unit {
        val intent: Intent = Intent(action).apply {
            component = ComponentName(appContext, ServiceTimeoutService::class.java)
            setPackage(appContext.packageName)
        }
        appContext.startService(intent)
    }
}
```

- [x] **Step 4: Create Service blocker**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutBlocker.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * Service 内部的阻塞动作，单独拆出便于 JVM 单元测试。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs Service 阻塞时长，默认超过常见 Service 执行超时窗口。
 */
class ServiceTimeoutBlocker(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    /**
     * 在 Service 所在线程阻塞，真实运行时就是主线程。
     */
    fun block(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        /**
         * 使用休眠制造 Service 生命周期回调长时间不返回的现场。
         *
         * @param durationMs 休眠时长，单位毫秒。
         */
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 25 秒，稳定覆盖常见前台进程 Service 执行超时窗口。
         */
        private const val DEFAULT_DURATION_MS: Long = 25_000L
    }
}
```

- [x] **Step 5: Create Service scenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 启动 Demo 专用阻塞 Service，用于复现 Service 生命周期执行超时。
 *
 * @param serviceStarter Service 启动器，测试中可替换为记录器。
 */
class ServiceTimeoutScenario(
    private val serviceStarter: ServiceStarter,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        serviceStarter = ContextServiceStarter(context = context),
    )

    override val id: String = "service_timeout"
    override val title: String = "Service 超时"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + SERVICE component evidence"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.stackFrames 包含 ServiceTimeoutService.onStartCommand",
        "mainThread.current.targetClass 包含 ActivityThread\$H",
        "systemAnr.anrType = SERVICE",
        "barrierEvidence.stuckTokens 不是主因",
    )

    /**
     * 启动 Demo 专用显式 Service。真正阻塞入口在 ServiceTimeoutService.onStartCommand。
     */
    override fun run(): Unit {
        serviceStarter.start(action = ACTION_SERVICE_TIMEOUT)
    }

    companion object {
        /**
         * Demo 专用 action，只用于应用内显式 Service。
         */
        const val ACTION_SERVICE_TIMEOUT: String =
            "com.valiantyan.vibeanrmonitoring.action.SERVICE_TIMEOUT"
    }
}
```

- [x] **Step 6: Create blocking Service**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/ServiceTimeoutService.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutBlocker
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenario

/**
 * Demo 专用阻塞 Service，用于复现 Service 生命周期执行超时。
 */
class ServiceTimeoutService : Service() {
    /**
     * 本场景不提供绑定能力，只验证 started Service 的 onStartCommand 超时。
     *
     * @param intent 绑定 Intent。
     * @return 始终返回 null。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 收到 Demo action 后在主线程阻塞，形成 Service 执行超时现场。
     *
     * @param intent 启动 Intent，只有 Demo action 才触发阻塞。
     * @param flags 系统启动标记。
     * @param startId 本次启动 id，用于阻塞结束后停止当前启动请求。
     * @return `START_NOT_STICKY`，避免进程恢复后自动重启 Demo 阻塞场景。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ServiceTimeoutScenario.ACTION_SERVICE_TIMEOUT) {
            return START_NOT_STICKY
        }
        ServiceTimeoutBlocker().block()
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
```

- [x] **Step 7: Run targeted test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenarioTest"
```

Expected: PASS.

- [x] **Step 8: Run all app JVM tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS, including existing CurrentSlowInput、MainThreadCpuBusy、MessageStorm、SyncBarrierLeak、BroadcastTimeout tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceStarter.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutBlocker.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ServiceTimeoutScenario.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/ServiceTimeoutService.kt
git commit -m "实现 Service 超时场景类"
```

### Task 2: 注册 Service 超时测试组件

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [x] **Step 1: Register ServiceTimeoutService**

Modify `app/src/main/AndroidManifest.xml` by adding this service entry inside `<application>` after `BarrierLeakService`:

```xml
        <service
            android:name=".ServiceTimeoutService"
            android:exported="false" />
```

The relevant manifest section should become:

```xml
        <service
            android:name=".BarrierLeakService"
            android:exported="false" />

        <service
            android:name=".ServiceTimeoutService"
            android:exported="false" />

        <receiver
            android:name=".BroadcastTimeoutReceiver"
            android:exported="false">
```

- [x] **Step 2: Validate manifest merge**

Run:

```bash
./gradlew :app:processDebugMainManifest
```

Expected: PASS. The merged manifest contains `com.valiantyan.vibeanrmonitoring.ServiceTimeoutService`.

- [x] **Step 3: Compile app Kotlin**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "注册 Service 超时测试组件"
```

### Task 3: 将 Service 超时按钮接入 Demo

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Add Chinese string**

Modify `app/src/main/res/values/strings.xml` by adding this string after `demo_broadcast_timeout`:

```xml
    <string name="demo_service_timeout">Service 超时</string>
```

The final strings block should include:

```xml
    <string name="demo_sync_barrier_leak">Sync Barrier 泄漏 ANR</string>
    <string name="demo_broadcast_timeout">BroadcastReceiver 超时</string>
    <string name="demo_service_timeout">Service 超时</string>
```

- [x] **Step 2: Add button**

Modify `app/src/main/res/layout/activity_main.xml` by adding this button after `broadcastTimeoutButton`:

```xml
    <Button
        android:id="@+id/serviceTimeoutButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/demo_service_timeout" />
```

The final bottom of the layout should be:

```xml
    <Button
        android:id="@+id/broadcastTimeoutButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/demo_broadcast_timeout" />

    <Button
        android:id="@+id/serviceTimeoutButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/demo_service_timeout" />
</LinearLayout>
```

- [x] **Step 3: Wire scenario in MainActivity**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`.

Add this import:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenario
```

Add this property after `broadcastTimeoutScenario`:

```kotlin
    // Service 超时场景，按钮只负责启动 Service，真正阻塞入口在 Service。
    private val serviceTimeoutScenario: ServiceTimeoutScenario by lazy {
        ServiceTimeoutScenario(context = this)
    }
```

Add this click listener after `broadcastTimeoutButton`:

```kotlin
        findViewById<Button>(R.id.serviceTimeoutButton).setOnClickListener {
            serviceTimeoutScenario.run()
        }
```

The relevant `onCreate()` tail should become:

```kotlin
        findViewById<Button>(R.id.syncBarrierLeakButton).setOnClickListener {
            runSyncBarrierLeak()
        }
        findViewById<Button>(R.id.broadcastTimeoutButton).setOnClickListener {
            broadcastTimeoutScenario.run()
        }
        findViewById<Button>(R.id.serviceTimeoutButton).setOnClickListener {
            serviceTimeoutScenario.run()
        }
```

- [x] **Step 4: Compile resources and Kotlin**

Run:

```bash
./gradlew :app:mergeDebugResources :app:compileDebugKotlin
```

Expected: PASS. If `serviceTimeoutButton` is missing from generated `R`, recheck `activity_main.xml`.

- [x] **Step 5: Run app JVM tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_main.xml app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入 Service 超时按钮"
```

### Task 4: 更新使用说明中的 Service 验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [x] **Step 1: Add Demo button table row**

In `docs-anr/103-ANR监控SDK使用说明.md`, update the Demo 页面按钮 table by adding this row after `BroadcastReceiver 超时`:

```markdown
| `Service 超时` | 启动显式应用内 Service，`onStartCommand()` 主线程阻塞 25 秒 | `mainThread.stackFrames` 包含 `ServiceTimeoutService.onStartCommand`、`systemAnr.anrType=SERVICE` 或 Service 相关 ActivityThread 消息 |
```

- [x] **Step 2: Add Service section**

Add this section after the “BroadcastReceiver 超时场景” section and before “线上接入清单”:

````markdown
### Service 超时场景

这个场景用于验证：如果 Service 生命周期回调在主线程执行耗时逻辑，SDK 是否能把根因定位到具体 Service，而不是只看到系统组件超时。

操作步骤：

1. 安装 Debug 包并打开 Demo App。
2. 点击“Service 超时”。
3. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
4. 如果想等待系统确认 Service ANR，可以继续等待到 20 秒以上；不同系统版本可能会直接弹 ANR 对话框，也可能只留下系统 traces。
5. 从设备拉取最新 JSON：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON > service-timeout.json
```

重点看这些字段：

| 字段 | 期望现象 | 含义 |
| --- | --- | --- |
| `event.eventType` | `SUSPECT_ANR` 或 `CONFIRMED_ANR` | SDK 已经捕获到 ANR 现场 |
| `mainThread.current.wallMs` | 大于 `3000`，如果等待更久可能超过 `20000` | 主线程当前消息执行时间过长 |
| `mainThread.current.targetClass` | 通常包含 `android.app.ActivityThread$H` | 当前阻塞发生在系统组件调度消息内 |
| `mainThread.stackFrames` | 包含 `ServiceTimeoutService.onStartCommand` 和 `ServiceTimeoutBlocker.block` | 业务根因入口是 Service 生命周期回调中阻塞 |
| `systemAnr.anrType` | 如果系统已确认，期望为 `SERVICE` | 系统侧确认这是 Service 组件超时 |
| `barrierEvidence.stuckTokens` | 空数组或不是主因 | 排除 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` | 排除 Binder / 跨进程阻塞 |

判断结论模板：

```text
本次 ANR 是 Service 生命周期执行超时。系统侧证据是 systemAnr.anrType 命中 SERVICE，或者主线程当前消息为 ActivityThread Service 组件调度；业务根因证据是主线程栈包含 ServiceTimeoutService.onStartCommand，当前消息执行时间超过阈值。Barrier 和 Binder 证据不构成本次主因，因此根因是 Service 在主线程生命周期回调中执行了耗时阻塞逻辑。
```

如果 `systemAnr.isConfirmedAnr=false`，不要直接否定这份报告。Debug 配置下 SDK 会在 3 秒左右先生成疑似 ANR，而系统 Service 超时确认通常更晚。此时先按 `mainThread.stackFrames` 和 `mainThread.current` 定位业务根因，再结合系统 traces 或后续确认报告复核组件类型。

修复方向：

- 不要在 `onCreate()`、`onStartCommand()`、`onBind()`、`onDestroy()` 中执行长时间阻塞、同步 IO、同步网络、锁等待或大计算。
- Service 收到启动请求后只做参数校验、状态切换和任务分发，把耗时工作交给后台线程、协程、WorkManager、JobScheduler 或前台服务中的后台执行链路。
- 如果必须等待后台任务结果，应改成异步回调或状态通知，不要让主线程等待 `Future.get()`、`CountDownLatch.await()`、`Thread.sleep()` 或长时间 synchronized 锁。
- 修复后重新点击“Service 超时”按钮验证：`mainThread.stackFrames` 不应再出现业务 Service 阻塞栈，`mainThread.current.wallMs` 应低于疑似 ANR 阈值。
````

- [x] **Step 3: Check docs format**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充 Service 超时验证说明"
```

### Task 5: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Update Service row**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the Service row:

```markdown
| 7 | Service 超时 | 启动阻塞 Service | Service 组件超时 | `systemAnr.anrType`、Service 相关 ActivityThread 消息 | 待实现 |
```

with:

```markdown
| 7 | Service 超时 | 点击“Service 超时”后启动显式应用内 Service，`onStartCommand()` 主线程阻塞 25 秒 | Service 组件超时 + 当前消息慢证据 | `systemAnr.anrType`、`mainThread.stackFrames` 包含 `ServiceTimeoutService.onStartCommand`、`mainThread.current.wallMs` | 已实现，待手动验收 |
```

- [x] **Step 2: Add seventh batch section**

Add this section after the BroadcastReceiver batch section:

````markdown
## 第七批次：Service 超时

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“Service 超时”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 如果要验证系统确认 Service ANR，继续等待 20 秒以上并查看系统 ANR traces。
6. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `event.eventType`，确认 SDK 已经生成疑似或确认报告。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.current.targetClass`，通常应包含 `ActivityThread$H`，说明阻塞发生在系统组件调度消息内。最后看 `mainThread.stackFrames`，应能看到 `ServiceTimeoutService.onStartCommand` 和 `ServiceTimeoutBlocker.block`，说明业务根因入口是 Service 生命周期回调。

如果 `systemAnr.isConfirmedAnr=true` 且 `systemAnr.anrType=SERVICE`，说明系统也确认了 Service 组件超时。如果 `systemAnr.isConfirmedAnr=false`，仍可先根据 SDK 疑似 ANR 报告定位业务根因，因为 SDK 阈值比系统 Service 超时阈值更早触发。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果主线程栈只看到按钮点击入口而没有 `ServiceTimeoutService.onStartCommand`，优先检查是否点错按钮，或 Service 是否没有在 Manifest 中注册成功。

### 首次验收记录

验收时间：待执行

验收设备：待执行

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s <device-id> shell input tap <service-timeout-button-x> <service-timeout-button-y>
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR 或 CONFIRMED_ANR
mainThread.current.wallMs >= 3000
mainThread.current.targetClass contains ActivityThread$H
mainThread.stackFrames contains ServiceTimeoutService.onStartCommand
mainThread.stackFrames contains ServiceTimeoutBlocker.block
barrierEvidence.stuckTokens = []
binderBlock.suspected = false
```

验收清单：

- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [ ] 真机或模拟器点击“Service 超时”后生成 JSON。
- [ ] JSON 中 `mainThread.stackFrames` 包含 `ServiceTimeoutService.onStartCommand`。
- [ ] JSON 中 `mainThread.current.wallMs >= 3000`。
- [ ] Barrier 和 Binder 证据均不是本次主因。
- [ ] 如果系统已确认 ANR，`systemAnr.anrType=SERVICE`。

验收结论：待执行。
````

- [x] **Step 3: Update follow-up order**

In the same file, update the final “后续批次顺序” sentence by removing Service from the remaining list. The sentence should become:

```markdown
后续按锁等待、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

- [x] **Step 4: Check docs format**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新 Service 超时场景矩阵"
```

### Task 6: 执行 Service 超时最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Run full local verification**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 2: Install debug app**

Run:

```bash
adb devices
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: app installs successfully.

- [x] **Step 3: Clear logs and launch app**

Run:

```bash
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

Expected: Demo App opens.

- [x] **Step 4: Trigger Service timeout**

Find the button bounds if needed:

```bash
adb -s <device-id> shell uiautomator dump /sdcard/window.xml
adb -s <device-id> shell cat /sdcard/window.xml | grep "Service 超时"
```

Tap the center of the “Service 超时” button:

```bash
adb -s <device-id> shell input tap <service-timeout-button-x> <service-timeout-button-y>
```

Expected: app freezes; logcat prints `suspect ANR captured` and `ANR report written`.

- [x] **Step 5: Capture log evidence**

Run:

```bash
adb -s <device-id> logcat -d | grep -E "VibeAnrApplication|ANR|ServiceTimeout"
```

Expected output includes lines similar to:

```text
W VibeAnrApplication: suspect ANR captured: <event-id>
W VibeAnrApplication: ANR report written: <event-id>
```

- [x] **Step 6: Pull latest JSON**

Run:

```bash
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON > /tmp/service-timeout-anr.JSON
```

Expected: `/tmp/service-timeout-anr.JSON` exists and contains a valid JSON report.

- [x] **Step 7: Inspect JSON root cause fields**

Run:

```bash
rg -n "\"eventType\"|\"primary\"|\"wallMs\"|\"targetClass\"|ServiceTimeoutService|ServiceTimeoutBlocker|\"anrType\"|\"stuckTokens\"|\"suspected\"" /tmp/service-timeout-anr.JSON
```

Expected key findings:

```text
"eventType":"SUSPECT_ANR" or "eventType":"CONFIRMED_ANR"
"primary":"CURRENT_MESSAGE_SLOW" or another component-supported current-message attribution
"wallMs": value >= 3000
"targetClass":"android.app.ActivityThread$H" or equivalent ActivityThread handler
ServiceTimeoutService.onStartCommand
ServiceTimeoutBlocker.block
"stuckTokens":[]
"suspected":false
```

If system confirmation is present, expected additional finding:

```text
"anrType":"SERVICE"
```

- [x] **Step 8: Update acceptance record**

In `docs-anr/105-Demo-ANR场景实现计划.md`, replace the Service batch placeholders:

```markdown
验收时间：待执行

验收设备：待执行
```

with actual values, for example:

```markdown
验收时间：2026-06-08 20:30 CST

验收设备：`emulator-5554`
```

Replace:

```markdown
验收结论：待执行。
```

with:

```markdown
验收结论：Service 超时场景验收通过。SDK 能捕获疑似或系统确认 ANR，JSON 能把系统 Service 组件调度和业务 Service 阻塞入口分开表达；根因可以明确写为“`ServiceTimeoutService.onStartCommand` 在主线程执行耗时阻塞，导致 Service 生命周期回调无法及时返回”。
```

Mark the checklist items according to actual result. If `systemAnr.anrType=SERVICE` was not present, keep that single item unchecked and add this sentence after the checklist:

```markdown
本次采样先命中 SDK 疑似 ANR 阈值，系统尚未确认 Service ANR；业务根因仍可由 `ServiceTimeoutService.onStartCommand` 和当前消息耗时确定。
```

- [x] **Step 9: Final docs check**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: no output.

- [ ] **Step 10: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录 Service 超时场景验收"
```

## Manual Testing Notes

Service 超时比普通按钮慢消息更容易受到系统版本影响。执行时注意：

- Debug 配置下 SDK 会在约 3 秒捕获疑似 ANR，系统 Service 超时确认通常更晚。
- 如果只拿到 `SUSPECT_ANR`，先根据 `mainThread.stackFrames` 定位根因，不要等待系统确认才开始分析。
- 如果系统弹出 ANR 对话框，先导出 SDK JSON，再决定是否结束 App；避免进程被杀后找不到新报告。
- 如果生成多份 JSON，优先看第一份包含 `ServiceTimeoutService.onStartCommand` 的报告；后续重复报告通常是同一次阻塞窗口的连续采样。

## Self-Review

### 1. Spec coverage

- 覆盖了 Demo 场景矩阵中的“Service 超时”。
- 覆盖了 TDD 测试、场景类、阻塞 Service、Manifest 注册、按钮接线、使用说明、矩阵更新和手动验收。
- 明确区分 SDK 疑似 ANR 与系统确认 Service ANR，避免新人误以为 `systemAnr.isConfirmedAnr=false` 就没有问题。
- 没有把 Provider、Binder、IO、锁等待或其他后续场景混入本批次。

### 2. Placeholder scan

计划正文没有实现步骤占位符。验收记录中的“待执行”只出现在 Task 5 要写入矩阵的初始记录中，并且 Task 6 明确要求用实际执行结果替换。

### 3. Type consistency

- `ServiceTimeoutScenario.ACTION_SERVICE_TIMEOUT` 在测试、场景类和 Service 中保持一致。
- `ServiceStarter.start(action: String)` 在测试记录器和 `ContextServiceStarter` 中保持一致。
- `ServiceTimeoutBlocker.block()` 和 `BlockingAction.block(durationMs: Long)` 与现有 `BroadcastTimeoutBlocker` 模式一致。
- `serviceTimeoutButton`、`demo_service_timeout`、`ServiceTimeoutScenario` 在布局、资源和 Activity 接线中保持一致。

## 举一反三提问

1. 如果 JSON 里 `systemAnr.isConfirmedAnr=false`，但 `mainThread.stackFrames` 已经包含 `ServiceTimeoutService.onStartCommand`，这份报告还能不能用于定位根因？

   可以。Debug 配置下 SDK 会在约 3 秒先生成疑似 ANR，而系统 Service 超时确认通常更晚。此时先把根因写成“Service 生命周期回调在主线程阻塞”，再等待系统 traces 或后续确认报告补充 `systemAnr.anrType=SERVICE`。

2. 如果 `attribution.primary=CURRENT_MESSAGE_SLOW`，为什么还能说这是 Service 超时场景？

   因为端侧归因先从“主线程当前消息是否慢”判断，Service 超时本质上也是 ActivityThread 调度 Service 生命周期消息时，主线程当前消息长时间不返回。判断组件类型要继续看 `mainThread.current.targetClass`、`mainThread.stackFrames` 和 `systemAnr.anrType`，不能只看 `attribution.primary` 一个字段。

3. 如果系统 ANR 日志里主线程栈顶是 `nativePollOnce`，这还能证明 Service 阻塞吗？

   不能直接证明。需要回到 SDK JSON 看 `mainThread.stackFrames`、`history`、`pendingQueue` 和 `barrierEvidence`。如果当前采样栈只有 `nativePollOnce`，但历史慢消息或前一份 JSON 指向 `ServiceTimeoutService.onStartCommand`，才可以把 Service 回调阻塞作为根因；如果队头 Barrier 证据完整，则要优先按 Sync Barrier 分析。

4. 为什么不把按钮点击里的 `Thread.sleep()` 当作 Service 超时？

   按钮点击里 sleep 只能复现“输入事件当前慢消息”，不能证明 Service 生命周期超时。Service 场景必须让按钮只启动 Service，真正阻塞落在 `ServiceTimeoutService.onStartCommand()`，这样 JSON 栈才能对准组件根因。

5. 为什么阻塞时长选择 25 秒，而不是 6 秒或 12 秒？

   6 秒适合输入事件或普通当前慢消息，12 秒适合 BroadcastReceiver 前台广播常见 10 秒窗口。Service 系统确认窗口通常更长，所以 Demo 选择 25 秒：SDK 会先产出疑似报告，继续等待时有机会观察系统 Service ANR 证据。

6. 如果点击“Service 超时”后没有生成 JSON，第一步查什么？

   先查 App 是否真的在前台、按钮是否触发、Manifest 是否注册 `ServiceTimeoutService`、logcat 是否有 `BackgroundServiceStartNotAllowedException` 或启动失败异常。这个场景要求从前台 Demo Activity 点击按钮启动 Service，不能在后台状态下验证。

7. 如果生成多份 JSON，应该分析哪一份？

   先按时间顺序找第一份包含 `ServiceTimeoutService.onStartCommand` 或 `ServiceTimeoutBlocker.block` 的 JSON。后续多份报告可能是同一次 25 秒阻塞窗口内的重复采样，适合作为补充证据，不适合当成多个独立 ANR。

8. 这个场景的修复结论应该怎么写？

   推荐写成：`ServiceTimeoutService.onStartCommand` 在主线程执行了长时间阻塞，导致 Service 生命周期消息无法及时返回；应把耗时工作迁移到后台执行链路，Service 回调只做轻量分发和状态更新。

## 三轮审核

### 第一轮：需求覆盖审核

结论：通过。

核对结果：

- 计划只覆盖“Service 超时”一个场景，没有把 Provider、Binder、锁等待、IO 或线程池场景混入本批次。
- 计划明确了 Service 场景和已有 BroadcastReceiver、Sync Barrier、消息风暴、当前慢消息之间的边界。
- 计划包含 TDD、实现、Manifest、按钮接线、使用说明、矩阵更新和最终验收，后续可以直接按 Task 1 到 Task 6 执行。
- 计划保留了系统确认不稳定的判断口径：`systemAnr.anrType=SERVICE` 是强证据，但不是唯一根因入口。

需要执行时重点关注：

- `startService()` 必须由前台 Demo Activity 点击触发，避免 Android 后台启动 Service 限制干扰验证。
- 如果系统没有确认 ANR，也要保存 SDK 疑似 JSON，并按主线程栈定位业务根因。

### 第二轮：代码可执行性审核

结论：通过，执行前无需拆分新计划。

核对结果：

- 测试代码中的 `ServiceTimeoutScenario`、`ServiceStarter`、`ServiceTimeoutBlocker`、`BlockingAction` 类型关系清楚，和现有 BroadcastReceiver 场景写法一致。
- `ServiceTimeoutService` 使用 `START_NOT_STICKY`，阻塞结束后调用 `stopSelf(startId)`，不会在进程恢复后自动重复触发 Demo 阻塞。
- Manifest 注册为 `android:exported="false"`，符合应用内 Demo 场景边界。
- `serviceTimeoutButton`、`demo_service_timeout`、`ServiceTimeoutScenario(context = this)` 命名一致，Activity 接线能直接编译。

需要执行时重点关注：

- Kotlin 文件创建时要加载 Kotlin 基础、函数、命名、文档、架构技能；`ServiceTimeoutService` 有系统回调和阻塞动作，注释要保持简洁。
- 如果 `./gradlew :app:compileDebugKotlin` 因 `Context.startService()` 或 Service 导入报错，优先对照 Task 1 的完整代码，不要顺手改动其他场景。

### 第三轮：验收和新人分析审核

结论：通过。

核对结果：

- 使用说明会告诉新人先看 `event.eventType`、再看 `mainThread.current`、再看 `mainThread.stackFrames`，最后用 `systemAnr` 补强组件类型。
- 计划明确了 Service 场景的关键根因栈：`ServiceTimeoutService.onStartCommand` 和 `ServiceTimeoutBlocker.block`。
- 计划明确了排除项：Barrier token 不应成为主因，Binder suspected 不应为 true。
- 计划提示了多份 JSON 的处理方式：优先找第一份包含 Service 根因栈的报告，后续重复报告作为补充证据。

需要执行时重点关注：

- 最终验收记录不要只写“生成 JSON”，必须写清楚 JSON 中哪些字段证明根因是 Service 回调阻塞。
- 如果 `systemAnr.anrType=SERVICE` 没出现，验收结论要写成“SDK 疑似 ANR 已定位业务根因，系统 Service 确认证据未命中”，不要伪造成系统确认。

## 审核后结论

这份计划可以进入实现阶段。执行顺序建议保持 Task 1 到 Task 6，不需要把 P0/P1/P2 再拆开；本场景的核心价值是补齐四大组件中的 Service 超时验证，并验证 SDK 能在系统确认前通过 JSON 找到业务根因入口。
