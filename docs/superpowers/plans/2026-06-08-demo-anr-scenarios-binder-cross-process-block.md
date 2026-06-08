# Demo Binder / 跨进程阻塞场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“Binder / 跨进程阻塞”做成可测试、可复现、可通过 SDK JSON 识别 `BINDER_BLOCK_SUSPECTED` 疑似归因的正式 ANR 场景。

**Architecture:** 本计划新增一个真正跨进程的 AIDL 绑定 Service：主进程 Activity 创建时预热绑定远端 `:remote` Service，点击按钮后在主线程同步调用远端进程的 `blockFor()`，远端 Service 在 Binder 线程阻塞，主线程停在 `BinderProxy.transact`。当前 SDK 只采集本进程 Binder 线程栈，无法直接看到远端 Binder 线程，因此计划先补强 SDK Binder 归因口径：主线程处于 Binder transact 即可输出 `BINDER_BLOCK_SUSPECTED`，本进程 Binder 线程等待只作为增强证据。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、AIDL、跨进程 bound `Service`、`BinderProxy.transact`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 9 的“Binder 跨进程阻塞”场景。

本计划不实现主线程锁等待、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。

本计划会包含一个 SDK 侧小修复：`BinderBlockClassifier` 目前要求“主线程 Binder 栈 + 本进程 Binder 线程等待栈”同时存在才输出 `suspected=true`。真实跨进程同步调用里，远端阻塞发生在 `:remote` 进程，主进程 SDK 通常只能看到主线程 `BinderProxy.transact`，看不到远端 Binder 线程，所以旧规则会漏报。本计划将其修正为：

- `mainThreadInBinder=true` 时即可认为 `suspected=true`。
- `binderThreadWaitsMain=true` 仍保留为更强辅助证据。
- `BINDER_BLOCK_SUSPECTED` 仍是“疑似”，不能写成确认死锁。

本计划会替换现有 `binderLikeButton` 的“本地锁模拟等待”行为。旧按钮只能制造 `Thread.sleep()` / monitor 等待，不能让主线程进入真实 `BinderProxy.transact`，不适合作为 Binder / 跨进程阻塞验收入口。

## 场景边界

Binder / 跨进程阻塞和已有场景的区别必须在实现和 JSON 说明中保持清楚：

- 输入事件当前慢消息：阻塞发生在按钮点击消息内，入口是 `CurrentSlowInputScenario.run`。
- 主线程 CPU 忙等：阻塞发生在按钮点击消息内，CPU 证据更高，入口是 `MainThreadCpuBusyScenario.run`。
- 消息风暴：根因是 Pending 队列大量重复消息，入口是 `MessageStormScenario.run`。
- Sync Barrier 泄漏 / nativePollOnce：根因是队头 Sync Barrier，入口是 `SyncBarrierLeakScenario.run` 的 Barrier token 插入栈。
- BroadcastReceiver 超时：按钮只发送广播，根因入口落在 `BroadcastTimeoutReceiver.onReceive`。
- Service 超时：按钮只启动本进程 Service，根因入口落在 `ServiceTimeoutService.onStartCommand`。
- ContentProvider 阻塞：按钮只发起 Provider 查询，根因入口落在 `BlockingContentProvider.query`。
- Binder / 跨进程阻塞：按钮调用远端进程 AIDL，主线程栈应命中 `BinderProxy.transact`，JSON 只能写“疑似跨进程阻塞”，不能写成“确认跨进程死锁”。

## 举一反三提问

实现和验收前先回答这些问题，避免场景做出来但 JSON 不能解释根因：

1. 为什么不能继续使用现有 `waitBinderLikeLock()`？
   - 因为它是本进程本地锁和 `Thread.sleep()`，主线程栈不会出现 `BinderProxy.transact`，无法验证 SDK 的 Binder 证据分支。
2. 为什么使用 AIDL，而不是 `Messenger`？
   - `Messenger.send()` 是异步 Binder 调用，调用方通常很快返回，不能稳定让主线程卡在同步 transact。AIDL 的普通方法是同步调用，更适合复现跨进程阻塞。
3. 为什么远端 Service 要放在 `:remote` 进程？
   - 同进程 `Binder` 可能走本地对象调用，不一定出现 `BinderProxy.transact`。独立进程可以稳定生成跨进程 Binder 栈。
4. 为什么 SDK 只输出 `BINDER_BLOCK_SUSPECTED`？
   - 端侧只能看到主线程 Binder 栈和当前进程 Binder 线程等待迹象，不能完整证明对端进程死锁。真实线上仍需要结合 system traces、Perfetto、对端日志复核。
