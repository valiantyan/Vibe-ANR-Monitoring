# Looper Printer 冲突安全实现计划

> **给智能体执行者：** 必须使用子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让 Looper `Printer` 安装在第三方 SDK 竞争场景下保持安全：避免卸载时覆盖后装 `Printer`，并在 SDK 诊断中暴露被替换或状态读取失败的问题。

**架构：** 默认策略保持保守：SDK 安装时链式转发已有 `Printer`，检测后续替换，把 `mLogging` 读取失败视为明确的 `UNKNOWN` 状态，并且卸载时不覆盖后装第三方 `Printer`。运行时报告将替换和未知状态记录为自监控计数；Matrix 式自恢复不纳入本轮 P0，因为它可能引发多个 SDK 互相抢占和包装链增长。

**技术栈：** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、JUnit 4.13.2、Android `Looper.setMessageLogging(Printer)`、现有 `SdkSelfMonitor`、现有 `AnrMonitorRuntime`。

---

## 范围检查

本计划只覆盖一个子系统：主 Looper `Printer` 冲突安全。它不实现 Matrix 式自动恢复、`IdleHandler` 轮询或公开配置开关。这些能力会等 SDK 具备可靠冲突诊断后再评估。

## 文件结构

- 修改 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt`
  - 负责安装时链式转发、带身份感知的状态检查，以及安全卸载。
- 修改 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstallerTest.kt`
  - 覆盖链式转发、替换检测、状态读取失败、安全卸载和幂等卸载。
- 新建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporter.kt`
  - 将安装器状态转换为稳定的 SDK 自监控计数，覆盖替换和状态读取失败。
- 新建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporterTest.kt`
  - 验证“已替换”和“未知”状态每次报告调用都会被记录，同时 SDK 仍持有槽位时不记录冲突。
- 修改 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
  - 在快照/报告构建前和卸载前记录 Looper `Printer` 冲突或未知状态。
- 修改 `docs-anr/103-ANR监控SDK使用说明.md`
  - 记录 `Printer` 单槽位限制和新增诊断计数。

---

### 任务 1：让 `MainLooperPrinterInstaller` 具备身份感知

**文件：**
- 修改：`anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstallerTest.kt`
- 修改：`anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt`

- [x] **步骤 1：编写覆盖替换检测、状态读取失败和安全卸载的失败测试**

在 `MainLooperPrinterInstallerTest.kt` 中新增 import：

```kotlin
import org.junit.Assert.assertSame
```

在 `MainLooperPrinterInstallerTest` 内追加这些测试：

```kotlin
    /**
     * SDK 安装后如果第三方再次设置 Printer，安装句柄应能识别当前槽位已不属于 SDK。
     */
    @Test
    fun statusReportsReplacedWhenAnotherPrinterOverridesSdkPrinter(): Unit {
        val thirdPartyPrinter = Printer {}
        var installedPrinter: Printer? = null
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        installedPrinter = thirdPartyPrinter

        assertEquals(MainLooperPrinterInstaller.InstallStatus.REPLACED, handle.status())
    }

    /**
     * SDK 安装后如果第三方再次设置 Printer，卸载时不能把第三方 Printer 覆盖回旧值。
     */
    @Test
    fun uninstallDoesNotOverwritePrinterInstalledAfterSdk(): Unit {
        val previousPrinter = Printer {}
        val thirdPartyPrinter = Printer {}
        var installedPrinter: Printer? = previousPrinter
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        installedPrinter = thirdPartyPrinter
        val result: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()

        assertEquals(MainLooperPrinterInstaller.InstallStatus.REPLACED, result)
        assertSame(thirdPartyPrinter, installedPrinter)
        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNINSTALLED, handle.status())
    }

    /**
     * 读取当前 Printer 失败时，应输出 UNKNOWN，避免把反射失败误判为三方覆盖。
     */
    @Test
    fun statusReportsUnknownWhenCurrentPrinterCannotBeRead(): Unit {
        val previousPrinter = Printer {}
        var installedPrinter: Printer? = previousPrinter
        var readCount = 0
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = {
                readCount += 1
                if (readCount == 1) {
                    installedPrinter
                } else {
                    throw IllegalStateException("reflection failed")
                }
            },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val sdkInstalledPrinter: Printer? = installedPrinter
        val result: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()

        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNKNOWN, result)
        assertSame(sdkInstalledPrinter, installedPrinter)
        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNINSTALLED, handle.status())
    }

    /**
     * 重复卸载不能再次写入 Looper Printer 槽位，避免破坏后续宿主状态。
     */
    @Test
    fun uninstallIsIdempotentAfterSafeRestore(): Unit {
        val previousPrinter = Printer {}
        var installedPrinter: Printer? = previousPrinter
        var setterCount = 0
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                setterCount += 1
                installedPrinter = printer
            },
        )

        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val firstResult: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()
        val secondResult: MainLooperPrinterInstaller.InstallStatus = handle.uninstall()

        assertEquals(MainLooperPrinterInstaller.InstallStatus.INSTALLED, firstResult)
        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNINSTALLED, secondResult)
        assertSame(previousPrinter, installedPrinter)
        assertEquals(2, setterCount)
    }
```

