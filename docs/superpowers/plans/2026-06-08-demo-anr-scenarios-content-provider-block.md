# Demo ContentProvider 阻塞场景 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中把“ContentProvider 阻塞”做成可测试、可复现、可通过 SDK JSON 区分“Provider 组件调用链”和“Provider 业务代码阻塞根因”的正式 ANR 场景。

**Architecture:** 本计划新增一个应用内 ContentProvider 场景：按钮只负责调用 `ContentProviderBlockScenario.run()`，场景类只负责通过 `ContentResolver.query()` 查询 Demo Provider，真正阻塞发生在 `BlockingContentProvider.query()`。为了让 JVM 单元测试稳定执行，Provider 查询动作和 Provider 内部阻塞动作都通过小接口拆开测试；真实 Demo 运行时使用非导出 Provider，并在 `query()` 中主线程阻塞 12 秒制造 Provider 调用阻塞窗口。Demo 页面会改成可滚动布局，确保第 8 个按钮在小屏设备上也能被手动点击。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`ContentProvider`、`ContentResolver.query()`、`MatrixCursor`、本地 JSON 报告。

---

## Scope Check

本计划只实现 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 8 的“ContentProvider 阻塞”场景。

本计划不实现主线程锁等待、Binder、IO、线程池、GC 或后台 CPU 竞争场景。那些场景继续保持“后续单独计划”。

本计划也不新增 SDK 归因枚举。Provider 阻塞在 SDK 疑似 ANR 阶段通常仍会表现为 `attribution.primary=CURRENT_MESSAGE_SLOW`，系统是否额外确认 Provider 组件 ANR 取决于 Android 版本、触发时机和系统 traces 可见性。当前 SDK 已有 `AnrType.PROVIDER` 和默认 `componentTimeoutMs=10000` 的消费模型，所以 Demo 验收时允许两种结果：

- 疑似 ANR 阶段：`event.eventType=SUSPECT_ANR`，`systemAnr.isConfirmedAnr=false`，业务根因必须能从 `mainThread.stackFrames` 定位到 `BlockingContentProvider.query` 和 `ContentProviderBlocker.block`。
- 系统确认阶段：`systemAnr.isConfirmedAnr=true`，如果系统能识别 Provider 组件类型，优先期望 `systemAnr.anrType=PROVIDER`；若系统消息没有明确 Provider 类型导致 `systemAnr.anrType=UNKNOWN`，仍以主线程栈和当前消息耗时判断业务根因。

## 场景边界

ContentProvider 阻塞和已有场景的区别必须在实现和 JSON 说明中保持清楚：

- 输入事件当前慢消息：阻塞发生在按钮点击消息内，入口是 `CurrentSlowInputScenario.run`。
- 主线程 CPU 忙等：阻塞发生在按钮点击消息内，但 CPU 证据更高，入口是 `MainThreadCpuBusyScenario.run`。
- 消息风暴：根因是 Pending 队列大量重复消息，入口是 `MessageStormScenario.run`。
- Sync Barrier 泄漏 / nativePollOnce：根因是队头 Sync Barrier，入口是 `SyncBarrierLeakScenario.run` 的 Barrier token 插入栈。
- BroadcastReceiver 超时：按钮只发送广播，根因入口落在 `BroadcastTimeoutReceiver.onReceive`。
- Service 超时：按钮只启动 Service，根因入口落在 `ServiceTimeoutService.onStartCommand`。
- ContentProvider 阻塞：按钮只发起 Provider 查询，根因入口必须落在 `BlockingContentProvider.query`，调用链中应能看到 `ContentResolver.query` 或 Provider Transport 相关栈。

## 举一反三提问

实现和验收前先回答这些问题，避免场景做出来但 JSON 不能解释根因：

1. 这个场景验证的是 Provider 的 `query()` 阻塞，还是 Provider 进程启动 / `onCreate()` 阻塞？
   - 本计划选择 `query()` 阻塞，因为它能把根因稳定落在 `BlockingContentProvider.query`，不会污染 App 启动阶段。
2. 如果系统没有确认 `systemAnr.anrType=PROVIDER`，这份 JSON 还能不能定位根因？
   - 可以。Debug 阈值会先产出疑似 ANR，核心证据是 `mainThread.stackFrames` 和 `mainThread.current.wallMs`。