5. 为什么要让主进程 Application 只安装一次 SDK？
   - 新增远端进程后，`Application.onCreate()` 也会在 `:remote` 进程执行。如果远端也安装 SDK，可能产生额外报告，污染新人分析样本。
6. 为什么点击前要预热绑定远端 Service？
   - `bindService()` 是异步的。如果第一次点击才开始绑定，本次点击只会弹“未就绪”，不会产生 Binder 阻塞样本。Activity 创建时预热绑定，手动测试时等 1 秒才有意义。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Modify: `app/build.gradle.kts`
  - 开启 AIDL 生成能力。
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifierTest.kt`
  - 增加真实跨进程调用只采到主线程 Binder 栈时的回归测试。
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifier.kt`
  - 调整 `suspected` 判断，避免真实跨进程阻塞漏报。
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`
  - 增加 Binder 线程等待证据缺失时的归因文案回归测试。
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`
  - 调整 Binder evidence 文案，避免凭空输出“Binder 线程等待主线程”。
- Create: `app/src/main/aidl/com/valiantyan/vibeanrmonitoring/IRemoteBlockingService.aidl`
  - 定义同步阻塞远端调用 `blockFor(long durationMs)`。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/RemoteBlockingService.kt`
  - Demo 专用远端进程 Service，收到 AIDL 调用后在 Binder 线程阻塞。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/RemoteBinderClient.kt`
  - 可注入远端 Binder 客户端接口和真实 `RemoteBlockingServiceClient` 实现。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenario.kt`
  - Demo 场景类，负责触发同步远端 Binder 阻塞，并声明 JSON 预期证据。
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenarioTest.kt`
  - JVM 单元测试，验证场景元数据、绑定准备、阻塞时长和失败提示。
- Modify: `app/src/main/AndroidManifest.xml`
  - 注册非导出的 `RemoteBlockingService`，并配置 `android:process=":remote"`。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/VibeAnrApplication.kt`
  - 只在主进程安装 ANR SDK，避免远端进程污染报告。
- Modify: `app/src/main/res/values/strings.xml`
  - 把 `demo_binder_like_lock` 改为正式中文按钮文案“Binder 跨进程阻塞”。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 接入 `BinderCrossProcessBlockScenario`，删除旧 `waitBinderLikeLock()`，并在 `onDestroy()` 释放绑定。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“Binder / 跨进程阻塞场景”的新人验证步骤、JSON 字段读取口径、排除项和修复方向。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 把 Binder 场景状态更新为已实现，并追加第九批次验收说明。

## Implementation Tasks

### Task 1: 修正 SDK Binder 疑似归因口径

**Files:**
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifierTest.kt`
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifier.kt`
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`

- [x] **Step 1: Write the failing test**

Replace the existing test named `classifyDoesNotSuspectWhenOnlyMainThreadIsInBinder` in `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifierTest.kt` with:

```kotlin
    @Test
    fun classifyReturnsSuspectedWhenOnlyMainThreadContainsBinderProxy(): Unit {
        val classifier = BinderBlockClassifier()

        val result = classifier.classify(
            mainFrames = listOf(
                "android.os.BinderProxy.transactNative(Native Method)",
                "android.os.BinderProxy.transact(BinderProxy.java:662)",
                "com.valiantyan.vibeanrmonitoring.scenario.BinderCrossProcessBlockScenario.run(BinderCrossProcessBlockScenario.kt:42)",
            ),
            binderThreadFrames = emptyList(),
        )

        assertTrue(result.suspected)
        assertTrue(result.mainThreadInBinder)
        assertFalse(result.binderThreadWaitsMain)
        assertEquals(
            listOf(
                "android.os.BinderProxy.transactNative(Native Method)",
                "android.os.BinderProxy.transact(BinderProxy.java:662)",
            ),
            result.mainThreadEvidence,
        )
        assertEquals(emptyList<String>(), result.binderThreadEvidence)
    }
```

The file already imports `assertFalse`; keep it because the new test still verifies `binderThreadWaitsMain=false`.

- [x] **Step 2: Add analyzer evidence regression**

Append this test to `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt` after `analyzeReturnsBinderBlockSuspectedWhenBinderEvidenceMatches`:

Add this import near the existing JUnit imports:

```kotlin
import org.junit.Assert.assertFalse
```