- [x] **步骤 2：运行聚焦测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstallerTest
```

预期：FAIL，因为 `MainLooperPrinterInstaller.InstallStatus` 和 `InstallHandle.status()` 尚不存在，并且当前 `uninstall()` 返回 `Unit`。

- [x] **步骤 3：实现身份感知状态和安全卸载**

将 `MainLooperPrinterInstaller.kt` 中 `install()` 的返回构造和 `InstallHandle` 类替换为以下代码：

```kotlin
    fun install(printer: Printer): InstallHandle {
        val reader: () -> Printer? = currentPrinterReader ?: { readCurrentPrinterFromLooper(looper = requireLooper()) }
        val setter: (Printer?) -> Unit = messageLoggingSetter ?: { value: Printer? -> requireLooper().setMessageLogging(value) }
        val previousPrinter: Printer? = reader()
        val chainedPrinter = Printer { line ->
            printer.println(line)
            previousPrinter?.println(line)
        }
        setter(chainedPrinter)
        return InstallHandle(
            previousPrinter = previousPrinter,
            installedPrinter = chainedPrinter,
            currentPrinterReader = reader,
            messageLoggingSetter = setter,
        )
    }

    /**
     * SDK 安装的 Printer 当前在 Looper 单槽位中的状态。
     */
    enum class InstallStatus {
        INSTALLED,
        REPLACED,
        UNKNOWN,
        UNINSTALLED,
    }

    /**
     * Printer 安装句柄。
     *
     * @property previousPrinter 安装前已有的 [Printer]。
     * @property installedPrinter SDK 本次安装到 Looper 的链式 [Printer]。
     * @property currentPrinterReader 当前 Looper Printer 读取入口。
     * @property messageLoggingSetter 恢复 [Printer] 的安装入口。
     */
    class InstallHandle internal constructor(
        private val previousPrinter: Printer?,
        private val installedPrinter: Printer,
        private val currentPrinterReader: () -> Printer?,
        private val messageLoggingSetter: (Printer?) -> Unit,
    ) {
        @Volatile
        private var isInstalled: Boolean = true

        /**
         * 返回当前 Looper Printer 槽位是否仍由 SDK 本次安装的链式 Printer 持有。
         */
        @Synchronized
        fun status(): InstallStatus {
            if (!isInstalled) {
                return InstallStatus.UNINSTALLED
            }
            val currentPrinter: Printer? = runCatching {
                currentPrinterReader()
            }.getOrElse {
                return InstallStatus.UNKNOWN
            }
            if (currentPrinter === installedPrinter) {
                return InstallStatus.INSTALLED
            }
            return InstallStatus.REPLACED
        }

        /**
         * 仅当当前槽位仍是 SDK 本次安装的 Printer 时恢复旧值，避免覆盖后装第三方 Printer。
         * 当前槽位读取失败时返回 [InstallStatus.UNKNOWN]，并保守跳过恢复，避免误判状态。
         *
         * @return 卸载前观察到的状态。
         */
        @Synchronized
        fun uninstall(): InstallStatus {
            val currentStatus: InstallStatus = status()
            if (currentStatus == InstallStatus.UNINSTALLED) {
                return currentStatus
            }
            if (currentStatus == InstallStatus.INSTALLED) {
                messageLoggingSetter(previousPrinter)
            }
            isInstalled = false
            return currentStatus
        }
    }