3. 为什么不用跨进程 Provider？
   - 本批次先验证 Provider 调用链和业务入口定位。跨进程等待更接近 Binder 场景，应留给后续 Binder / 跨进程阻塞计划。
4. 第 8 个按钮在小屏设备上是否可见？
   - 必须可见。本计划要求把 Demo 页面改成 `ScrollView`，否则手动验收可能点不到按钮。
5. 修复方向应该落在哪里？
   - 落在 Provider 业务实现：`query()` 等入口不得做长耗时同步工作，调用方只负责不要在主线程发起不可控慢查询。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenarioTest.kt`
  - JVM 单元测试，验证 Provider 场景元数据、查询 authority/path 和阻塞时长。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderCaller.kt`
  - 可注入 Provider 查询器。测试中记录 authority/path，真实 Demo 中通过 `ContentResolver.query()` 查询应用内 Provider。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlocker.kt`
  - Provider 内部阻塞动作，默认主线程休眠 12 秒。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenario.kt`
  - Demo 场景类，负责触发 Provider 查询，并声明 JSON 预期证据。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/BlockingContentProvider.kt`
  - Demo 专用非导出 Provider，`query()` 收到专用 path 后阻塞并返回最小 `MatrixCursor`。
- Modify: `app/src/main/AndroidManifest.xml`
  - 注册非导出的 `BlockingContentProvider`，authority 使用 `${applicationId}.blocking-provider`。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增中文按钮文案 `demo_content_provider_block`。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 把根布局改为可滚动容器，并新增“ContentProvider 阻塞”按钮，避免按钮在小屏设备上不可见。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 接入 `ContentProviderBlockScenario` 并绑定按钮点击。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加“ContentProvider 阻塞场景”的新人验证步骤、JSON 字段读取口径、排除项和修复方向。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 把 ContentProvider 场景状态更新为已实现，并追加第八批次验收说明。

## Implementation Tasks

### Task 1: 用 TDD 实现 ContentProvider 阻塞场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderCaller.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlocker.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenario.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/BlockingContentProvider.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 ContentProvider 阻塞场景的触发入口和 Provider 内部阻塞动作。
 */
class ContentProviderBlockScenarioTest {
    @Test
    fun runQueriesProviderWithConfiguredAuthorityAndPath(): Unit {
        val caller: RecordingContentProviderCaller = RecordingContentProviderCaller()
        val scenario: ContentProviderBlockScenario = ContentProviderBlockScenario(
            contentProviderCaller = caller,
        )

        scenario.run()

        assertEquals(
            listOf(
                RecordingContentProviderCall(
                    authority = ContentProviderBlockScenario.AUTHORITY,
                    path = ContentProviderBlockScenario.BLOCKING_PATH,
                ),
            ),
            caller.calls,
        )
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val caller: RecordingContentProviderCaller = RecordingContentProviderCaller()
        val scenario: ContentProviderBlockScenario = ContentProviderBlockScenario(
            contentProviderCaller = caller,
        )

        assertEquals("content_provider_block", scenario.id)
        assertEquals("ContentProvider 阻塞", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + PROVIDER call stack evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 BlockingContentProvider.query"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ContentProviderBlocker.block"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ContentResolver.query 或 ContentProvider.Transport.query"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun blockerBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val blocker: ContentProviderBlocker = ContentProviderBlocker(
            blockingAction = blockingAction,
            durationMs = 12_345L,
        )

        blocker.block()

        assertEquals(listOf(12_345L), blockingAction.blockedDurations)
    }

    private class RecordingContentProviderCaller : ContentProviderCaller {
        val calls: MutableList<RecordingContentProviderCall> = mutableListOf()

        override fun query(authority: String, path: String): Unit {
            calls.add(
                RecordingContentProviderCall(
                    authority = authority,
                    path = path,
                ),
            )
        }
    }

    private data class RecordingContentProviderCall(
        val authority: String,
        val path: String,
    )

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
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenarioTest
```

Expected: FAIL because `ContentProviderBlockScenario`, `ContentProviderCaller`, and `ContentProviderBlocker` do not exist.

- [x] **Step 3: Create ContentProvider caller abstraction**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderCaller.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * 可注入 Provider 查询器，测试中记录 authority/path，真实 Demo 中发起 ContentResolver 查询。
 */