```kotlin
    @Test
    fun analyzeReturnsBinderBlockSuspectedWithoutClaimingMissingBinderThreadWait(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 6_000L,
                    cpuMs = 0L,
                ),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("android.os.BinderProxy.transactNative(Native Method)"),
                binderBlockSnapshot = BinderBlockSnapshot(
                    available = true,
                    suspected = true,
                    mainThreadInBinder = true,
                    binderThreadWaitsMain = false,
                    mainThreadEvidence = listOf("android.os.BinderProxy.transactNative(Native Method)"),
                    binderThreadEvidence = emptyList(),
                    failureReason = null,
                ),
            ),
        )

        assertEquals(AnrAttributionCode.BINDER_BLOCK_SUSPECTED, result.primaryCode)
        assertTrue(result.evidenceItems.contains("main thread blocked in Binder transact"))
        assertTrue(result.evidenceItems.contains("local binder thread wait evidence unavailable"))
        assertFalse(result.evidenceItems.contains("binder thread waits main or lock"))
    }
```

- [x] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.binder.BinderBlockClassifierTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest
```

Expected: FAIL because current `BinderBlockClassifier` only returns `suspected=true` when both main-thread Binder evidence and local Binder-thread wait evidence exist, and current analyzer evidence always contains `binder thread waits main or lock`.

- [x] **Step 4: Update BinderBlockClassifier**

Modify `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifier.kt` inside `classify()`:

```kotlin
        return BinderBlockSnapshot(
            available = true,
            suspected = mainEvidence.isNotEmpty(),
            mainThreadInBinder = mainEvidence.isNotEmpty(),
            binderThreadWaitsMain = binderEvidence.isNotEmpty(),
            mainThreadEvidence = mainEvidence,
            binderThreadEvidence = binderEvidence,
            failureReason = null,
        )
```

Keep the existing comment that this is only疑似 evidence. Update the old test comment from “仍不能判定跨进程阻塞疑似” to “仍应输出跨进程阻塞疑似，但缺少本进程 Binder 线程等待增强证据”。Do not rename `binderThreadWaitsMain`; it remains a stronger supporting signal, not a required condition.

- [x] **Step 5: Update AttributionAnalyzer Binder evidence**

Modify `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt` inside `binderEvidence()`:

```kotlin
    private fun binderEvidence(snapshot: BinderBlockSnapshot): List<String> {
        val evidence: MutableList<String> = mutableListOf("main thread blocked in Binder transact")
        if (snapshot.binderThreadWaitsMain) {
            evidence += "binder thread waits main or lock"
        } else {
            evidence += "local binder thread wait evidence unavailable"
        }
        evidence += snapshot.mainThreadEvidence.take(n = 1)
        evidence += snapshot.binderThreadEvidence.take(n = 1)
        return evidence
    }
```

This wording is intentionally conservative: main-thread Binder is enough for `BINDER_BLOCK_SUSPECTED`, but lack of local Binder-thread wait evidence must be visible to humans reading JSON.

- [x] **Step 6: Run Binder classifier tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.binder.BinderBlockClassifierTest
```

Expected: PASS.

- [x] **Step 7: Run attribution and encoder regression tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifierTest.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifier.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt
git commit -m "修正 Binder 跨进程阻塞疑似归因"
```

### Task 2: 开启 AIDL 并新增远端阻塞 Service

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/aidl/com/valiantyan/vibeanrmonitoring/IRemoteBlockingService.aidl`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/RemoteBlockingService.kt`

- [x] **Step 1: Enable AIDL build feature**

Modify `app/build.gradle.kts` inside the existing `android { ... }` block, after `buildTypes { ... }` and before `compileOptions { ... }`:

```kotlin
    buildFeatures {
        aidl = true
    }
```

- [x] **Step 2: Create the AIDL contract**

Create `app/src/main/aidl/com/valiantyan/vibeanrmonitoring/IRemoteBlockingService.aidl`:

```aidl
package com.valiantyan.vibeanrmonitoring;

interface IRemoteBlockingService {
    void blockFor(long durationMs);
}
```

- [x] **Step 3: Create the remote blocking Service**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/RemoteBlockingService.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Demo 专用远端进程 Service，用于复现主进程同步 Binder 调用被远端阻塞。
 */
class RemoteBlockingService : Service() {
    private val binder: IRemoteBlockingService.Stub = object : IRemoteBlockingService.Stub() {
        /**
         * 在远端 Binder 线程阻塞指定时长，让主进程调用方停在 BinderProxy.transact。
         */
        override fun blockFor(durationMs: Long): Unit {
            Log.w(TAG, "remote binder block start: durationMs=$durationMs")
            Thread.sleep(durationMs)
            Log.w(TAG, "remote binder block end")
        }
    }

    /**
     * 返回 AIDL Binder stub，调用方会拿到跨进程代理。
     */
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private companion object {
        /**
         * 远端 Binder 场景日志标签。
         */
        private const val TAG: String = "RemoteBlockingService"
    }
}
```