```

- [x] **步骤 4：运行聚焦测试并确认通过**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstallerTest
```

预期：PASS。

- [x] **步骤 5：提交任务 1**

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstallerTest.kt
git commit -m "fix: make looper printer uninstall identity-aware"
```

---

### 任务 2：记录 Looper Printer 冲突诊断

**文件：**
- 新建：`anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporter.kt`
- 新建：`anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporterTest.kt`
- 修改：`anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`

- [x] **步骤 1：编写诊断器的失败测试**

新建 `LooperPrinterConflictReporterTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.internal.diagnostics

import android.util.Printer
import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证 Looper Printer 单槽位冲突能进入 SDK 自诊断指标。
 */
class LooperPrinterConflictReporterTest {
    /**
     * 当前 Looper Printer 仍由 SDK 持有时，不应记录冲突指标。
     */
    @Test
    fun recordDoesNotIncrementMetricWhenPrinterStillInstalled(): Unit {
        var installedPrinter: Printer? = null
        val selfMonitor = SdkSelfMonitor()
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )
        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val reporter = LooperPrinterConflictReporter(selfMonitor = selfMonitor)

        val status: MainLooperPrinterInstaller.InstallStatus = reporter.record(handle = handle)

        assertEquals(MainLooperPrinterInstaller.InstallStatus.INSTALLED, status)
        assertNull(selfMonitor.snapshotCounters()["looper_printer_replaced"])
        assertNull(selfMonitor.snapshotCounters()["looper_printer_status_unknown"])
    }

    /**
     * 当前 Looper Printer 被第三方替换时，应记录稳定的冲突计数指标。
     */
    @Test
    fun recordIncrementsMetricWhenPrinterWasReplaced(): Unit {
        var installedPrinter: Printer? = null
        val selfMonitor = SdkSelfMonitor()
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = { installedPrinter },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )
        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        installedPrinter = Printer {}
        val reporter = LooperPrinterConflictReporter(selfMonitor = selfMonitor)

        val status: MainLooperPrinterInstaller.InstallStatus = reporter.record(handle = handle)

        assertEquals(MainLooperPrinterInstaller.InstallStatus.REPLACED, status)
        assertEquals(1L, selfMonitor.snapshotCounters()["looper_printer_replaced"])
    }

    /**
     * 当前 Looper Printer 状态读取失败时，应记录独立指标，避免误判为三方覆盖。
     */
    @Test
    fun recordIncrementsMetricWhenPrinterStatusIsUnknown(): Unit {
        var installedPrinter: Printer? = null
        var readCount = 0
        val selfMonitor = SdkSelfMonitor()
        val installer = MainLooperPrinterInstaller(
            currentPrinterReader = {
                readCount += 1
                if (readCount == 1) {
                    installedPrinter
                } else {
                    throw IllegalStateException("reflection failed")
                }
            },
            messageLoggingSetter = { printer: Printer? ->
                installedPrinter = printer
            },
        )
        val handle: MainLooperPrinterInstaller.InstallHandle = installer.install(printer = Printer {})
        val reporter = LooperPrinterConflictReporter(selfMonitor = selfMonitor)

        val status: MainLooperPrinterInstaller.InstallStatus = reporter.record(handle = handle)

        assertEquals(MainLooperPrinterInstaller.InstallStatus.UNKNOWN, status)
        assertEquals(1L, selfMonitor.snapshotCounters()["looper_printer_status_unknown"])
        assertNull(selfMonitor.snapshotCounters()["looper_printer_replaced"])
    }
}
```

- [x] **步骤 2：运行诊断器测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.internal.diagnostics.LooperPrinterConflictReporterTest
```

预期：FAIL，因为 `LooperPrinterConflictReporter` 尚不存在。

- [x] **步骤 3：实现诊断器**

新建 `LooperPrinterConflictReporter.kt`：