fun interface ContentProviderCaller {
    /**
     * 查询指定 Provider 路径。
     *
     * @param authority Provider authority。
     * @param path Provider 路径。
     */
    fun query(authority: String, path: String): Unit
}

/**
 * 使用应用上下文查询应用内 ContentProvider，避免持有 Activity。
 *
 * @param context 用于获取应用上下文和 ContentResolver。
 */
class ContextContentProviderCaller(
    context: Context,
) : ContentProviderCaller {
    private val appContext: Context = context.applicationContext

    /**
     * 通过 ContentResolver 查询 Demo Provider。阻塞发生在 Provider.query() 内部。
     *
     * @param authority Provider authority。
     * @param path Provider 路径。
     */
    override fun query(authority: String, path: String): Unit {
        val uri: Uri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(path)
            .build()
        val cursor: Cursor? = appContext.contentResolver.query(
            uri,
            null,
            null,
            null,
            null,
        )
        cursor?.close()
    }
}
```

- [x] **Step 4: Create ContentProvider blocker**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlocker.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * ContentProvider 内部的阻塞动作，单独拆出便于 JVM 单元测试。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs Provider 阻塞时长，默认超过 Demo 疑似 ANR 阈值。
 */
class ContentProviderBlocker(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    /**
     * 在 Provider 查询所在线程阻塞，Demo 按钮触发时就是主线程。
     */
    fun block(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        /**
         * 使用休眠制造 Provider 查询长时间不返回的现场。
         *
         * @param durationMs 休眠时长，单位毫秒。
         */
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 12 秒，稳定超过 Demo SDK 疑似 ANR 阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 12_000L
    }
}
```

- [x] **Step 5: Create ContentProvider scenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 查询 Demo 专用阻塞 Provider，用于复现 ContentProvider 查询长时间不返回。
 *
 * @param contentProviderCaller Provider 查询器，测试中可替换为记录器。
 */
class ContentProviderBlockScenario(
    private val contentProviderCaller: ContentProviderCaller,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        contentProviderCaller = ContextContentProviderCaller(context = context),
    )

    override val id: String = "content_provider_block"
    override val title: String = "ContentProvider 阻塞"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + PROVIDER call stack evidence"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.stackFrames 包含 BlockingContentProvider.query",
        "mainThread.stackFrames 包含 ContentProviderBlocker.block",
        "mainThread.stackFrames 包含 ContentResolver.query 或 ContentProvider.Transport.query",
        "mainThread.current.wallMs >= 3000",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发 Provider 查询。此方法由按钮点击在主线程调用。
     */
    override fun run(): Unit {
        contentProviderCaller.query(
            authority = AUTHORITY,
            path = BLOCKING_PATH,
        )
    }

    companion object {
        const val AUTHORITY: String = "com.valiantyan.vibeanrmonitoring.blocking-provider"
        const val BLOCKING_PATH: String = "block"
    }
}
```

- [x] **Step 6: Create BlockingContentProvider**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/BlockingContentProvider.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlocker

/**
 * Demo 专用阻塞 Provider，用于复现 Provider 查询长时间不返回导致的主线程 ANR。
 */
class BlockingContentProvider : ContentProvider() {
    private val blocker: ContentProviderBlocker = ContentProviderBlocker()

    /**
     * Provider 初始化不做阻塞，避免 App 启动阶段被污染；真正阻塞只发生在 query()。
     */
    override fun onCreate(): Boolean = true

    /**
     * 收到 Demo 专用路径后阻塞，再返回一行最小结果。
     *
     * @param uri 查询 URI。
     * @param projection 查询列。
     * @param selection 查询条件。
     * @param selectionArgs 查询参数。
     * @param sortOrder 排序条件。
     * @return 最小 Cursor，证明查询链路完成。
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.lastPathSegment == ContentProviderBlockScenario.BLOCKING_PATH) {
            blocker.block()
        }
        return MatrixCursor(arrayOf("status")).apply {
            addRow(arrayOf("blocked"))
        }
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.vibeanr.blocking-provider"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
```