- [x] **Step 4: Compile to verify AIDL generation**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS. If this fails with `Unresolved reference: IRemoteBlockingService`, verify the AIDL package path is exactly `app/src/main/aidl/com/valiantyan/vibeanrmonitoring/IRemoteBlockingService.aidl`.

- [x] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/aidl/com/valiantyan/vibeanrmonitoring/IRemoteBlockingService.aidl app/src/main/java/com/valiantyan/vibeanrmonitoring/RemoteBlockingService.kt
git commit -m "新增 Binder 跨进程阻塞远端服务"
```

### Task 3: 用 TDD 实现 Binder 跨进程场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/RemoteBinderClient.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenario.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Binder 跨进程阻塞场景的元数据、绑定准备和远端阻塞调用。
 */
class BinderCrossProcessBlockScenarioTest {
    @Test
    fun initPreparesRemoteBinderConnection(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()

        BinderCrossProcessBlockScenario(remoteBinderClient = client)

        assertEquals(1, client.ensureBoundCount)
    }

    @Test
    fun runBlocksRemoteBinderForConfiguredDuration(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        val scenario: BinderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(
            remoteBinderClient = client,
            durationMs = 7_654L,
        )

        scenario.run()

        assertEquals(listOf(7_654L), client.blockedDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        val scenario: BinderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(
            remoteBinderClient = client,
        )

        assertEquals("binder_cross_process_block", scenario.id)
        assertEquals("Binder / 跨进程阻塞", scenario.title)
        assertEquals("BINDER_BLOCK_SUSPECTED", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("attribution.primary = BINDER_BLOCK_SUSPECTED"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected = true"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.mainThreadInBinder = true"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 BinderProxy.transact"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun releaseDelegatesToRemoteClient(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        val scenario: BinderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(
            remoteBinderClient = client,
        )

        scenario.release()

        assertEquals(1, client.releaseCount)
    }

    private class RecordingRemoteBinderClient : RemoteBinderClient {
        var ensureBoundCount: Int = 0
        var releaseCount: Int = 0
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun ensureBound(): Unit {
            ensureBoundCount += 1
        }

        override fun blockRemote(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }

        override fun release(): Unit {
            releaseCount += 1
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BinderCrossProcessBlockScenarioTest
```

Expected: FAIL because `BinderCrossProcessBlockScenario` and `RemoteBinderClient` do not exist.

- [x] **Step 3: Create the remote Binder client abstraction**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/RemoteBinderClient.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.valiantyan.vibeanrmonitoring.IRemoteBlockingService
import com.valiantyan.vibeanrmonitoring.RemoteBlockingService

/**
 * 可注入远端 Binder 客户端，测试中记录调用，真实 Demo 中绑定跨进程 Service。
 */
interface RemoteBinderClient {
    /**
     * 确保远端 Service 已开始绑定。
     */
    fun ensureBound(): Unit

    /**
     * 同步调用远端阻塞方法。
     *
     * @param durationMs 远端阻塞时长，单位毫秒。
     */
    fun blockRemote(durationMs: Long): Unit

    /**
     * 释放 Service 绑定。
     */
    fun release(): Unit
}

/**
 * 真实跨进程 Binder 客户端，主线程调用 [blockRemote] 时会停在 BinderProxy.transact。
 *
 * @param context 用于绑定远端 Service。
 * @param failureNotifier 绑定未完成或远端异常时提示用户本次没有产生有效 Binder 样本。
 */