```kotlin
package com.valiantyan.anrmonitor.internal.diagnostics

import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller

/**
 * 将 Looper Printer 单槽位竞争状态记录到 SDK 自监控指标。
 *
 * @param selfMonitor SDK 自监控器。
 */
internal class LooperPrinterConflictReporter(
    private val selfMonitor: SdkSelfMonitor,
) {
    /**
     * 读取安装句柄状态，并在 SDK Printer 已被替换或状态不可读时记录稳定指标。
     *
     * @param handle 当前 Looper Printer 安装句柄，未安装时为空。
     * @return 当前观察到的安装状态，未安装时返回 [MainLooperPrinterInstaller.InstallStatus.UNINSTALLED]。
     */
    fun record(handle: MainLooperPrinterInstaller.InstallHandle?): MainLooperPrinterInstaller.InstallStatus {
        val status: MainLooperPrinterInstaller.InstallStatus = handle?.status()
            ?: MainLooperPrinterInstaller.InstallStatus.UNINSTALLED
        if (status == MainLooperPrinterInstaller.InstallStatus.REPLACED) {
            selfMonitor.increment(name = METRIC_LOOPER_PRINTER_REPLACED)
        }
        if (status == MainLooperPrinterInstaller.InstallStatus.UNKNOWN) {
            selfMonitor.increment(name = METRIC_LOOPER_PRINTER_STATUS_UNKNOWN)
        }
        return status
    }

    private companion object {
        const val METRIC_LOOPER_PRINTER_REPLACED: String = "looper_printer_replaced"
        const val METRIC_LOOPER_PRINTER_STATUS_UNKNOWN: String = "looper_printer_status_unknown"
    }
}
```

- [x] **步骤 4：将诊断器接入运行时**

修改 `AnrMonitorRuntime.kt` 中的导入：

```kotlin
import com.valiantyan.anrmonitor.internal.diagnostics.LooperPrinterConflictReporter
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
```

在 `sdkSelfMonitor` 后新增该属性：

```kotlin
    // Looper Printer 单槽位冲突诊断器，默认只记录指标，不主动抢回槽位。
    private val looperPrinterConflictReporter: LooperPrinterConflictReporter = LooperPrinterConflictReporter(
        selfMonitor = sdkSelfMonitor,
    )
```

在 `stop()` 中、`uninstall()` 前更新为：

```kotlin
        looperPrinterConflictReporter.record(handle = looperPrinterHandle)
        looperPrinterHandle?.uninstall()
```

更新 `captureAndReport()`，确保在 `reportAssembler.build(...)` 快照自监控指标前先记录冲突状态：

```kotlin
    private fun captureAndReport(nowUptimeMs: Long): Unit {
        val buildStartMs: Long = clock.uptimeMillis()
        looperPrinterConflictReporter.record(handle = looperPrinterHandle)
        val snapshot: AnrSnapshot = buildSnapshot(nowUptimeMs = nowUptimeMs)
        if (!incidentDeduplicator.shouldReport(snapshot = snapshot)) {
            return
        }
        listener.onSuspectAnr(snapshot = snapshot)
        val report: AnrReport = reportAssembler.build(
            snapshot = snapshot,
            buildStartMs = buildStartMs,
        )
        localWriter.write(report = report)
        listener.onConfirmedAnr(report = report)
        uploadIfEnabled(report = report)
    }
```

- [x] **步骤 5：运行聚焦测试并编译运行时**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.internal.diagnostics.LooperPrinterConflictReporterTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstallerTest
```

预期：PASS。

运行：

```bash
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [x] **步骤 6：提交任务 2**

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporter.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporterTest.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt
git commit -m "feat: report looper printer conflict status"
```

---

### 任务 3：记录竞争模型和诊断说明

**文件：**
- 修改：`docs-anr/103-ANR监控SDK使用说明.md`

- [x] **步骤 1：新增文档章节**

将以下章节追加到 `docs-anr/103-ANR监控SDK使用说明.md` 中现有 SDK 诊断或 Looper 时间线使用说明之后：

```markdown
### Looper Printer 竞争诊断

Android `Looper.setMessageLogging(Printer)` 是单槽位入口，不是多监听器注册。SDK 安装时会读取当前 `Printer` 并建立链式转发，避免覆盖宿主或已安装三方 SDK 的消息日志。

如果 SDK 安装后又有三方 SDK 调用 `setMessageLogging()`，当前 SDK 的 Looper 时间线采集可能停止更新。SDK 不会默认抢回该槽位，避免多个 SDK 互相覆盖；它会在疑似 ANR 报告和停止时检查安装句柄状态，并在 `sdkDiagnostics.selfMetrics` 中记录：