- [x] **Step 7: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenarioTest
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderCaller.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlocker.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/ContentProviderBlockScenario.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/BlockingContentProvider.kt
git commit -m "新增 ContentProvider 阻塞场景类"
```

### Task 2: 注册 ContentProvider 清单

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [x] **Step 1: Register non-exported Provider**

Modify `app/src/main/AndroidManifest.xml` by adding this provider block after `ServiceTimeoutService` and before `BroadcastTimeoutReceiver`:

```xml
        <provider
            android:name=".BlockingContentProvider"
            android:authorities="${applicationId}.blocking-provider"
            android:exported="false" />
```

The relevant application section should contain:

```xml
        <service
            android:name=".ServiceTimeoutService"
            android:exported="false" />

        <provider
            android:name=".BlockingContentProvider"
            android:authorities="${applicationId}.blocking-provider"
            android:exported="false" />

        <receiver
            android:name=".BroadcastTimeoutReceiver"
            android:exported="false">
```

- [x] **Step 2: Verify manifest processing**

Run:

```bash
./gradlew :app:processDebugMainManifest
```

Expected: PASS.

- [x] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "注册 ContentProvider 阻塞测试组件"
```

### Task 3: 将 ContentProvider 阻塞按钮接入 Demo

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Add Chinese button text**

Modify `app/src/main/res/values/strings.xml` by adding this line after `demo_service_timeout`:

```xml
    <string name="demo_content_provider_block">ContentProvider 阻塞</string>
```

The final file should include:

```xml
    <string name="demo_broadcast_timeout">BroadcastReceiver 超时</string>
    <string name="demo_service_timeout">Service 超时</string>
    <string name="demo_content_provider_block">ContentProvider 阻塞</string>
```

- [x] **Step 2: Make layout scrollable and add button**

Replace `app/src/main/res/layout/activity_main.xml` with this complete scrollable layout:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:orientation="vertical"
        android:padding="24dp">

        <Button
            android:id="@+id/currentSlowButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/demo_current_slow" />

        <Button
            android:id="@+id/messageStormButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_message_storm" />

        <Button
            android:id="@+id/currentBusyButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_current_busy" />

        <Button
            android:id="@+id/binderLikeButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_binder_like_lock" />

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

        <Button
            android:id="@+id/serviceTimeoutButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_service_timeout" />

        <Button
            android:id="@+id/contentProviderBlockButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_content_provider_block" />
    </LinearLayout>
</ScrollView>
```

- [x] **Step 3: Wire scenario in MainActivity**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`.

Add this import with the other scenario imports:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenario
```

Add this property after `serviceTimeoutScenario`:

```kotlin
    // ContentProvider 阻塞场景，按钮只负责发起查询，真正阻塞入口在 Provider.query。
    private val contentProviderBlockScenario: ContentProviderBlockScenario by lazy {
        ContentProviderBlockScenario(context = this)
    }
```

Add this click listener after `serviceTimeoutButton`:

```kotlin
        findViewById<Button>(R.id.contentProviderBlockButton).setOnClickListener {
            contentProviderBlockScenario.run()
        }
```

- [x] **Step 4: Verify app compiles**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:mergeDebugResources
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_main.xml app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入 ContentProvider 阻塞按钮"
```

### Task 4: 更新使用说明中的 ContentProvider 验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`

- [x] **Step 1: Add button row**

Modify the Demo button table in `docs-anr/103-ANR监控SDK使用说明.md` by adding this row after `Service 超时`:

```markdown
| `ContentProvider 阻塞` | 查询显式应用内 Provider，`query()` 主线程阻塞 12 秒 | `mainThread.stackFrames` 包含 `BlockingContentProvider.query`、`ContentResolver.query` 或 `ContentProvider.Transport.query`、当前消息耗时 |
```

- [x] **Step 2: Add ContentProvider section**

Add this section after the existing “Service 超时场景” section and before “## 9. 线上接入清单”:

````markdown
### ContentProvider 阻塞场景

这个场景用于验证：如果 ContentProvider 的查询逻辑在主线程执行耗时阻塞，SDK 是否能把根因定位到具体 Provider，而不是只看到当前消息慢或系统组件调度。

操作步骤：