class RemoteBlockingServiceClient(
    context: Context,
    private val failureNotifier: ScenarioFailureNotifier,
) : RemoteBinderClient {
    private val appContext: Context = context.applicationContext
    private var remoteService: IRemoteBlockingService? = null
    private var isBinding: Boolean = false
    private var isReleased: Boolean = false

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?,
        ): Unit {
            remoteService = IRemoteBlockingService.Stub.asInterface(service)
            isBinding = false
            Log.w(TAG, "remote binder service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?): Unit {
            remoteService = null
            isBinding = false
            Log.w(TAG, "remote binder service disconnected")
        }
    }

    /**
     * 开始绑定远端 Service。重复调用不会重复 bind。
     */
    override fun ensureBound(): Unit {
        if (isReleased || remoteService != null || isBinding) {
            return
        }
        isBinding = true
        val intent: Intent = Intent(appContext, RemoteBlockingService::class.java)
        val bound: Boolean = appContext.bindService(
            intent,
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            isBinding = false
            failureNotifier.notifyFailure(
                message = "Remote binder service bind failed",
                error = null,
            )
        }
    }

    /**
     * 同步调用远端阻塞方法。若 Service 还未连接，只提示用户重试，不制造伪样本。
     */
    override fun blockRemote(durationMs: Long): Unit {
        val service: IRemoteBlockingService = remoteService ?: run {
            ensureBound()
            failureNotifier.notifyFailure(
                message = "Remote binder service is not connected, please tap again",
                error = null,
            )
            return
        }
        try {
            service.blockFor(durationMs)
        } catch (error: RemoteException) {
            remoteService = null
            failureNotifier.notifyFailure(
                message = "Remote binder call failed",
                error = error,
            )
            ensureBound()
        }
    }

    /**
     * 释放绑定，避免 Activity 销毁后泄漏 ServiceConnection。
     */
    override fun release(): Unit {
        if (isReleased) {
            return
        }
        isReleased = true
        runCatching {
            appContext.unbindService(connection)
        }.onFailure { error: Throwable ->
            Log.w(TAG, "unbind remote binder service failed: ${error.message}")
        }
        remoteService = null
        isBinding = false
    }

    private companion object {
        /**
         * Binder 场景日志标签。
         */
        private const val TAG: String = "BinderCrossProcess"
    }
}
```

- [x] **Step 4: Create Binder scenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 通过同步 AIDL 调用复现 Binder / 跨进程阻塞。
 *
 * @param remoteBinderClient 远端 Binder 客户端，测试中可替换为记录器。
 * @param durationMs 远端进程阻塞时长。
 */
class BinderCrossProcessBlockScenario(
    private val remoteBinderClient: RemoteBinderClient,
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        remoteBinderClient = RemoteBlockingServiceClient(
            context = context,
            failureNotifier = BinderScenarioFailureNotifier(context = context),
        ),
    )

    init {
        remoteBinderClient.ensureBound()
    }

    override val id: String = "binder_cross_process_block"
    override val title: String = "Binder / 跨进程阻塞"
    override val expectedAttribution: String = "BINDER_BLOCK_SUSPECTED"
    override val expectedJsonSignals: List<String> = listOf(
        "attribution.primary = BINDER_BLOCK_SUSPECTED",
        "binderBlock.suspected = true",
        "binderBlock.mainThreadInBinder = true",
        "mainThread.stackFrames 包含 BinderProxy.transact",
        "mainThread.stackFrames 包含 BinderCrossProcessBlockScenario.run",
        "barrierEvidence.stuckTokens 不是主因",
    )

    /**
     * 在主线程同步调用远端 AIDL，让当前点击消息卡在 Binder transact。
     */
    override fun run(): Unit {
        remoteBinderClient.blockRemote(durationMs = durationMs)
    }

    /**
     * Activity 销毁时释放远端 Service 绑定。
     */
    fun release(): Unit {
        remoteBinderClient.release()
    }

    private companion object {
        /**
         * 默认阻塞 12 秒，足够覆盖 Demo 的 suspectAnrMs=3000，同时避免等待过久。
         */
        private const val DEFAULT_DURATION_MS: Long = 12_000L
    }
}
```

- [x] **Step 5: Create Binder-specific failure notifier**

Append this class to `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/RemoteBinderClient.kt` after `RemoteBlockingServiceClient`:

```kotlin
/**
 * Binder 场景失败提示器，避免 Service 未连接时用户误以为已经制造了跨进程阻塞。
 */
class BinderScenarioFailureNotifier(
    private val context: Context,
) : ScenarioFailureNotifier {
    /**
     * 同时输出 Logcat 和 Toast，方便测试者知道本次没有产生有效 Binder 样本。
     */
    override fun notifyFailure(
        message: String,
        error: Throwable?,
    ): Unit {
        Log.e(TAG, "Binder 跨进程阻塞场景触发失败: $message", error)
        android.widget.Toast.makeText(
            context,
            "Binder 场景未就绪，请稍后再点一次",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private companion object {
        /**
         * Binder 场景日志标签。
         */
        private const val TAG: String = "BinderCrossProcess"
    }
}
```

- [x] **Step 6: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BinderCrossProcessBlockScenarioTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/RemoteBinderClient.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/BinderCrossProcessBlockScenario.kt
git commit -m "新增 Binder 跨进程阻塞场景类"
```

### Task 4: 注册远端 Service 并限制 SDK 只在主进程安装

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/VibeAnrApplication.kt`