| 指标 | 含义 | 建议排查 |
| --- | --- | --- |
| `looper_printer_replaced` | SDK 安装的链式 `Printer` 已被后续 `Printer` 替换 | 检查接入顺序；要求后装 SDK 读取旧 `Printer` 并转发；或在灰度版本中评估受控自恢复 |
| `looper_printer_status_unknown` | SDK 无法读取当前 Looper `Printer` 状态，通常来自反射读取失败 | 检查目标 Android 版本和厂商限制；该状态不会被误判为三方覆盖 |

卸载时 SDK 只会在当前槽位仍是自身安装的链式 `Printer` 时恢复旧值。如果槽位已经被后装 `Printer` 接管，或当前槽位状态无法读取，SDK 会保留当前槽位不变，避免覆盖第三方后续安装或基于未知状态做错误恢复。
```

- [x] **步骤 2：搜索文档中的冲突表述**

运行：

```bash
rg -n "Looper Printer|setMessageLogging|Printer 竞争|looper_printer_replaced|looper_printer_status_unknown" docs-anr docs/superpowers
```

预期：现有文档可以提到“代理转发、检测冲突”。不能有文字声称 SDK 永远持有 Looper `Printer`、永远恢复它，或把反射读取失败当作第三方替换。

- [x] **步骤 3：提交任务 3**

```bash
git add docs-anr/103-ANR监控SDK使用说明.md
git commit -m "docs: explain looper printer conflict diagnostics"
```

---

### 任务 4：最终验证

**文件：**
- 验证：`anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt`
- 验证：`anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporter.kt`
- 验证：`anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
- 验证：`docs-anr/103-ANR监控SDK使用说明.md`

- [x] **步骤 1：运行 Looper 和 diagnostics 单元测试**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstallerTest --tests com.valiantyan.anrmonitor.internal.diagnostics.LooperPrinterConflictReporterTest --tests com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitorTest
```

预期：PASS。

- [x] **步骤 2：运行 SDK 单元测试套件**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest
```

预期：BUILD SUCCESSFUL。

- [x] **步骤 3：运行 Kotlin 编译**

运行：

```bash
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [x] **步骤 4：检查 git diff，避免意外扩大范围**

运行：

```bash
git diff --stat
```

预期：只有安装器、安装器测试、冲突诊断器、诊断器测试、运行时接线和文档文件发生变化。

运行：

```bash
git diff -- anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt
```

预期：本轮 P0 实现中没有 Matrix 式 `IdleHandler` 恢复、没有默认抢回逻辑，也没有公开配置变更。

- [x] **步骤 5：如有必要，提交最终验证修复**

如果任务 4 产生了任何修复，提交这些变更：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporter.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstallerTest.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/internal/diagnostics/LooperPrinterConflictReporterTest.kt docs-anr/103-ANR监控SDK使用说明.md
git commit -m "fix: address looper printer conflict review findings"
```

预期：如果任务 1-3 已经通过且没有后续修复，则不需要额外提交。

---

## 延后 P1：受控 Matrix 式恢复

不要在上述 P0 任务中实现这一项。后续计划可以新增默认关闭的 `recoverLooperPrinterConflict` 选项，并满足这些约束：

- 恢复默认关闭。
- 恢复有冷却时间，例如 60 秒。
- 恢复有进程内最大次数限制。
- 恢复时把当前 `Printer` 作为原始转发对象包进去，而不是丢弃它。
- 恢复会记录 `looper_printer_recovery_attempted` 和 `looper_printer_recovery_succeeded`。
- 恢复需要通过重复替换测试，确保包装链增长有边界。

---

## 自检

**规格覆盖：** 本计划覆盖安全卸载、替换检测、状态读取失败处理、SDK 诊断、运行时接线、测试和文档。Matrix 式恢复明确延后，因为当前需求是安全修复隐藏竞争风险。

**占位内容扫描：** 没有残留 `TBD`、`TODO`、“稍后实现” 或未指定的测试步骤。每个代码变更步骤都包含具体 Kotlin 或 Markdown 内容。

**类型一致性：** `MainLooperPrinterInstaller.InstallStatus`、`InstallHandle.status()`、`InstallHandle.uninstall(): InstallStatus`、`LooperPrinterConflictReporter.record(...)`、`looper_printer_replaced` 和 `looper_printer_status_unknown` 在各任务中保持一致。