1. 安装 Debug 包并打开 Demo App。
2. 点击“ContentProvider 阻塞”。
3. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
4. 从设备拉取最新 JSON：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json > content-provider-block.json
```

重点看这些字段：

| 字段 | 期望现象 | 含义 |
| --- | --- | --- |
| `event.eventType` | `SUSPECT_ANR` 或 `CONFIRMED_ANR` | SDK 已经捕获到 ANR 现场 |
| `mainThread.current.wallMs` | 大于 `3000` | 主线程当前消息执行时间过长 |
| `mainThread.stackFrames` | 包含 `BlockingContentProvider.query` 和 `ContentProviderBlocker.block` | 业务根因入口是 Provider 查询中阻塞 |
| `mainThread.stackFrames` | 包含 `ContentResolver.query` 或 `ContentProvider.Transport.query` | 说明调用链经过 Provider 查询 |
| `systemAnr.isConfirmedAnr` | 可能为 `true` 或 `false` | `false` 代表 SDK 先抓到疑似 ANR，`true` 代表系统也确认了 ANR |
| `systemAnr.anrType` | 系统确认后优先期望 `PROVIDER`，也可能是 `UNKNOWN` | `PROVIDER` 是最理想组件证据；`UNKNOWN` 时仍按栈定位业务根因 |
| `systemAnr.componentTimeoutMs` | Provider 类型确认后通常为 `10000` | 用来解释系统 Provider 超时预算 |
| `barrierEvidence.stuckTokens` | 空数组或不是主因 | 排除 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` | 排除 Binder / 跨进程阻塞 |

判断结论模板：

```text
本次 ANR 是 ContentProvider 查询阻塞。业务根因证据是主线程栈包含 BlockingContentProvider.query 和 ContentProviderBlocker.block，调用链中出现 ContentResolver.query 或 ContentProvider.Transport.query，当前消息执行时间超过阈值。Barrier 和 Binder 证据不构成本次主因，因此根因是 Provider 在 query() 中执行了耗时阻塞逻辑。
```

如果 `systemAnr.isConfirmedAnr=false`，不要直接否定这份报告。Debug 配置下 SDK 会在 3 秒左右先生成疑似 ANR，而系统 Provider 相关确认可能更晚。如果 `systemAnr.isConfirmedAnr=true` 且 `systemAnr.anrType=PROVIDER`，说明系统也确认了 Provider 组件超时；如果系统版本没有输出明确 Provider 类型，仍先按 `mainThread.stackFrames` 定位业务根因，再结合系统 traces 复核组件类型。

修复方向：

- `query()`、`insert()`、`update()`、`delete()`、`openFile()` 中不要执行长时间同步 IO、同步网络、锁等待或大计算。
- Provider 只做参数校验、权限校验和轻量查询分发，耗时工作应下沉到后台线程、缓存预热或异步任务。
- 如果 Provider 是跨进程访问入口，要把超时预算看得更严格，因为调用方可能正在主线程等待返回。
- 对数据库查询增加索引、分页、取消能力和慢查询日志，避免一次 Provider 查询扫描大量数据。
- 修复后重新点击“ContentProvider 阻塞”按钮验证：`mainThread.stackFrames` 不应再出现 Provider 阻塞栈，`mainThread.current.wallMs` 应低于疑似 ANR 阈值。
````

- [x] **Step 3: Check docs diff**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 4: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "补充 ContentProvider 阻塞验证说明"
```

### Task 5: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Update overview row**

Modify the ContentProvider row in `docs-anr/105-Demo-ANR场景实现计划.md` from:

```markdown
| 8 | ContentProvider 阻塞 | 查询阻塞 Provider | Provider 组件阻塞 | Provider 调用栈和系统组件证据 | 待实现 |
```

to:

```markdown
| 8 | ContentProvider 阻塞 | 点击“ContentProvider 阻塞”后查询应用内 Provider，`query()` 主线程阻塞 12 秒 | Provider 查询阻塞 + 当前消息慢证据 | `mainThread.stackFrames` 包含 `BlockingContentProvider.query`、`ContentProviderBlocker.block`、`mainThread.current.wallMs` | 已实现，待手动验收 |
```

- [x] **Step 2: Add eighth batch section**

Add this section after the existing “第七批次：Service 超时” section and before “## 后续批次顺序”:

````markdown
## 第八批次：ContentProvider 阻塞

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“ContentProvider 阻塞”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `event.eventType`，确认 SDK 已经生成疑似或确认报告。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.stackFrames`，应能看到 `BlockingContentProvider.query` 和 `ContentProviderBlocker.block`，说明业务根因入口是 Provider 查询。最后检查栈中是否存在 `ContentResolver.query` 或 `ContentProvider.Transport.query`，用于证明调用链经过 ContentProvider。