- [x] **Step 1: Register remote Service**

Modify `app/src/main/AndroidManifest.xml` inside `<application>` after `ServiceTimeoutService`:

```xml
        <service
            android:name=".RemoteBlockingService"
            android:exported="false"
            android:process=":remote" />
```

- [x] **Step 2: Add main-process guard in Application**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/VibeAnrApplication.kt` so the file content becomes:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.app.Application
import android.os.Build
import android.util.Log
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

/**
 * Demo 应用入口，在 debug 主进程启动 ANR SDK 并把关键事件写入 logcat。
 */
class VibeAnrApplication : Application() {
    /**
     * 应用启动时安装 ANR SDK，确保 Activity 场景按钮触发前采集链路已经就绪。
     */
    override fun onCreate(): Unit {
        super.onCreate()
        if (!isMainProcess()) {
            Log.w(TAG, "skip ANR monitor in process=${currentProcessName()}")
            return
        }
        AnrMonitor.install(
            context = this,
            config = AnrMonitorConfig(
                appId = "vibe-anr-demo",
                environment = "debug",
                enabled = true,
                uploadEnabled = false,
                sampleRate = 1.0f,
                suspectAnrMs = 3_000L,
                watchdogIntervalMs = 500L,
                captureBarrierEvidence = true,
                barrierTokenStuckThresholdMs = 2_000L,
                captureBinderEvidence = true,
            ),
            listener = object : AnrEventListener {
                /**
                 * 疑似 ANR 现场捕获后输出事件 ID，便于手动验收对齐报告文件。
                 */
                override fun onSuspectAnr(snapshot: AnrSnapshot): Unit {
                    Log.w(TAG, "suspect ANR captured: ${snapshot.eventId}")
                }

                /**
                 * 完整报告生成后输出事件 ID，便于确认本地 JSON 已完成编码。
                 */
                override fun onConfirmedAnr(report: AnrReport): Unit {
                    Log.w(TAG, "ANR report written: ${report.snapshot.eventId}")
                }

                /**
                 * SDK 内部异常只记录到 logcat，避免 demo 进程因为监控失败而崩溃。
                 */
                override fun onMonitorError(error: Throwable): Unit {
                    Log.e(TAG, "ANR monitor error: ${error.message}", error)
                }
            },
        )
    }

    private fun isMainProcess(): Boolean {
        return currentProcessName() == packageName
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid: Int = android.os.Process.myPid()
        val activityManager: android.app.ActivityManager? = getSystemService(
            android.app.ActivityManager::class.java,
        )
        return activityManager
            ?.runningAppProcesses
            ?.firstOrNull { processInfo: android.app.ActivityManager.RunningAppProcessInfo ->
                processInfo.pid == pid
            }
            ?.processName
            ?: packageName
    }

    private companion object {
        /**
         * Application 级 SDK 日志标签。
         */
        private const val TAG: String = "VibeAnrApplication"
    }
}
```

- [x] **Step 3: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [x] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/valiantyan/vibeanrmonitoring/VibeAnrApplication.kt
git commit -m "限制 Demo SDK 只在主进程启动"
```

### Task 5: 将 Binder 跨进程按钮接入 Demo

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Update button text**

Modify `app/src/main/res/values/strings.xml`:

```xml
    <string name="demo_binder_like_lock">Binder 跨进程阻塞</string>
```

- [x] **Step 2: Wire MainActivity to the scenario**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt` so these changes are present:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.BinderCrossProcessBlockScenario
```

Add this property after `mainThreadCpuBusyScenario`:

```kotlin
    // Binder 跨进程阻塞场景，点击时主线程同步调用远端 AIDL。
    private lateinit var binderCrossProcessBlockScenario: BinderCrossProcessBlockScenario
```

Initialize it in `onCreate()` immediately after `setContentView(R.layout.activity_main)`:

```kotlin
        binderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(context = this)
```

Change the existing `binderLikeButton` click listener:

```kotlin
        findViewById<Button>(R.id.binderLikeButton).setOnClickListener {
            binderCrossProcessBlockScenario.run()
        }
```

Remove the old `waitBinderLikeLock()` function completely.

Add this lifecycle method before `runSyncBarrierLeak()`:

```kotlin
    /**
     * Activity 销毁时释放 Binder 远端服务绑定。
     */
    override fun onDestroy(): Unit {
        if (::binderCrossProcessBlockScenario.isInitialized) {
            binderCrossProcessBlockScenario.release()
        }
        super.onDestroy()
    }
