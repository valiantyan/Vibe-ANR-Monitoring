# IO / 数据库 / 文件阻塞 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Demo App 中新增“IO / 数据库 / 文件阻塞”ANR 场景，让 SDK 可以抓到主线程同步文件写入、同步数据库事务导致的当前消息慢，并在 JSON 中定位到业务入口。

**Architecture:** 本计划沿用现有 Demo 场景模式：Activity 只负责按钮接线，阻塞逻辑放到 `scenario` 包中的独立类。场景类通过 `MainThreadIoWorkload` 注入可测试工作负载，真实 Demo 运行时使用应用私有目录文件写入、频繁 `FileDescriptor.sync()` 和 SQLite 大事务制造主线程慢 IO/DB 现场，不使用 `Thread.sleep()` 伪造耗时。

**Tech Stack:** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android App 模块、现有 `:anr-monitor-sdk`、`FileOutputStream`、`FileDescriptor.sync()`、`SQLiteDatabase`、本地 JSON 报告。

---

## Scope Check

“IO / 数据库 / 文件阻塞”属于一个根因家族：主线程执行同步磁盘操作或同步数据库操作，导致当前主线程消息无法及时返回。为了避免和已经实现的 `ContentProvider 阻塞` 混淆，本计划不新增 Provider，也不把数据库访问藏在 Provider 里；按钮点击后直接在主线程执行 Demo 专用文件写入和 SQLite 事务。

本计划不实现网络阻塞、锁等待、线程池耗尽、GC/内存抖动或后台 CPU 竞争。数据库部分只使用 Android 内置 SQLite，不引入 Room、SQLCipher 或第三方数据库库。

## File Structure

下列路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenarioTest.kt`
  - JVM 单元测试，验证场景元数据和工作负载调用。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadIoWorkload.kt`
  - 可注入的主线程 IO/DB 工作负载接口。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/FileAndDatabaseBlockingWorkload.kt`
  - 真实 Demo 工作负载：应用私有目录写文件、频繁 flush/fsync、执行 SQLite 大事务插入。
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenario.kt`
  - 场景入口，暴露 `id`、`title`、`expectedAttribution`、`expectedJsonSignals` 和 `run()`。
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
  - 新增场景属性并把按钮接到 `IoDatabaseFileBlockScenario.run()`。
- Modify: `app/src/main/res/layout/activity_main.xml`
  - 新增“IO / 数据库 / 文件阻塞”按钮。
- Modify: `app/src/main/res/values/strings.xml`
  - 新增按钮中文文案。
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
  - 增加 IO/DB/File 场景验证步骤、JSON 判断口径、修复建议。
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`
  - 将场景 10 状态从“待实现”更新为“已实现，待手动验收”，补充本场景批次说明。
- Modify: `README.md`
  - 在“Demo 已覆盖场景”加入新场景，并从后续计划移除主线程 IO/数据库阻塞。

## Implementation Tasks

### Task 1: 用 TDD 实现 IO / 数据库 / 文件阻塞场景类

**Files:**
- Create: `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenarioTest.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadIoWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/FileAndDatabaseBlockingWorkload.kt`
- Create: `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenario.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenarioTest.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 IO / 数据库 / 文件阻塞场景的元数据和主线程工作负载入口。
 */