如果 `systemAnr.isConfirmedAnr=true` 且 `systemAnr.anrType=PROVIDER`，说明系统也确认了 Provider 组件超时，`systemAnr.componentTimeoutMs` 通常应为 `10000`。如果 `systemAnr.isConfirmedAnr=false`，仍可先根据 SDK 疑似 ANR 报告定位业务根因，因为 SDK 阈值比系统确认阈值更早触发。如果系统版本没有输出明确 Provider 类型，也不要把 `systemAnr.anrType=UNKNOWN` 当成根因未知；根因仍以主线程栈和当前消息耗时为准。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果主线程栈只看到按钮点击入口而没有 `BlockingContentProvider.query`，优先检查 Provider 是否已经在 Manifest 中注册成功，或是否点错按钮。

### 首次验收记录

验收时间：执行 Task 6 时填写具体时间。

验收设备：执行 Task 6 时填写设备序列号。

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s <device-id> shell input tap <content-provider-button-x> <content-provider-button-y>
adb -s <device-id> logcat -d -s VibeAnrApplication
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json
```

验收清单：

- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [ ] 真机或模拟器点击“ContentProvider 阻塞”后生成 JSON。
- [ ] JSON 中 `mainThread.stackFrames` 包含 `BlockingContentProvider.query`。
- [ ] JSON 中 `mainThread.stackFrames` 包含 `ContentProviderBlocker.block`。
- [ ] JSON 中 `mainThread.current.wallMs >= 3000`。
- [ ] 如果系统已确认 ANR，`systemAnr.anrType=PROVIDER`。
- [ ] Barrier 和 Binder 证据均不是本次主因。

验收结论：执行 Task 6 后填写。期望结论为“ContentProvider 阻塞场景验收通过。SDK 能捕获疑似 ANR，JSON 能把 Provider 查询调用链和业务 Provider 阻塞入口分开表达；如果系统确认 ANR，`systemAnr.anrType=PROVIDER` 可作为组件超时证据；根因可以明确写为 `BlockingContentProvider.query` 在主线程执行耗时阻塞，导致 Provider 查询无法及时返回。”
````

- [x] **Step 3: Update later batch order**

Modify the final “后续批次顺序” sentence from:

```markdown
后续按锁等待、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

to:

```markdown
后续按锁等待、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

- [x] **Step 4: Check docs diff**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新 ContentProvider 阻塞场景矩阵"
```

### Task 6: 执行 ContentProvider 阻塞最终验收

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

Expected: install succeeds.

- [x] **Step 3: Trigger ContentProvider block scenario**

Run:

```bash
adb -s <device-id> logcat -c
adb -s <device-id> shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s <device-id> shell uiautomator dump /sdcard/window.xml
adb -s <device-id> shell input tap <content-provider-button-x> <content-provider-button-y>
```

Expected: the app becomes unresponsive for about 12 seconds.

Use the `uiautomator dump` output to choose the real button coordinates for “ContentProvider 阻塞”. The layout should already be scrollable after Task 3; if the button is still below the visible viewport on a small device, scroll to the bottom before tapping.

- [x] **Step 4: Inspect logcat**

Run:

```bash
adb -s <device-id> logcat -d -s VibeAnrApplication AnrMonitor
```

Expected: log contains `suspect ANR captured: <event-id>` and `ANR report written: <event-id>`.

- [x] **Step 5: Pull latest JSON**

Run:

```bash
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json > /tmp/content-provider-block.json
```

Expected: JSON file is written to `/tmp/content-provider-block.json`.

- [x] **Step 6: Verify JSON root-cause evidence**

Run:

```bash
rg -n "BlockingContentProvider|ContentProviderBlocker|ContentResolver|ContentProvider\\$Transport|CURRENT_MESSAGE_SLOW|PROVIDER|componentTimeoutMs|binderBlock|barrierEvidence" /tmp/content-provider-block.json
```

Expected:

- `mainThread.stackFrames` contains `BlockingContentProvider.query`.
- `mainThread.stackFrames` contains `ContentProviderBlocker.block`.
- `mainThread.stackFrames` contains `ContentResolver.query` or `ContentProvider$Transport.query`.
- `mainThread.current.wallMs` is greater than or equal to `3000`.
- If system ANR is confirmed, `systemAnr.anrType` is `PROVIDER` and `systemAnr.componentTimeoutMs` is usually `10000`.
- `barrierEvidence.stuckTokens` is empty or not used as primary evidence.
- `binderBlock.suspected` is `false`.

- [x] **Step 7: Fill acceptance record**

Modify the “第八批次：ContentProvider 阻塞” section in `docs-anr/105-Demo-ANR场景实现计划.md`:

- Replace `执行 Task 6 时填写具体时间。` with the actual time, for example `2026-06-08 20:30 CST`.
- Replace `执行 Task 6 时填写设备序列号。` with the actual device id, for example `emulator-5554`.
- Replace `<device-id>`, `<content-provider-button-x>`, `<content-provider-button-y>`, and `<event-id>` in the command block with the actual values used.
- Mark passed checklist items as `- [x]`.
- Replace the acceptance conclusion sentence with the actual JSON-backed conclusion.

- [x] **Step 8: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "记录 ContentProvider 阻塞场景验收结果"
```

## 三轮审核记录

### 第一轮：需求覆盖审核

- 结论：计划覆盖了 ContentProvider 阻塞的完整实现链路，包括测试、Provider、Manifest、按钮、说明文档、矩阵状态和最终验收。
- 修正：补充“为什么阻塞放在 `query()` 而不是 `onCreate()`”的说明，避免把 App 启动阻塞误做成 Provider 查询阻塞。
- 修正：补充举一反三问题，要求执行前明确本批次不做跨进程 Provider，跨进程等待留给 Binder 场景。

### 第二轮：Android 组件语义审核

- 结论：当前 SDK 已支持 `AnrType.PROVIDER` 和 Provider 默认组件阈值，计划不应只把系统类型写成 `UNKNOWN`。
- 修正：把 `systemAnr.anrType=PROVIDER` 和 `systemAnr.componentTimeoutMs=10000` 写入文档读取口径、矩阵验收和最终 JSON 检查。
- 保留：疑似 ANR 阶段仍允许 `systemAnr.isConfirmedAnr=false`，因为 SDK 会早于系统确认捕获现场。

### 第三轮：可执行性审核

- 结论：第 8 个按钮追加后，原始不可滚动 `LinearLayout` 在小屏设备上可能不可见，影响手动验收。
- 修正：Task 3 改为完整替换 `activity_main.xml`，使用 `ScrollView` 包裹按钮列表，并新增 `ContentProvider 阻塞` 按钮。
- 修正：Task 6 的手动验收说明改为基于可滚动页面选择坐标，避免执行者误以为需要另做布局 follow-up。

## Self-Review

### Spec coverage

- 覆盖了 `docs-anr/105-Demo-ANR场景实现计划.md` 中顺序 8 的“ContentProvider 阻塞”。
- 覆盖了 TDD：先写 `ContentProviderBlockScenarioTest`，再实现场景类、查询器、阻塞器和 Provider。
- 覆盖了 Demo 接线：Manifest Provider、中文按钮、布局、`MainActivity` 点击事件。
- 覆盖了新人可读文档：`docs-anr/103-ANR监控SDK使用说明.md` 增加操作步骤、JSON 字段、排除项和修复方向。
- 覆盖了场景矩阵状态和最终手动验收记录。

### Placeholder scan

- 本计划没有使用空洞占位词或只描述意图却不给代码的步骤。
- Task 6 中的 `<device-id>`、`<event-id>` 和按钮坐标是执行期必须由真实设备产生的命令参数，不是实现占位；该任务给出了替换规则和验收字段。

### Type consistency

- `ContentProviderBlockScenario.AUTHORITY` 与 Manifest `${applicationId}.blocking-provider` 对齐，当前 applicationId 为 `com.valiantyan.vibeanrmonitoring`。
- `ContentProviderBlockScenario.BLOCKING_PATH` 与 `BlockingContentProvider.query()` 的 `uri.lastPathSegment` 判断一致。
- `ContentProviderCaller.query(authority, path)` 在测试记录器和 `ContextContentProviderCaller` 中签名一致。
- `ContentProviderBlocker.block()` 与测试中的 `RecordingBlockingAction.block(durationMs)` 调用一致。