```

- [x] **Step 3: Compile app**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [x] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入 Binder 跨进程阻塞按钮"
```

### Task 6: 更新使用说明和场景矩阵

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Add usage guide section**

Append this section to `docs-anr/103-ANR监控SDK使用说明.md` after the ContentProvider section:

```markdown
## Binder / 跨进程阻塞场景怎么验证

### 触发步骤

1. 安装 debug 包并打开 Demo App。
2. 等待 1 秒，让主进程完成远端 `:remote` Service 绑定。
3. 点击“Binder 跨进程阻塞”。
4. 如果弹出“Binder 场景未就绪，请稍后再点一次”，说明远端 Service 还没连接完成；等 1 秒后再点一次。
5. 等待 Logcat 出现 `suspect ANR captured` 和 `ANR report written`。
6. 从 `files/anr-monitor-reports` 拉取最新 JSON。

### JSON 先看什么

先看：

```text
attribution.primary = BINDER_BLOCK_SUSPECTED
binderBlock.suspected = true
binderBlock.mainThreadInBinder = true
```

再看 `mainThread.stackFrames`，应能看到 `android.os.BinderProxy.transact` 或 `android.os.BinderProxy.transactNative`，并能看到 Demo 入口 `BinderCrossProcessBlockScenario.run`。

然后看 `mainThread.current.wallMs`，应超过 `suspectAnrMs=3000`。这说明当前点击消息不是普通慢代码，而是卡在同步 Binder 调用返回前。

### 怎么写根因

可以写：

```text
主线程在点击“Binder 跨进程阻塞”后同步调用远端 AIDL，当前消息 wall time 超过阈值，主线程栈命中 BinderProxy.transact，binderBlock 输出 suspected=true 和 mainThreadInBinder=true。因此本次 ANR 属于 Binder / 跨进程阻塞疑似，端侧根因入口是 BinderCrossProcessBlockScenario.run 发起的同步远端调用。
```

不要写成：

```text
已经确认跨进程死锁。
```

因为端侧 JSON 只能证明主线程卡在 Binder 调用及当前进程存在 Binder 疑似证据，是否死锁还需要 system traces、Perfetto 或对端进程日志复核。

### 排除项

- `barrierEvidence.stuckTokens` 不应成为主因。
- `pendingQueue.messages[0].isBarrierLike` 不应作为主证据。
- 如果 `binderBlock.available=false`，说明 Binder 证据不可用，不能用这份 JSON 证明 Binder 阻塞。
- 如果主线程栈没有 `BinderProxy.transact`，优先检查是否点到了旧包、远端 Service 未连接，或当前报告不是这次按钮产生的最新 JSON。

### 修复方向

- 不要在主线程发起不可控耗时的同步跨进程调用。
- 对必须跨进程的能力，改为异步调用、超时取消、后台线程调用或缓存上一次结果。
- 对远端服务增加超时保护和降级结果，避免调用方无限等待。
- 线上排查时需要同时拿调用方日志、对端进程日志和系统 traces，确认阻塞发生在对端业务、系统服务还是调用方错误地等待结果。
```

- [x] **Step 2: Update scenario matrix row**

Modify row 9 in `docs-anr/105-Demo-ANR场景实现计划.md`:

```markdown
| 9 | Binder 跨进程阻塞 | 点击“Binder 跨进程阻塞”后主线程同步调用远端 `:remote` AIDL，远端 Binder 线程阻塞 12 秒 | `BINDER_BLOCK_SUSPECTED` | `binderBlock.suspected=true`、`binderBlock.mainThreadInBinder=true`、`mainThread.stackFrames` 包含 `BinderProxy.transact` | 已实现，待手动验收 |
```

- [x] **Step 3: Add batch section to scenario matrix**

Append this section to `docs-anr/105-Demo-ANR场景实现计划.md`:

```markdown
## 第九批次：Binder / 跨进程阻塞

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 等待 1 秒，让远端 `:remote` Service 完成绑定。
4. 点击“Binder 跨进程阻塞”。
5. 如果弹出未就绪提示，等 1 秒后再点击一次。
6. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
7. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `BINDER_BLOCK_SUSPECTED`。再看 `binderBlock.suspected` 和 `binderBlock.mainThreadInBinder`，都应为 `true`。接着看 `mainThread.stackFrames`，应能看到 `BinderProxy.transact` 或 `BinderProxy.transactNative`，并能回溯到 `BinderCrossProcessBlockScenario.run`。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `pendingQueue.messages[0].isBarrierLike` 不应该作为主证据。
- 如果 `binderBlock.available=false`，不能用这份 JSON 排除或证明 Binder。
- 如果只有 `CURRENT_MESSAGE_SLOW`，但主线程栈没有 Binder transact，应检查是否没有真正连上远端 Service。

### 验收记录模板

验收时间：2026-06-08 HH:mm CST

验收设备：`<device-id>`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s <device-id> shell input tap <binder-button-x> <binder-button-y>
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = BINDER_BLOCK_SUSPECTED
binderBlock.available = true
binderBlock.suspected = true
binderBlock.mainThreadInBinder = true
mainThread.stackFrames contains BinderProxy.transact
mainThread.stackFrames contains BinderCrossProcessBlockScenario.run
barrierEvidence.stuckTokens = []
```