class IoDatabaseFileBlockScenarioTest {
    @Test
    fun runExecutesConfiguredIoWorkload(): Unit {
        val workload: RecordingMainThreadIoWorkload = RecordingMainThreadIoWorkload()
        val scenario: IoDatabaseFileBlockScenario = IoDatabaseFileBlockScenario(
            workload = workload,
        )

        scenario.run()

        assertEquals(1, workload.runCount)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingMainThreadIoWorkload = RecordingMainThreadIoWorkload()
        val scenario: IoDatabaseFileBlockScenario = IoDatabaseFileBlockScenario(
            workload = workload,
        )

        assertEquals("io_database_file_block", scenario.id)
        assertEquals("IO / 数据库 / 文件阻塞", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + IO/DB call stack evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 IoDatabaseFileBlockScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 FileOutputStream.write 或 SQLiteDatabase"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingMainThreadIoWorkload : MainThreadIoWorkload {
        var runCount: Int = 0

        override fun runIoDatabaseFileWorkload(): Unit {
            runCount += 1
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.IoDatabaseFileBlockScenarioTest
```

Expected: FAIL with unresolved references for `MainThreadIoWorkload` and `IoDatabaseFileBlockScenario`.

- [x] **Step 3: Create MainThreadIoWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadIoWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 主线程执行的 IO/DB 工作负载。测试中替换为记录器，Demo 运行时执行真实文件和数据库操作。
 */
fun interface MainThreadIoWorkload {
    /**
     * 执行一组同步文件和数据库操作。调用方必须保证此方法在主线程触发，才能复现 ANR。
     */
    fun runIoDatabaseFileWorkload(): Unit
}
```

- [x] **Step 4: Create FileAndDatabaseBlockingWorkload**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/FileAndDatabaseBlockingWorkload.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Demo 专用主线程慢 IO/DB 工作负载。
 *
 * 这个类故意在调用线程执行同步文件写入、fsync 和 SQLite 大事务，用于制造可观测的 ANR 现场。
 *
 * @param context 应用上下文，用于访问私有文件和数据库目录。
 * @param fileChunks 文件写入块数量。
 * @param fileChunkSizeBytes 每个文件块大小。
 * @param syncEveryChunks 每写入多少个文件块执行一次 fsync。
 * @param databaseRows SQLite 事务插入行数。
 * @param databasePayloadBytes 每行 SQLite payload 大小。
 */
class FileAndDatabaseBlockingWorkload(
    context: Context,
    private val fileChunks: Int = DEFAULT_FILE_CHUNKS,
    private val fileChunkSizeBytes: Int = DEFAULT_FILE_CHUNK_SIZE_BYTES,
    private val syncEveryChunks: Int = DEFAULT_SYNC_EVERY_CHUNKS,
    private val databaseRows: Int = DEFAULT_DATABASE_ROWS,
    private val databasePayloadBytes: Int = DEFAULT_DATABASE_PAYLOAD_BYTES,
) : MainThreadIoWorkload {
    private val appContext: Context = context.applicationContext

    /**
     * 依次执行同步文件写入和 SQLite 事务。此方法由按钮点击在主线程调用。
     */
    override fun runIoDatabaseFileWorkload(): Unit {
        val workingDir: File = File(appContext.filesDir, WORKING_DIR_NAME)
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }
        writeBlockingFile(file = File(workingDir, BLOCKING_FILE_NAME))
        runBlockingDatabaseTransaction()
    }

    private fun writeBlockingFile(file: File): Unit {
        val buffer: ByteArray = ByteArray(fileChunkSizeBytes) { index ->
            (index % BYTE_PATTERN_MOD).toByte()
        }
        FileOutputStream(file, false).use { stream ->
            repeat(fileChunks) { index ->
                stream.write(buffer)
                if (index % syncEveryChunks == 0) {
                    stream.flush()
                    stream.fd.sync()
                }
            }
            stream.flush()
            stream.fd.sync()
        }
    }

    private fun runBlockingDatabaseTransaction(): Unit {
        val databaseFile: File = appContext.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        val database: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS demo_blocking_io (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "label TEXT NOT NULL, " +
                    "payload BLOB NOT NULL" +
                    ")",
            )
            database.beginTransaction()
            try {
                database.execSQL("DELETE FROM demo_blocking_io")
                repeat(databaseRows) { index ->
                    database.execSQL(
                        "INSERT INTO demo_blocking_io(label, payload) VALUES(?, ?)",
                        arrayOf(
                            String.format(Locale.US, "row-%04d", index),
                            ByteArray(databasePayloadBytes) { payloadIndex ->
                                ((index + payloadIndex) % BYTE_PATTERN_MOD).toByte()
                            },
                        ),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } finally {
            database.close()
        }
    }

    companion object {
        private const val WORKING_DIR_NAME: String = "demo-blocking-io"
        private const val BLOCKING_FILE_NAME: String = "blocking-file.bin"
        private const val DATABASE_NAME: String = "demo_blocking_io.db"
        private const val BYTE_PATTERN_MOD: Int = 251
        private const val DEFAULT_SYNC_EVERY_CHUNKS: Int = 1
        private const val DEFAULT_FILE_CHUNKS: Int = 128
        private const val DEFAULT_FILE_CHUNK_SIZE_BYTES: Int = 256 * 1024
        private const val DEFAULT_DATABASE_ROWS: Int = 800
        private const val DEFAULT_DATABASE_PAYLOAD_BYTES: Int = 8 * 1024
    }
}
```

- [x] **Step 5: Create IoDatabaseFileBlockScenario**

Create `app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenario.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 在主线程执行同步文件和数据库操作，用于复现 IO / 数据库 / 文件阻塞导致的当前消息慢。
 *
 * @param workload 可测试工作负载，真实 Demo 中执行文件写入和 SQLite 事务。
 */
class IoDatabaseFileBlockScenario(
    private val workload: MainThreadIoWorkload,
) : AnrDemoScenario {
    /**
     * 使用真实 Android Context 创建 IO/DB 工作负载。
     *
     * @param context 用于访问应用私有文件和数据库目录。
     */
    constructor(context: Context) : this(
        workload = FileAndDatabaseBlockingWorkload(context = context),
    )

    /** 场景唯一标识，后续文档和分析报告用它区分 Demo 类型。 */
    override val id: String = "io_database_file_block"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "IO / 数据库 / 文件阻塞"

    /** 预期归因说明，主线程同步 IO/DB 通常先表现为当前消息慢。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + IO/DB call stack evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 IoDatabaseFileBlockScenario.run",
        "mainThread.stackFrames 包含 FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload",
        "mainThread.stackFrames 包含 FileOutputStream.write 或 SQLiteDatabase",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发主线程同步 IO/DB 工作负载。此方法由按钮点击在主线程调用。
     */
    override fun run(): Unit {
        workload.runIoDatabaseFileWorkload()
    }
}
```

- [x] **Step 6: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.IoDatabaseFileBlockScenarioTest
```

Expected: PASS.

- [x] **Step 7: Run app compile to catch Android API issues**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add app/src/test/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenarioTest.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/MainThreadIoWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/FileAndDatabaseBlockingWorkload.kt app/src/main/java/com/valiantyan/vibeanrmonitoring/scenario/IoDatabaseFileBlockScenario.kt
git commit -m "新增 IO 数据库文件阻塞场景类"
```

### Task 2: 将 IO / 数据库 / 文件阻塞按钮接入 Demo

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **Step 1: Add button string**

Modify `app/src/main/res/values/strings.xml` by adding this string after `demo_content_provider_block`:

```xml
    <string name="demo_io_database_file_block">IO / 数据库 / 文件阻塞</string>
```

The end of the file should look like this:

```xml
    <string name="demo_service_timeout">Service 超时</string>
    <string name="demo_content_provider_block">ContentProvider 阻塞</string>
    <string name="demo_io_database_file_block">IO / 数据库 / 文件阻塞</string>
</resources>
```

- [x] **Step 2: Add button to layout**

Modify `app/src/main/res/layout/activity_main.xml` by adding this button after `contentProviderBlockButton`:

```xml
        <Button
            android:id="@+id/ioDatabaseFileBlockButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_io_database_file_block" />
```

The end of the `LinearLayout` should look like this:

```xml
        <Button
            android:id="@+id/contentProviderBlockButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_content_provider_block" />

        <Button
            android:id="@+id/ioDatabaseFileBlockButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/demo_io_database_file_block" />
    </LinearLayout>
```

- [x] **Step 3: Wire MainActivity**

Modify `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`.

Add this import after `ContentProviderBlockScenario`:

```kotlin
import com.valiantyan.vibeanrmonitoring.scenario.IoDatabaseFileBlockScenario
```

Add this property after `contentProviderBlockScenario`:

```kotlin
    // IO / 数据库 / 文件阻塞场景，按钮点击后在主线程执行同步文件和 SQLite 操作。
    private val ioDatabaseFileBlockScenario: IoDatabaseFileBlockScenario by lazy {
        IoDatabaseFileBlockScenario(context = this)
    }
```

Add this click listener after `contentProviderBlockButton`:

```kotlin
        findViewById<Button>(R.id.ioDatabaseFileBlockButton).setOnClickListener {
            ioDatabaseFileBlockScenario.run()
        }
```

The relevant section should look like this:

```kotlin
    // ContentProvider 阻塞场景，按钮只负责发起查询，真正阻塞入口在 Provider.query。
    private val contentProviderBlockScenario: ContentProviderBlockScenario by lazy {
        ContentProviderBlockScenario(context = this)
    }

    // IO / 数据库 / 文件阻塞场景，按钮点击后在主线程执行同步文件和 SQLite 操作。
    private val ioDatabaseFileBlockScenario: IoDatabaseFileBlockScenario by lazy {
        IoDatabaseFileBlockScenario(context = this)
    }
```

```kotlin
        findViewById<Button>(R.id.contentProviderBlockButton).setOnClickListener {
            contentProviderBlockScenario.run()
        }
        findViewById<Button>(R.id.ioDatabaseFileBlockButton).setOnClickListener {
            ioDatabaseFileBlockScenario.run()
        }
```

- [x] **Step 4: Run tests and build**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.IoDatabaseFileBlockScenarioTest
```

Expected: PASS.

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_main.xml app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt
git commit -m "接入 IO 数据库文件阻塞按钮"
```

### Task 3: 更新使用说明中的 IO / 数据库 / 文件阻塞验证步骤

**Files:**
- Modify: `docs-anr/103-ANR监控SDK使用说明.md`
- Modify: `README.md`

- [x] **Step 1: Add README covered scenario row**

Modify `README.md` in “Demo 已覆盖场景” by adding this row after `ContentProvider 阻塞`:

```markdown
| IO / 数据库 / 文件阻塞 | 点击“IO / 数据库 / 文件阻塞” | `CURRENT_MESSAGE_SLOW`，主线程栈定位到 `IoDatabaseFileBlockScenario.run` 和 `FileAndDatabaseBlockingWorkload` |
```

Modify the sentence below the table from:

```markdown
后续场景计划包括主线程锁等待、主线程 IO/数据库阻塞、线程池耗尽等待、GC/内存抖动、进程内 CPU 竞争等。
```

to:

```markdown
后续场景计划包括主线程锁等待、线程池耗尽等待、GC/内存抖动、进程内 CPU 竞争等。
```

- [x] **Step 2: Add usage-guide scenario section**

Modify `docs-anr/103-ANR监控SDK使用说明.md` by adding this section after the existing ContentProvider 场景说明 section:

```markdown
### Demo 场景：IO / 数据库 / 文件阻塞

这个场景用于验证“主线程同步文件 IO / SQLite 数据库事务”导致的当前消息慢。按钮点击后，Demo 会在主线程写入应用私有目录文件、执行 `FileDescriptor.sync()`，并执行一段 SQLite 事务插入。

#### 操作步骤

1. 安装 debug 包并打开 Demo App。
2. 点击“IO / 数据库 / 文件阻塞”。
3. 等待 logcat 输出 `suspect ANR captured` 和 `ANR report written`。
4. 拉取 `files/anr-monitor-reports` 目录下最新 JSON。
5. 按下面字段顺序分析。

#### JSON 判断口径

| 字段 | 期望 | 含义 |
| --- | --- | --- |
| `event.eventType` | `SUSPECT_ANR` 或系统确认 ANR | SDK 已捕获一次 ANR 现场 |
| `attribution.primary` | `CURRENT_MESSAGE_SLOW` | 主因是当前主线程消息执行过慢 |
| `mainThread.current.wallMs` | 大于 `3000` | 当前消息超过 Demo 疑似 ANR 阈值 |
| `mainThread.stackFrames` | 包含 `IoDatabaseFileBlockScenario.run` | 能定位到 Demo 场景入口 |
| `mainThread.stackFrames` | 包含 `FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload` | 能定位到同步 IO/DB 工作负载 |
| `mainThread.stackFrames` | 可能包含 `FileOutputStream.write`、`FileDescriptor.sync`、`SQLiteDatabase`、`SQLiteConnection` | 说明主线程正在执行文件或数据库相关操作 |
| `barrierEvidence.stuckTokens` | 空数组或不是主证据 | 本次不是 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` 或不是主证据 | 本次不是 Binder 跨进程阻塞 |

#### 根因写法

可以写成：

```text
本次 ANR 是主线程同步 IO/数据库阻塞。证据是 attribution.primary=CURRENT_MESSAGE_SLOW，当前消息耗时超过阈值，主线程栈能回溯到 IoDatabaseFileBlockScenario.run 和 FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload，并出现文件写入或 SQLite 调用帧。Barrier 和 Binder 证据不是本次主因。
```

不要写成：

```text
系统 nativePollOnce 导致 ANR。
```

如果系统 traces 中看到 `nativePollOnce`，要继续回到 SDK JSON 看当前消息、历史消息、Pending 队列和主线程栈。对这个场景来说，`nativePollOnce` 只是系统等待消息或等待事件的表象，不是根因。

#### 修复建议

- 不在 `onClick`、`onCreate`、`onResume`、`BroadcastReceiver.onReceive`、`Service.onStartCommand` 等主线程回调中执行同步文件读写或大事务。
- 文件写入放到后台线程，必要时拆成小块并提供取消能力。
- 数据库写入使用后台线程、事务批处理、索引优化和分页查询。
- 对用户可感知操作增加超时、进度状态和失败兜底，不让主线程等待 IO 完成。
- 对线上业务增加慢 IO、慢 SQL 和主线程磁盘访问日志，和 SDK JSON 的栈证据交叉定位。
```

- [x] **Step 3: Check markdown formatting**

Run:

```bash
git diff --check -- docs-anr/103-ANR监控SDK使用说明.md README.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 4: Commit**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md README.md
git commit -m "更新 IO 数据库文件阻塞使用说明"
```

### Task 4: 更新 Demo 场景矩阵状态

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [x] **Step 1: Update scenario table row**

Modify row 10 in `docs-anr/105-Demo-ANR场景实现计划.md` from:

```markdown
| 10 | 主线程 IO/数据库阻塞 | 主线程执行慢 IO 或慢查询 | `CURRENT_MESSAGE_SLOW` | IO/DB 业务栈、当前消息耗时 | 待实现 |
```

to:

```markdown
| 10 | IO / 数据库 / 文件阻塞 | 点击“IO / 数据库 / 文件阻塞”后主线程执行同步文件写入、fsync 和 SQLite 事务 | `CURRENT_MESSAGE_SLOW` + IO/DB 栈证据 | `mainThread.current.wallMs`、`mainThread.stackFrames` 包含 `IoDatabaseFileBlockScenario.run` / `FileAndDatabaseBlockingWorkload`、文件或 SQLite 调用帧 | 已实现，待手动验收 |
```

- [x] **Step 2: Add batch section**

Add this section before the final “后续批次顺序” paragraph:

```markdown
## 第十批次：IO / 数据库 / 文件阻塞

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“IO / 数据库 / 文件阻塞”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.stackFrames`，应能看到 `IoDatabaseFileBlockScenario.run` 和 `FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload`；如果采样时机落在文件阶段，还可能看到 `FileOutputStream.write`、`FileDescriptor.sync`；如果采样时机落在数据库阶段，还可能看到 `SQLiteDatabase`、`SQLiteConnection` 或 SQLite native 调用。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该成为主因。
- 如果 `mainThread.current.wallMs` 没有超过阈值，优先增加 `DEFAULT_FILE_CHUNKS`、`DEFAULT_DATABASE_ROWS` 或降低 `DEFAULT_SYNC_EVERY_CHUNKS`，不要用 `Thread.sleep()` 伪造 IO/DB 耗时。
- 这个场景不等同于 `ContentProvider 阻塞`；本场景直接在主线程执行文件和 SQLite 工作负载。

### 首次验收记录

验收状态：未执行。

验收设备：未执行。

验收结论：未执行。执行 Task 5 后用真实日志和 JSON 字段替换本段。
```

- [x] **Step 3: Update final next-batch sentence**

Modify the final next-batch sentence from:

```markdown
后续按锁等待、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

to:

```markdown
后续按锁等待、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
```

- [x] **Step 4: Check markdown formatting**

Run:

```bash
git diff --check -- docs-anr/105-Demo-ANR场景实现计划.md
```

Expected: command exits with code `0` and prints no whitespace errors.

- [x] **Step 5: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md
git commit -m "更新 IO 数据库文件阻塞场景矩阵"
```

### Task 5: 执行 IO / 数据库 / 文件阻塞最终验收

**Files:**
- Modify: `docs-anr/105-Demo-ANR场景实现计划.md`

- [ ] **Step 1: Run full local verification**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Install and start Demo on emulator**

Run:

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

Expected:

```text
Success
```

for install, and Demo App opens on the emulator.

- [ ] **Step 3: Trigger the IO / 数据库 / 文件阻塞 button**

If the emulator uses the same layout as previous manual验收, scroll to the lower part of the screen and tap the new button manually. If using adb input, first scroll down:

```bash
adb -s emulator-5554 shell input swipe 540 1700 540 800 300
adb -s emulator-5554 shell input tap 540 1600
```

Expected: UI becomes unresponsive for several seconds.

- [ ] **Step 4: Capture logs**

Run:

```bash
adb -s emulator-5554 logcat -d -s VibeAnrApplication AnrMonitor
```

Expected logs contain one event similar to:

```text
W VibeAnrApplication: suspect ANR captured: 00000000-0000-0000-0000-000000000000
W VibeAnrApplication: ANR report written: 00000000-0000-0000-0000-000000000000
```

The UUID in real logs will differ from `00000000-0000-0000-0000-000000000000`. Use the extraction command in the next step to select the last `ANR report written` event after clicking the IO/DB/File button.

- [ ] **Step 5: Pull the generated JSON**

Run:

```bash
mkdir -p "/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring/SDK案例分析/IO 数据库 文件阻塞/JSON"
EVENT_ID=$(adb -s emulator-5554 logcat -d -s VibeAnrApplication | awk '/ANR report written/ {print $NF}' | tail -n 1)
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/${EVENT_ID}.json > "/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring/SDK案例分析/IO 数据库 文件阻塞/JSON/${EVENT_ID}.json"
```

Expected: `SDK案例分析/IO 数据库 文件阻塞/JSON/${EVENT_ID}.json` exists and contains valid JSON.

- [ ] **Step 6: Inspect key JSON fields**

Run:

```bash
rg -n "\"primary\"|\"wallMs\"|IoDatabaseFileBlockScenario|FileAndDatabaseBlockingWorkload|FileOutputStream|FileDescriptor|SQLiteDatabase|SQLiteConnection|\"stuckTokens\"|\"suspected\"" "SDK案例分析/IO 数据库 文件阻塞/JSON/${EVENT_ID}.json"
```

Expected output contains:

```text
"primary": "CURRENT_MESSAGE_SLOW"
IoDatabaseFileBlockScenario.run
FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload
```

Expected output may contain one or more of:

```text
FileOutputStream
FileDescriptor
SQLiteDatabase
SQLiteConnection
```

Expected output should not show Barrier or Binder as the main cause:

```text
"stuckTokens": []
"suspected": false
```

- [ ] **Step 7: Fill acceptance record**

Modify the “第十批次：IO / 数据库 / 文件阻塞” acceptance section in `docs-anr/105-Demo-ANR场景实现计划.md` from:

```markdown
验收状态：未执行。

验收设备：未执行。

验收结论：未执行。执行 Task 5 后用真实日志和 JSON 字段替换本段。
```

to a concrete acceptance record using the real event id and fields from `SDK案例分析/IO 数据库 文件阻塞/JSON/${EVENT_ID}.json`. Use this command to collect the needed lines before editing:

```bash
EVENT_ID=$(adb -s emulator-5554 logcat -d -s VibeAnrApplication | awk '/ANR report written/ {print $NF}' | tail -n 1)
rg -n "\"eventType\"|\"primary\"|\"wallMs\"|IoDatabaseFileBlockScenario|FileAndDatabaseBlockingWorkload|FileOutputStream|FileDescriptor|SQLiteDatabase|SQLiteConnection|\"stuckTokens\"|\"suspected\"" "SDK案例分析/IO 数据库 文件阻塞/JSON/${EVENT_ID}.json"
```

The acceptance record must use this shape:

````markdown
验收时间：执行验收时记录的北京时间

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell input swipe 540 1700 540 800 300
adb -s emulator-5554 shell input tap 540 1600
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
EVENT_ID=$(adb -s emulator-5554 logcat -d -s VibeAnrApplication | awk '/ANR report written/ {print $NF}' | tail -n 1)
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/${EVENT_ID}.json
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
mainThread.current.wallMs = 从案例 JSON 复制的 wallMs 数值
mainThread.stackFrames contains IoDatabaseFileBlockScenario.run
mainThread.stackFrames contains FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload
mainThread.stackFrames contains 从案例 JSON 复制的文件或 SQLite 调用帧
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：IO / 数据库 / 文件阻塞场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `CURRENT_MESSAGE_SLOW`，当前消息耗时超过阈值，主线程栈能定位到 `IoDatabaseFileBlockScenario.run` 和 `FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload`，并出现文件或 SQLite 相关调用帧；Barrier 和 Binder 证据均不是本次主因，因此根因可以明确写为“按钮点击消息在主线程执行同步文件 IO / SQLite 数据库事务，导致当前消息无法及时返回”。
````

- [ ] **Step 8: Run final diff checks**

Run:

```bash
git diff --check
```

Expected: command exits with code `0` and prints no whitespace errors.

- [ ] **Step 9: Commit**

```bash
git add docs-anr/105-Demo-ANR场景实现计划.md "SDK案例分析/IO 数据库 文件阻塞/JSON/${EVENT_ID}.json"
git commit -m "记录 IO 数据库文件阻塞验收结果"
```

## Review Checklist

- [ ] 单元测试覆盖场景元数据和工作负载调用。
- [ ] Demo 按钮中文文案、布局和 Activity 接线一致。
- [ ] JSON 预期证据能区分本场景和 ContentProvider、Binder、Sync Barrier。
- [ ] 使用说明面向新人，包含操作步骤、字段解释、根因写法和修复建议。
- [ ] 场景矩阵状态和 README 覆盖场景同步更新。
- [ ] 最终验收至少包含一次真实设备或模拟器生成的 JSON。

## Self-Review

**Spec coverage:** 本计划覆盖了 “IO / 数据库 / 文件阻塞” 场景的测试、实现、按钮接入、使用说明、矩阵状态和手动 JSON 验收。文件 IO 与数据库事务都在真实 Demo 工作负载中执行，数据库不依赖第三方库，也不使用 `Thread.sleep()` 伪造耗时。

**Placeholder scan:** 计划中没有 `TBD`、`TODO`、`implement later`。验收记录使用真实事件 ID、真实耗时和真实文件或 SQLite 调用帧，避免提交无法追溯的示例值。

**Type consistency:** `IoDatabaseFileBlockScenario`、`MainThreadIoWorkload`、`FileAndDatabaseBlockingWorkload` 在测试和实现步骤中的名称保持一致。