验收结论：待执行后填写。
```

- [x] **Step 4: Check markdown formatting**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 5: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新 Binder 跨进程阻塞验证说明"
```

### Task 7: 执行 Binder 跨进程最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [ ] **Step 1: Run full local verification**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Install debug APK**

Run:

```bash
adb devices
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

- [ ] **Step 3: Trigger Binder scenario**

Run:

```bash
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

Wait 1 second, then tap the “Binder 跨进程阻塞” button:

```bash
adb -s <device-id> shell input tap <binder-button-x> <binder-button-y>
```

Expected Logcat:

```text
W RemoteBlockingService: remote binder block start: durationMs=12000
W VibeAnrApplication: suspect ANR captured: <event-id>
W VibeAnrApplication: ANR report written: <event-id>
```

- [ ] **Step 4: Pull the latest report**

Run:

```bash
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON > /tmp/binder-cross-process-anr.json
```

Expected: report file exists and contains `binderBlock`.

- [ ] **Step 5: Verify JSON evidence manually**

Open `/tmp/binder-cross-process-anr.json` and verify these exact signals:

```text
event.eventType = SUSPECT_ANR
attribution.primary = BINDER_BLOCK_SUSPECTED
binderBlock.available = true
binderBlock.suspected = true
binderBlock.mainThreadInBinder = true
mainThread.stackFrames contains BinderProxy.transact or BinderProxy.transactNative
mainThread.stackFrames contains BinderCrossProcessBlockScenario.run
barrierEvidence.stuckTokens = []
```

If `attribution.primary=CURRENT_MESSAGE_SLOW` but `binderBlock.mainThreadInBinder=true`, record it as a SDK attribution gap and do not mark the task complete until `AttributionAnalyzer` priority or Binder evidence conditions are reviewed.

- [ ] **Step 6: Update acceptance record**

Replace the “验收结论：待执行后填写。” line in the new Binder section of `docs-anr/105-Demo-ANR场景实现计划.md` with the actual event id and key fields:

```markdown
验收结论：Binder / 跨进程阻塞场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `BINDER_BLOCK_SUSPECTED`，`binderBlock.suspected=true`，主线程栈命中 `BinderProxy.transact` 并能回溯到 `BinderCrossProcessBlockScenario.run`。Barrier 证据不是本次主因，因此本次可以写为“主线程同步 Binder 调用远端进程时等待返回，属于跨进程阻塞疑似”，不能写为“已确认跨进程死锁”。
```

- [ ] **Step 7: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录 Binder 跨进程阻塞场景验收结果"
```

## Self-Review

### 1. Spec coverage

- 覆盖了总矩阵中的“Binder / 跨进程阻塞”场景。
- 覆盖了真实跨进程 Binder 复现，而不是旧的本地模拟等待。
- 覆盖了 SDK JSON 的 `BINDER_BLOCK_SUSPECTED`、`binderBlock.suspected`、`mainThreadInBinder`、`BinderProxy.transact` 证据链。
- 覆盖了远端进程导致的 SDK 重复安装风险，并要求主进程保护。
- 覆盖了新人使用说明、矩阵状态和手动验收记录。

### 2. Placeholder scan

- 本计划没有未展开的占位词或“稍后补充”类步骤。
- 每个代码创建步骤都给出完整文件内容或明确替换片段。
- 每个测试步骤都给出精确 Gradle 命令和预期结果。

### 3. Type consistency

- `BinderCrossProcessBlockScenario` 使用 `RemoteBinderClient`。
- `RemoteBlockingServiceClient` 使用 AIDL 生成的 `IRemoteBlockingService`。
- `RemoteBlockingService` 实现 `IRemoteBlockingService.Stub`。
- `MainActivity` 调用 `BinderCrossProcessBlockScenario.run()` 和 `release()`。
- 文档中的归因码统一为 `BINDER_BLOCK_SUSPECTED`。
