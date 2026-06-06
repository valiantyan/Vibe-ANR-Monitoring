# ANR 监控 SDK 全量实施计划

> **给 agent 执行者：** 必须使用子技能 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪进度。

**目标：** 构建覆盖 `docs-anr/01-第一篇-设计原理及影响因素.md` 到 `docs-anr/05-第五篇-告别SharedPreference等待.md` 全部核心内容的 Android ANR 监控 SDK，包括系统超时模型、主线程消息时间线、慢消息采样、Pending 队列、线程 CPU、Checktime、系统环境、Barrier、SharedPreferences、跨进程阻塞疑似、完整归因、上报协议、治理闭环和验收体系。

**架构：** 新增独立的 `anr-monitor-sdk` Android Library 模块。领域模型和归因逻辑保持纯 Kotlin，方便 JVM 单元测试；Android 运行时访问集中在 collector/runtime 包内；示例 `app` 只作为接入、复现和验收入口。阶段一到阶段四只是交付顺序，不是能力范围裁剪：本计划最终必须覆盖 01 到 05 文档的全部 SDK 能力。

**技术栈：** Android Gradle Plugin 8.5.2、Kotlin 1.9.22、minSdk 23、targetSdk 35、JUnit 4.13.2、Android framework `Looper`/`Handler`/`MessageQueue`/`Debug`/`ActivityManager`、`/proc` 自进程读取、SharedPreferences 包装入口、本地 JSON/gzip 报告、可选远程上传扩展点。

---

## 范围检查

本计划实现全量 SDK。阶段一到阶段四是渐进交付里程碑，不代表后续阶段能力可以裁掉。

阶段一：骨架版本：

- SDK 模块和 Gradle 接线。
- 对外 API 和不可变配置。
- 主线程消息环形缓冲区。
- Looper 消息日志采集器。
- Watchdog 疑似 ANR 触发。
- 主线程栈快照。
- 本地 JSON 报告路径。

阶段二：证据版本：

- Pending 队列快照和反射失败诊断。
- 基于 Pending 队头的 Sync Barrier 疑似证据。
- 基础归因规则：当前消息慢、历史消息慢、消息风暴、Barrier 疑似、SP 栈命中和未知。
- 用于手动验证的 demo app 场景。

阶段三：线上诊断能力：

- 慢消息堆栈采样和栈 hash 聚合。
- 线程 CPU 排名、可疑线程栈和 `/proc/self/task/<tid>/stat` 自进程读取。
- Checktime 调度能力检测和系统/设备环境采集。
- ActivityManager confirmed ANR 增强、组件阈值配置、ActivityThread.H 关键组件消息识别。
- SP 文件健康度扫描、包装 API、调用栈和写入耗时记录。
- 报告压缩、重试、限频、采样、保留策略和 SDK 自监控。

阶段四：专项增强和治理闭环：

- `nativePollOnce(timeoutMillis)` 监控和 Barrier token 配对诊断。
- `QueuedWork.waitToFinish` 绕过灰度能力，默认关闭并带白名单、回滚和一致性监控。
- Binder/跨进程阻塞疑似分析和线下 Trace/Perfetto 复核入口。
- SP 静态扫描或 Gradle 插桩辅助治理。
- 服务端聚类字段、owner hint、看板协议和治理闭环。

## 输入来源与能力追溯矩阵

| 输入文档 | 必须覆盖的 SDK 能力 | 计划任务 |
| --- | --- | --- |
| 01 设计原理及影响因素 | ANR 类型和阈值、系统等待超时模型、Reason/Trace 不等于根因、当前/历史/Pending/环境综合判断 | 任务 2、4、5、7、12、13、14、20 |
| 02 监控工具与分析思路 | Raster 三段时间线、慢消息采样、Wall/Cpu、Checktime、AnrInfo、Logcat/Kernel/Meminfo 可得性分级 | 任务 4、5、6、11、12、13、14、18、19 |
| 03 实例剖析集锦 | 当前慢、历史慢、累计慢、消息风暴、进程内 IO、外部系统负载、Binder/跨进程疑似、证据链归因 | 任务 7、11、12、13、17、18、20 |
| 04 Barrier 导致主线程假死 | Pending 队头 `target == null`、Barrier token、同步消息被挡、`nativePollOnce` 不能简单视为空闲 | 任务 6、7、16、19、20 |
| 05 告别 SharedPreference 等待 | `SP_LOAD_WAIT`、`SP_APPLY_WAIT`、SP 文件健康度、包装 API、QueuedWork 绕过灰度和数据一致性治理 | 任务 7、15、18、19、20 |

## 文件结构

下列仓库路径均相对于 `/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`。

项目配置：

- 修改 `settings.gradle.kts`: include `:anr-monitor-sdk`.
- 修改 `build.gradle.kts`: 新增 Android Library 插件 alias.
- 修改 `gradle/libs.versions.toml`: 新增 `android-library` 插件和 `junit` 依赖.
- 创建 `anr-monitor-sdk/build.gradle.kts`: Android Library 构建脚本.
- 创建 `anr-monitor-sdk/src/main/AndroidManifest.xml`: 最小 SDK manifest.

SDK API：

- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitor.kt`: 幂等的公开安装/卸载入口.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt`: 不可变配置和阈值.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrEventListener.kt`: debug 和自动化监听回调.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrReportUploader.kt`: 宿主上报扩展点.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/UploadResult.kt`: 上报结果类型.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorSession.kt`: 已安装 session 句柄.

领域模型：

- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrEventType.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrAttributionCode.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/Confidence.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MessageRecord.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/PendingMessage.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/PendingQueueSnapshot.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/StackTraceSnapshot.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AttributionResult.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/SdkDiagnostics.kt`.

核心工具：

- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/clock/Clock.kt`: 纯时间源接口.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/clock/AndroidClock.kt`: Android `SystemClock` 适配器.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/clock/ThreadCpuClock.kt`: 线程 CPU 时间源.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/privacy/ClassNameSanitizer.kt`: 类名隐私策略.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/timeline/MessageRingBuffer.kt`: 有界历史缓冲区.

采集器：

- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/LooperMessageParser.kt`: 解析 Looper 日志行.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollector.kt`: 采集当前/历史 dispatch 记录.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt`: 安装链式 Printer.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/watchdog/HeartbeatState.kt`: 纯超时状态机.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/watchdog/AnrWatchdog.kt`: Android 后台 Watchdog.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/stack/MainThreadStackCollector.kt`: 主线程 Java 栈采集.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueAnalyzer.kt`: 纯 Pending 证据摘要.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueSnapshotter.kt`: Android 反射快照器.

归因和报告：

- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionThresholds.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/JsonEscaper.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/local/LocalAnrReportWriter.kt`.
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`.

Demo app：

- 修改 `app/build.gradle.kts`: 依赖 `:anr-monitor-sdk`.
- 创建 `app/src/main/java/com/valiantyan/vibeanrmonitoring/VibeAnrApplication.kt`: 安装 SDK.
- 修改 `app/src/main/AndroidManifest.xml`: 注册 Application.
- 修改 `app/src/main/res/layout/activity_main.xml`: 新增场景按钮.
- 修改 `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`: 触发当前慢、消息风暴、SP apply 等待场景.

测试：

- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorConfigTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/privacy/ClassNameSanitizerTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MessageRingBufferTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/LooperMessageParserTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollectorTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/watchdog/HeartbeatStateTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueAnalyzerTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`.

全量诊断扩展：

- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/stack/SlowMessageStackSampler.kt`: 慢消息周期采样。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/threadcpu/ThreadCpuSnapshotter.kt`: 线程 CPU 排名和可疑线程栈。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/checktime/ChecktimeMonitor.kt`: 调度能力检测。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/environment/EnvironmentSnapshotter.kt`: 系统和设备环境采集。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/anrinfo/AnrInfoCollector.kt`: `ActivityManager.getProcessesInErrorState()` 确认 ANR。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/SharedPreferencesHealthScanner.kt`: SP 文件大小、key 数和健康度扫描。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/MonitoredSharedPreferences.kt`: SP 包装入口和调用栈记录。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/QueuedWorkBypassController.kt`: `QueuedWork.waitToFinish` 绕过灰度控制。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/barrier/BarrierTokenTracker.kt`: Barrier token 配对诊断。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/nativepoll/NativePollOnceMonitor.kt`: `nativePollOnce(timeoutMillis)` 证据记录。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifier.kt`: Binder/跨进程阻塞疑似识别。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetentionPolicy.kt`: 本地报告保留策略。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetryQueue.kt`: 上报重试队列。
- 创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/SdkSelfMonitor.kt`: SDK 自监控指标。
- 创建 `docs-anr/101-ANR监控SDK全量验收记录.md`: 全量能力验收记录。
- 创建 `docs-anr/102-ANR监控SDK服务端消费协议.md`: 服务端聚类、看板和 owner hint 协议。

全量诊断测试：

- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/stack/SlowMessageStackSamplerTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/threadcpu/ThreadCpuSnapshotterTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/checktime/ChecktimeMonitorTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/environment/EnvironmentSnapshotterTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/sharedprefs/SharedPreferencesHealthScannerTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/barrier/BarrierTokenTrackerTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifierTest.kt`.
- 创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetentionPolicyTest.kt`.

## 实施任务

### 任务 1：创建 SDK 模块和测试接线

**文件：**
- 修改： `settings.gradle.kts`
- 修改： `build.gradle.kts`
- 修改： `gradle/libs.versions.toml`
- 创建： `anr-monitor-sdk/build.gradle.kts`
- 创建： `anr-monitor-sdk/src/main/AndroidManifest.xml`

- [x] **步骤 1：运行缺失模块命令**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest
```

预期：FAIL，错误信息包含 `Project 'anr-monitor-sdk' not found`.

- [x] **步骤 2：接入 Gradle 模块**

使用以下完整内容写入 `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Vibe-ANR-Monitoring"
include(":app")
include(":anr-monitor-sdk")
```

使用以下完整内容写入 `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

使用以下完整内容写入 `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.5.2"
appcompat = "1.7.1"
constraintlayout = "2.2.1"
coreKtx = "1.15.0"
junit = "4.13.2"
kotlin = "1.9.22"
material = "1.13.0"

[libraries]
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
androidx-constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

创建 `anr-monitor-sdk/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.valiantyan.anrmonitor"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation(libs.junit)
}
```

创建 `anr-monitor-sdk/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

创建 `anr-monitor-sdk/consumer-rules.pro`：

```proguard
# 首阶段 SDK 不需要公开 keep 规则，因为没有向宿主暴露反射 API 面。
```

- [x] **步骤 3：验证模块任务存在**

运行：

```bash
./gradlew :anr-monitor-sdk:tasks
```

预期：PASS，且输出包含 `Verification tasks`.

- [x] **步骤 4：提交模块骨架**

运行：

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml anr-monitor-sdk
git commit -m "新增 ANR SDK 模块骨架"
```

预期：提交成功。

### 任务 2：定义对外 API 和领域模型

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorConfigTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrEventListener.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrReportUploader.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/UploadResult.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorSession.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitor.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrEventType.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrAttributionCode.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/Confidence.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MessageRecord.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/PendingMessage.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/PendingQueueSnapshot.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/StackTraceSnapshot.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AttributionResult.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/SdkDiagnostics.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt`

- [x] **步骤 1：编写失败的配置测试**

创建 `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorConfigTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrMonitorConfigTest {
    @Test
    fun createDefaultConfigUsesSafeInitialBudgets(): Unit {
        val config = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
        )

        assertTrue(config.enabled)
        assertEquals(120, config.historyBufferSize)
        assertEquals(5_000L, config.suspectAnrMs)
        assertEquals(200, config.pendingSnapshotMaxDepth)
        assertFalse(config.enableQueuedWorkBypass)
    }

    @Test
    fun createConfigClampsSampleRateIntoValidRange(): Unit {
        val highConfig = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
            sampleRate = 9.0f,
        )
        val lowConfig = AnrMonitorConfig(
            appId = "demo",
            environment = "debug",
            sampleRate = -1.0f,
        )

        assertEquals(1.0f, highConfig.normalizedSampleRate, 0.0f)
        assertEquals(0.0f, lowConfig.normalizedSampleRate, 0.0f)
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.api.AnrMonitorConfigTest
```

预期：FAIL，错误为 `Unresolved reference: AnrMonitorConfig`.

- [x] **步骤 3：新增 API 和模型代码**

创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

enum class AnrPrivacyMode {
    SAFE,
    STRICT,
}

data class AnrMonitorConfig(
    val appId: String,
    val environment: String,
    val enabled: Boolean = true,
    val uploadEnabled: Boolean = false,
    val sampleRate: Float = 1.0f,
    val historyBufferSize: Int = 120,
    val shortMessageAggregateMs: Long = 300L,
    val slowMessageMs: Long = 1_000L,
    val stackSampleIntervalMs: Long = 500L,
    val maxStackSamplesPerMessage: Int = 10,
    val watchdogIntervalMs: Long = 1_000L,
    val suspectAnrMs: Long = 5_000L,
    val pendingSnapshotMaxDepth: Int = 200,
    val capturePendingQueue: Boolean = true,
    val captureSpHealth: Boolean = true,
    val enableQueuedWorkBypass: Boolean = false,
    val privacyMode: AnrPrivacyMode = AnrPrivacyMode.SAFE,
) {
    val normalizedSampleRate: Float = sampleRate.coerceIn(
        minimumValue = 0.0f,
        maximumValue = 1.0f,
    )
}
```

创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/UploadResult.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

sealed interface UploadResult {
    data object Success : UploadResult
    data object Skip : UploadResult
    data class Failure(
        val reason: String,
    ) : UploadResult
}
```

创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrReportUploader.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrReport

fun interface AnrReportUploader {
    fun upload(report: AnrReport): UploadResult
}
```

创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrEventListener.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

interface AnrEventListener {
    fun onSuspectAnr(snapshot: AnrSnapshot): Unit = Unit
    fun onConfirmedAnr(report: AnrReport): Unit = Unit
    fun onMonitorError(error: Throwable): Unit = Unit
}
```

创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorSession.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

class AnrMonitorSession internal constructor(
    val config: AnrMonitorConfig,
    private val stopAction: () -> Unit,
) {
    @Volatile
    var isRunning: Boolean = config.enabled
        private set

    fun stop(): Unit {
        if (!isRunning) {
            return
        }
        stopAction()
        isRunning = false
    }
}
```

创建 `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitor.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

import android.content.Context

object AnrMonitor {
    @Volatile
    private var activeSession: AnrMonitorSession? = null

    @Synchronized
    fun install(
        context: Context,
        config: AnrMonitorConfig,
        uploader: AnrReportUploader = AnrReportUploader { UploadResult.Skip },
        listener: AnrEventListener = object : AnrEventListener {},
    ): AnrMonitorSession {
        val existingSession: AnrMonitorSession? = activeSession
        if (existingSession != null) {
            return existingSession
        }
        context.applicationContext
        val session = AnrMonitorSession(
            config = config,
            stopAction = {},
        )
        activeSession = session
        return session
    }

    @Synchronized
    fun uninstall(): Unit {
        activeSession?.stop()
        activeSession = null
    }
}
```

创建枚举模型文件：

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrEventType.kt
package com.valiantyan.anrmonitor.domain.model

enum class AnrEventType {
    SUSPECT_ANR,
    CONFIRMED_ANR,
}
```

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrAttributionCode.kt
package com.valiantyan.anrmonitor.domain.model

enum class AnrAttributionCode {
    CURRENT_MESSAGE_SLOW,
    HISTORY_MESSAGE_SLOW,
    MESSAGE_STORM,
    SYNC_BARRIER_STUCK,
    SP_LOAD_WAIT,
    SP_APPLY_WAIT,
    UNKNOWN_INSUFFICIENT_EVIDENCE,
}
```

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/Confidence.kt
package com.valiantyan.anrmonitor.domain.model

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}
```

创建 `MessageRecord.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.model

enum class MessageRecordKind {
    CURRENT,
    HISTORY,
    AGGREGATED,
    IDLE,
    COMPONENT,
}

data class MessageRecord(
    val seq: Long,
    val kind: MessageRecordKind,
    val messageType: String,
    val what: Int?,
    val targetClass: String,
    val callbackClass: String?,
    val isCriticalComponent: Boolean,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val wallMs: Long,
    val cpuMs: Long,
    val count: Int = 1,
    val sampleStackIds: List<String> = emptyList(),
)
```

创建 `PendingMessage.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.model

data class PendingMessage(
    val index: Int,
    val whenUptimeMs: Long,
    val delayMs: Long,
    val blockedMs: Long,
    val what: Int?,
    val arg1: Int,
    val arg2: Int,
    val targetClass: String?,
    val callbackClass: String?,
    val objClass: String?,
    val isAsynchronous: Boolean?,
    val isBarrierLike: Boolean,
    val isCriticalComponent: Boolean,
)
```

创建 `PendingQueueSnapshot.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.model

data class PendingQueueSnapshot(
    val available: Boolean,
    val truncated: Boolean,
    val maxDepth: Int,
    val messages: List<PendingMessage>,
    val failureReason: String?,
) {
    companion object {
        fun unavailable(
            maxDepth: Int,
            failureReason: String,
        ): PendingQueueSnapshot {
            return PendingQueueSnapshot(
                available = false,
                truncated = false,
                maxDepth = maxDepth,
                messages = emptyList(),
                failureReason = failureReason,
            )
        }
    }
}
```

创建栈、归因、诊断、快照和报告模型文件：

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/StackTraceSnapshot.kt
package com.valiantyan.anrmonitor.domain.model

data class StackTraceSnapshot(
    val stackId: String,
    val threadName: String,
    val frames: List<String>,
)
```

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AttributionResult.kt
package com.valiantyan.anrmonitor.domain.model

data class AttributionResult(
    val primaryCode: AnrAttributionCode,
    val secondaryCodes: List<AnrAttributionCode>,
    val confidence: Confidence,
    val evidenceItems: List<String>,
    val missingEvidence: List<String>,
    val actionSuggestions: List<String>,
)
```

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/SdkDiagnostics.kt
package com.valiantyan.anrmonitor.domain.model

data class SdkDiagnostics(
    val pendingAvailable: Boolean,
    val reportBuildCostMs: Long,
    val collectorFailures: List<String>,
)
```

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt
package com.valiantyan.anrmonitor.domain.model

data class AnrSnapshot(
    val eventId: String,
    val eventType: AnrEventType,
    val appId: String,
    val environment: String,
    val timeUptimeMs: Long,
    val currentMessage: MessageRecord?,
    val historyMessages: List<MessageRecord>,
    val pendingQueue: PendingQueueSnapshot,
    val mainThreadStack: StackTraceSnapshot,
)
```

```kotlin
// anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt
package com.valiantyan.anrmonitor.domain.model

data class AnrReport(
    val schemaVersion: Int,
    val snapshot: AnrSnapshot,
    val attribution: AttributionResult,
    val diagnostics: SdkDiagnostics,
) {
    companion object {
        fun empty(
            appId: String,
            environment: String,
        ): AnrReport {
            val snapshot = AnrSnapshot(
                eventId = "empty",
                eventType = AnrEventType.SUSPECT_ANR,
                appId = appId,
                environment = environment,
                timeUptimeMs = 0L,
                currentMessage = null,
                historyMessages = emptyList(),
                pendingQueue = PendingQueueSnapshot.unavailable(
                    maxDepth = 0,
                    failureReason = "empty report",
                ),
                mainThreadStack = StackTraceSnapshot(
                    stackId = "empty-main",
                    threadName = "main",
                    frames = emptyList(),
                ),
            )
            return AnrReport(
                schemaVersion = 1,
                snapshot = snapshot,
                attribution = AttributionResult(
                    primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
                    secondaryCodes = emptyList(),
                    confidence = Confidence.UNKNOWN,
                    evidenceItems = emptyList(),
                    missingEvidence = listOf("empty report"),
                    actionSuggestions = emptyList(),
                ),
                diagnostics = SdkDiagnostics(
                    pendingAvailable = false,
                    reportBuildCostMs = 0L,
                    collectorFailures = emptyList(),
                ),
            )
        }
    }
}
```

- [x] **步骤 4：运行配置测试**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.api.AnrMonitorConfigTest
```

预期：PASS。

- [x] **步骤 5：提交 API 和模型**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor
git commit -m "新增 ANR SDK 对外 API 与领域模型"
```

预期：提交成功。

### 任务 3：新增隐私脱敏器和消息环形缓冲区

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/privacy/ClassNameSanitizerTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MessageRingBufferTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/clock/Clock.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/clock/AndroidClock.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/clock/ThreadCpuClock.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/privacy/ClassNameSanitizer.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/timeline/MessageRingBuffer.kt`

- [x] **步骤 1：编写失败的单元测试**

创建 `ClassNameSanitizerTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.privacy

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassNameSanitizerTest {
    @Test
    fun sanitizeSafeModeKeepsSystemClassAndTrimsAnonymousSuffix(): Unit {
        val sanitizer = ClassNameSanitizer(
            privacyMode = AnrPrivacyMode.SAFE,
        )

        val value: String = sanitizer.sanitizeClassName(
            className = "android.app.ActivityThread\$H\$1",
        )

        assertEquals("android.app.ActivityThread\$H", value)
    }

    @Test
    fun sanitizeStrictModeReturnsStableHashPrefix(): Unit {
        val sanitizer = ClassNameSanitizer(
            privacyMode = AnrPrivacyMode.STRICT,
        )

        val value: String = sanitizer.sanitizeClassName(
            className = "com.example.secret.PaymentHandler",
        )

        assertTrue(value.startsWith("hash_"))
        assertEquals(value, sanitizer.sanitizeClassName(className = "com.example.secret.PaymentHandler"))
    }
}
```

创建 `MessageRingBufferTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRingBufferTest {
    @Test
    fun addRecordsEvictsOldestWhenCapacityExceeded(): Unit {
        val buffer = MessageRingBuffer(
            capacity = 2,
        )

        buffer.add(record = record(seq = 1L, wallMs = 10L))
        buffer.add(record = record(seq = 2L, wallMs = 20L))
        buffer.add(record = record(seq = 3L, wallMs = 30L))

        assertEquals(listOf(2L, 3L), buffer.snapshot().map { item -> item.seq })
    }

    @Test
    fun findSlowMessagesReturnsRecordsAtOrAboveThreshold(): Unit {
        val buffer = MessageRingBuffer(
            capacity = 4,
        )

        buffer.add(record = record(seq = 1L, wallMs = 100L))
        buffer.add(record = record(seq = 2L, wallMs = 1_500L))

        assertEquals(listOf(2L), buffer.findSlowMessages(thresholdMs = 1_000L).map { item -> item.seq })
    }

    private fun record(
        seq: Long,
        wallMs: Long,
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = MessageRecordKind.HISTORY,
            messageType = "dispatch",
            what = null,
            targetClass = "android.os.Handler",
            callbackClass = null,
            isCriticalComponent = false,
            startUptimeMs = 0L,
            endUptimeMs = wallMs,
            wallMs = wallMs,
            cpuMs = wallMs,
        )
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizerTest --tests com.valiantyan.anrmonitor.core.timeline.MessageRingBufferTest
```

预期：FAIL，出现未解析引用 `ClassNameSanitizer` 和 `MessageRingBuffer`.

- [x] **步骤 3：新增核心实现**

创建 `Clock.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.clock

interface Clock {
    fun uptimeMillis(): Long
}
```

创建 `AndroidClock.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.clock

import android.os.SystemClock

class AndroidClock : Clock {
    override fun uptimeMillis(): Long {
        return SystemClock.uptimeMillis()
    }
}
```

创建 `ThreadCpuClock.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.clock

import android.os.Debug

class ThreadCpuClock {
    fun currentThreadCpuMs(): Long {
        return Debug.threadCpuTimeNanos() / NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
```

创建 `ClassNameSanitizer.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.privacy

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import java.security.MessageDigest

class ClassNameSanitizer(
    private val privacyMode: AnrPrivacyMode,
) {
    fun sanitizeClassName(className: String?): String {
        if (className.isNullOrBlank()) {
            return ""
        }
        val normalizedName: String = trimAnonymousSuffix(className = className)
        if (privacyMode == AnrPrivacyMode.STRICT) {
            return "hash_${hash(value = normalizedName)}"
        }
        return normalizedName
    }

    private fun trimAnonymousSuffix(className: String): String {
        return className.replace(
            regex = Regex("\\$\\d+$"),
            replacement = "",
        )
    }

    private fun hash(value: String): String {
        val digest: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(n = 8).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
```

创建 `MessageRingBuffer.kt`：

```kotlin
package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MessageRecord

class MessageRingBuffer(
    private val capacity: Int,
) {
    private val records: ArrayDeque<MessageRecord> = ArrayDeque()

    @Synchronized
    fun add(record: MessageRecord): Unit {
        if (capacity <= 0) {
            return
        }
        while (records.size >= capacity) {
            records.removeFirst()
        }
        records.addLast(record)
    }

    @Synchronized
    fun snapshot(): List<MessageRecord> {
        return records.toList()
    }

    @Synchronized
    fun findSlowMessages(thresholdMs: Long): List<MessageRecord> {
        return records.filter { record -> record.wallMs >= thresholdMs }
    }

    @Synchronized
    fun clear(): Unit {
        records.clear()
    }
}
```

- [x] **步骤 4：运行测试**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizerTest --tests com.valiantyan.anrmonitor.core.timeline.MessageRingBufferTest
```

预期：PASS。

- [x] **步骤 5：提交核心工具**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core
git commit -m "新增 ANR SDK 核心工具与消息缓冲"
```

预期：提交成功。

### 任务 4：采集主 Looper 消息时间线

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/LooperMessageParserTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollectorTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/LooperDispatchEvent.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/LooperMessageParser.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollector.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperPrinterInstaller.kt`

- [x] **步骤 1：编写失败的解析器和采集器测试**

创建 `LooperMessageParserTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.looper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooperMessageParserTest {
    @Test
    fun parseDispatchStartExtractsTargetAndWhat(): Unit {
        val line = ">>>>> Dispatching to Handler (android.app.ActivityThread\$H) {12345} null: 115"

        val event: LooperDispatchEvent = LooperMessageParser.parse(line = line)

        assertTrue(event.isStart)
        assertEquals("android.app.ActivityThread\$H", event.targetClass)
        assertEquals(115, event.what)
    }

    @Test
    fun parseDispatchEndMarksEnd(): Unit {
        val line = "<<<<< Finished to Handler (android.app.ActivityThread\$H) {12345} null"

        val event: LooperDispatchEvent = LooperMessageParser.parse(line = line)

        assertFalse(event.isStart)
        assertEquals("android.app.ActivityThread\$H", event.targetClass)
    }
}
```

创建 `MainLooperTimelineCollectorTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.looper

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainLooperTimelineCollectorTest {
    @Test
    fun onLooperLogCreatesCurrentThenHistoryRecord(): Unit {
        val clock = FakeClock(values = arrayDequeOf(100L, 250L))
        val cpuClock = FakeCpuClock(values = arrayDequeOf(10L, 80L))
        val buffer = MessageRingBuffer(capacity = 4)
        val collector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            historyBuffer = buffer,
        )

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        collector.onLooperLog(line = "<<<<< Finished to Handler (android.os.Handler) {12345} null")

        val history = buffer.snapshot()
        assertNull(collector.currentMessage())
        assertEquals(1, history.size)
        assertEquals(MessageRecordKind.HISTORY, history.first().kind)
        assertEquals(150L, history.first().wallMs)
        assertEquals(70L, history.first().cpuMs)
    }

    private class FakeClock(
        private val values: ArrayDeque<Long>,
    ) : Clock {
        override fun uptimeMillis(): Long {
            return values.removeFirst()
        }
    }

    private class FakeCpuClock(
        private val values: ArrayDeque<Long>,
    ) : MainLooperTimelineCollector.CpuClock {
        override fun currentThreadCpuMs(): Long {
            return values.removeFirst()
        }
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.LooperMessageParserTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollectorTest
```

预期：FAIL，出现未解析引用 `LooperMessageParser` 和 `MainLooperTimelineCollector`.

- [x] **步骤 3：新增 Looper 时间线采集器**

创建 `LooperDispatchEvent.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.looper

data class LooperDispatchEvent(
    val isStart: Boolean,
    val targetClass: String,
    val callbackClass: String?,
    val what: Int?,
)
```

创建 `LooperMessageParser.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.looper

object LooperMessageParser {
    fun parse(line: String): LooperDispatchEvent {
        val isStart: Boolean = line.startsWith(prefix = ">>>>>")
        val targetClass: String = extractBetween(
            value = line,
            start = "(",
            end = ")",
        )
        return LooperDispatchEvent(
            isStart = isStart,
            targetClass = targetClass,
            callbackClass = extractCallback(line = line),
            what = extractWhat(line = line),
        )
    }

    private fun extractBetween(
        value: String,
        start: String,
        end: String,
    ): String {
        val startIndex: Int = value.indexOf(string = start)
        if (startIndex < 0) {
            return ""
        }
        val contentStart: Int = startIndex + start.length
        val endIndex: Int = value.indexOf(
            string = end,
            startIndex = contentStart,
        )
        if (endIndex < contentStart) {
            return ""
        }
        return value.substring(
            startIndex = contentStart,
            endIndex = endIndex,
        )
    }

    private fun extractCallback(line: String): String? {
        val callbackPart: String = line.substringAfter(
            delimiter = "} ",
            missingDelimiterValue = "",
        )
        val callback: String = callbackPart.substringBefore(
            delimiter = ":",
            missingDelimiterValue = callbackPart,
        ).trim()
        if (callback.isBlank() || callback == "null") {
            return null
        }
        return callback
    }

    private fun extractWhat(line: String): Int? {
        val value: String = line.substringAfterLast(
            delimiter = ":",
            missingDelimiterValue = "",
        ).trim()
        return value.toIntOrNull()
    }
}
```

创建 `MainLooperTimelineCollector.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.looper

import android.util.Printer
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import java.util.concurrent.atomic.AtomicLong

class MainLooperTimelineCollector(
    private val clock: Clock,
    private val threadCpuClock: CpuClock,
    private val sanitizer: ClassNameSanitizer,
    private val historyBuffer: MessageRingBuffer,
) : Printer {
    private val sequence: AtomicLong = AtomicLong(0L)

    @Volatile
    private var currentRecordStart: CurrentRecordStart? = null

    override fun println(x: String): Unit {
        onLooperLog(line = x)
    }

    fun onLooperLog(line: String): Unit {
        val event: LooperDispatchEvent = LooperMessageParser.parse(line = line)
        if (event.isStart) {
            currentRecordStart = createCurrentRecordStart(event = event)
            return
        }
        finishCurrentRecord(event = event)
    }

    fun currentMessage(): MessageRecord? {
        val start: CurrentRecordStart = currentRecordStart ?: return null
        val nowUptimeMs: Long = clock.uptimeMillis()
        val nowCpuMs: Long = threadCpuClock.currentThreadCpuMs()
        return start.toRecord(
            kind = MessageRecordKind.CURRENT,
            endUptimeMs = null,
            wallMs = (nowUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L),
            cpuMs = (nowCpuMs - start.startCpuMs).coerceAtLeast(minimumValue = 0L),
        )
    }

    fun historyMessages(): List<MessageRecord> {
        return historyBuffer.snapshot()
    }

    private fun createCurrentRecordStart(event: LooperDispatchEvent): CurrentRecordStart {
        return CurrentRecordStart(
            seq = sequence.incrementAndGet(),
            targetClass = sanitizer.sanitizeClassName(className = event.targetClass),
            callbackClass = sanitizer.sanitizeClassName(className = event.callbackClass).ifBlank { null },
            what = event.what,
            startUptimeMs = clock.uptimeMillis(),
            startCpuMs = threadCpuClock.currentThreadCpuMs(),
        )
    }

    private fun finishCurrentRecord(event: LooperDispatchEvent): Unit {
        val start: CurrentRecordStart = currentRecordStart ?: return
        val endUptimeMs: Long = clock.uptimeMillis()
        val endCpuMs: Long = threadCpuClock.currentThreadCpuMs()
        val record: MessageRecord = start.toRecord(
            kind = MessageRecordKind.HISTORY,
            endUptimeMs = endUptimeMs,
            wallMs = (endUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L),
            cpuMs = (endCpuMs - start.startCpuMs).coerceAtLeast(minimumValue = 0L),
        )
        historyBuffer.add(record = record.copy(targetClass = sanitizer.sanitizeClassName(className = event.targetClass).ifBlank { record.targetClass }))
        currentRecordStart = null
    }

    interface CpuClock {
        fun currentThreadCpuMs(): Long
    }

    private data class CurrentRecordStart(
        val seq: Long,
        val targetClass: String,
        val callbackClass: String?,
        val what: Int?,
        val startUptimeMs: Long,
        val startCpuMs: Long,
    ) {
        fun toRecord(
            kind: MessageRecordKind,
            endUptimeMs: Long?,
            wallMs: Long,
            cpuMs: Long,
        ): MessageRecord {
            return MessageRecord(
                seq = seq,
                kind = kind,
                messageType = "looper_dispatch",
                what = what,
                targetClass = targetClass,
                callbackClass = callbackClass,
                isCriticalComponent = targetClass == "android.app.ActivityThread\$H",
                startUptimeMs = startUptimeMs,
                endUptimeMs = endUptimeMs,
                wallMs = wallMs,
                cpuMs = cpuMs,
            )
        }
    }
}
```

创建 `MainLooperPrinterInstaller.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.looper

import android.os.Looper
import android.util.Printer

class MainLooperPrinterInstaller(
    private val looper: Looper = Looper.getMainLooper(),
) {
    fun install(printer: Printer): InstallResult {
        val previousPrinter: Printer? = readCurrentPrinter()
        val chainedPrinter = Printer { line ->
            printer.println(line)
            previousPrinter?.println(line)
        }
        looper.setMessageLogging(chainedPrinter)
        return InstallResult(
            installed = true,
            hadPreviousPrinter = previousPrinter != null,
            failureReason = null,
        )
    }

    private fun readCurrentPrinter(): Printer? {
        return runCatching {
            val field = Looper::class.java.getDeclaredField("mLogging")
            field.isAccessible = true
            field.get(looper) as? Printer
        }.getOrNull()
    }

    data class InstallResult(
        val installed: Boolean,
        val hadPreviousPrinter: Boolean,
        val failureReason: String?,
    )
}
```

- [x] **步骤 4：运行 Looper 测试**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.LooperMessageParserTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollectorTest
```

预期：PASS。

- [x] **步骤 5：提交 Looper 采集器**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper
git commit -m "新增主线程 Looper 消息时间线采集"
```

预期：提交成功。

### 任务 5：新增 Watchdog 和主线程栈快照

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/watchdog/HeartbeatStateTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/watchdog/HeartbeatState.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/watchdog/AnrWatchdog.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/stack/MainThreadStackCollector.kt`

- [x] **步骤 1：编写失败的心跳测试**

创建 `HeartbeatStateTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.watchdog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatStateTest {
    @Test
    fun isTimedOutReturnsFalseWhenHeartbeatHandledBeforeThreshold(): Unit {
        val state = HeartbeatState(
            timeoutMs = 5_000L,
        )

        state.markPosted(seq = 1L, postedUptimeMs = 1_000L)
        state.markHandled(seq = 1L)

        assertFalse(state.isTimedOut(nowUptimeMs = 7_000L))
    }

    @Test
    fun isTimedOutReturnsTrueWhenPostedHeartbeatIsTooOld(): Unit {
        val state = HeartbeatState(
            timeoutMs = 5_000L,
        )

        state.markPosted(seq = 7L, postedUptimeMs = 1_000L)

        assertTrue(state.isTimedOut(nowUptimeMs = 6_500L))
    }

    @Test
    fun hasPendingReturnsTrueUntilHeartbeatIsHandled(): Unit {
        val state = HeartbeatState(
            timeoutMs = 5_000L,
        )

        state.markPosted(seq = 9L, postedUptimeMs = 1_000L)

        assertTrue(state.hasPending())
        state.markHandled(seq = 9L)
        assertFalse(state.hasPending())
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.watchdog.HeartbeatStateTest
```

预期：FAIL，出现未解析引用 `HeartbeatState`.

- [x] **步骤 3：新增 Watchdog 和栈采集代码**

创建 `HeartbeatState.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.watchdog

class HeartbeatState(
    private val timeoutMs: Long,
) {
    @Volatile
    private var pendingSeq: Long? = null

    @Volatile
    private var pendingPostedUptimeMs: Long = 0L

    @Synchronized
    fun markPosted(
        seq: Long,
        postedUptimeMs: Long,
    ): Unit {
        pendingSeq = seq
        pendingPostedUptimeMs = postedUptimeMs
    }

    @Synchronized
    fun markHandled(seq: Long): Unit {
        if (pendingSeq == seq) {
            pendingSeq = null
        }
    }

    fun hasPending(): Boolean {
        return pendingSeq != null
    }

    fun isTimedOut(nowUptimeMs: Long): Boolean {
        val seq: Long = pendingSeq ?: return false
        val elapsedMs: Long = nowUptimeMs - pendingPostedUptimeMs
        return seq > 0L && elapsedMs >= timeoutMs
    }
}
```

创建 `AnrWatchdog.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.watchdog

import android.os.Handler
import android.os.Looper
import com.valiantyan.anrmonitor.core.clock.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AnrWatchdog(
    private val clock: Clock,
    private val intervalMs: Long,
    private val heartbeatState: HeartbeatState,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onSuspectAnr: () -> Unit,
) {
    private val isRunning: AtomicBoolean = AtomicBoolean(false)
    private val sequence: AtomicLong = AtomicLong(0L)
    private var thread: Thread? = null

    fun start(): Unit {
        if (!isRunning.compareAndSet(false, true)) {
            return
        }
        thread = Thread(::loop, "vibe-anr-watchdog").apply { start() }
    }

    fun stop(): Unit {
        isRunning.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop(): Unit {
        while (isRunning.get()) {
            if (heartbeatState.isTimedOut(nowUptimeMs = clock.uptimeMillis())) {
                onSuspectAnr()
            }
            if (!heartbeatState.hasPending()) {
                postHeartbeat()
            }
            sleepInterval()
        }
    }

    private fun postHeartbeat(): Unit {
        val seq: Long = sequence.incrementAndGet()
        heartbeatState.markPosted(
            seq = seq,
            postedUptimeMs = clock.uptimeMillis(),
        )
        mainHandler.post {
            heartbeatState.markHandled(seq = seq)
        }
    }

    private fun sleepInterval(): Unit {
        try {
            Thread.sleep(intervalMs)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
```

创建 `MainThreadStackCollector.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.stack

import android.os.Looper
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot

class MainThreadStackCollector {
    fun capture(): StackTraceSnapshot {
        val mainThread: Thread = Looper.getMainLooper().thread
        val frames: List<String> = mainThread.stackTrace.map { frame -> frame.toString() }
        return StackTraceSnapshot(
            stackId = "main-${frames.hashCode()}",
            threadName = mainThread.name,
            frames = frames,
        )
    }
}
```

- [x] **步骤 4：运行心跳测试并编译 SDK**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.watchdog.HeartbeatStateTest
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

预期：两个命令都 PASS。

- [x] **步骤 5：提交 Watchdog 和栈采集器**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/watchdog anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/stack anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/watchdog
git commit -m "新增 ANR Watchdog 与主线程栈采集"
```

预期：提交成功。

### 任务 6：新增 Pending 队列快照和 Barrier 证据

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueAnalyzerTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueSummary.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueAnalyzer.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/pending/PendingQueueSnapshotter.kt`

- [x] **步骤 1：编写失败的 Pending 分析器测试**

创建 `PendingQueueAnalyzerTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.pending

import com.valiantyan.anrmonitor.domain.model.PendingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQueueAnalyzerTest {
    @Test
    fun analyzeDetectsBarrierAtQueueHead(): Unit {
        val summary: PendingQueueSummary = PendingQueueAnalyzer.analyze(
            messages = listOf(
                pending(index = 0, isBarrierLike = true, targetClass = null, blockedMs = 12_000L, arg1 = 41),
                pending(index = 1, isBarrierLike = false, targetClass = "android.os.Handler", blockedMs = 11_000L, arg1 = 0),
            ),
        )

        assertTrue(summary.hasBarrierHead)
        assertEquals(41, summary.barrierToken)
        assertEquals(11_000L, summary.firstSynchronousBlockedMs)
    }

    private fun pending(
        index: Int,
        isBarrierLike: Boolean,
        targetClass: String?,
        blockedMs: Long,
        arg1: Int,
    ): PendingMessage {
        return PendingMessage(
            index = index,
            whenUptimeMs = 100L,
            delayMs = -blockedMs,
            blockedMs = blockedMs,
            what = null,
            arg1 = arg1,
            arg2 = 0,
            targetClass = targetClass,
            callbackClass = null,
            objClass = null,
            isAsynchronous = false,
            isBarrierLike = isBarrierLike,
            isCriticalComponent = false,
        )
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.pending.PendingQueueAnalyzerTest
```

预期：FAIL，出现未解析引用 `PendingQueueAnalyzer` 和 `PendingQueueSummary`.

- [x] **步骤 3：新增 Pending 分析器和快照器**

创建 `PendingQueueSummary.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.pending

data class PendingQueueSummary(
    val totalCount: Int,
    val hasBarrierHead: Boolean,
    val barrierToken: Int?,
    val firstSynchronousBlockedMs: Long?,
    val repeatedTargetCount: Int,
)
```

创建 `PendingQueueAnalyzer.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.pending

import com.valiantyan.anrmonitor.domain.model.PendingMessage

object PendingQueueAnalyzer {
    fun analyze(messages: List<PendingMessage>): PendingQueueSummary {
        val head: PendingMessage? = messages.firstOrNull()
        val firstSynchronous: PendingMessage? = messages.firstOrNull { message ->
            !message.isBarrierLike && message.isAsynchronous != true
        }
        val repeatedTargetCount: Int = messages
            .mapNotNull { message -> message.targetClass ?: message.callbackClass }
            .groupingBy { key -> key }
            .eachCount()
            .values
            .maxOrNull() ?: 0
        return PendingQueueSummary(
            totalCount = messages.size,
            hasBarrierHead = head?.isBarrierLike == true,
            barrierToken = head?.takeIf { message -> message.isBarrierLike }?.arg1,
            firstSynchronousBlockedMs = firstSynchronous?.blockedMs,
            repeatedTargetCount = repeatedTargetCount,
        )
    }
}
```

创建 `PendingQueueSnapshotter.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.pending

import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot

class PendingQueueSnapshotter(
    private val clock: Clock,
    private val sanitizer: ClassNameSanitizer,
    private val looper: Looper = Looper.getMainLooper(),
) {
    fun capture(maxDepth: Int): PendingQueueSnapshot {
        return runCatching {
            captureUnsafe(maxDepth = maxDepth)
        }.getOrElse { throwable ->
            PendingQueueSnapshot.unavailable(
                maxDepth = maxDepth,
                failureReason = throwable.javaClass.simpleName,
            )
        }
    }

    private fun captureUnsafe(maxDepth: Int): PendingQueueSnapshot {
        val queueField = Looper::class.java.getDeclaredField("mQueue")
        queueField.isAccessible = true
        val queue = queueField.get(looper) as MessageQueue
        val messagesField = MessageQueue::class.java.getDeclaredField("mMessages")
        messagesField.isAccessible = true
        val nextField = Message::class.java.getDeclaredField("next")
        nextField.isAccessible = true
        val nowUptimeMs: Long = clock.uptimeMillis()
        val messages = mutableListOf<PendingMessage>()
        var current: Message? = messagesField.get(queue) as? Message
        var index = 0
        while (current != null && index < maxDepth) {
            messages += current.toPendingMessage(
                index = index,
                nowUptimeMs = nowUptimeMs,
            )
            current = nextField.get(current) as? Message
            index += 1
        }
        return PendingQueueSnapshot(
            available = true,
            truncated = current != null,
            maxDepth = maxDepth,
            messages = messages,
            failureReason = null,
        )
    }

    private fun Message.toPendingMessage(
        index: Int,
        nowUptimeMs: Long,
    ): PendingMessage {
        val targetClass: String? = target?.javaClass?.name
        val callbackClass: String? = callback?.javaClass?.name
        val objClass: String? = obj?.javaClass?.name
        val blockedMs: Long = (nowUptimeMs - `when`).coerceAtLeast(minimumValue = 0L)
        return PendingMessage(
            index = index,
            whenUptimeMs = `when`,
            delayMs = `when` - nowUptimeMs,
            blockedMs = blockedMs,
            what = what,
            arg1 = arg1,
            arg2 = arg2,
            targetClass = sanitizer.sanitizeClassName(className = targetClass).ifBlank { null },
            callbackClass = sanitizer.sanitizeClassName(className = callbackClass).ifBlank { null },
            objClass = sanitizer.sanitizeClassName(className = objClass).ifBlank { null },
            isAsynchronous = runCatching { isAsynchronous }.getOrNull(),
            isBarrierLike = target == null,
            isCriticalComponent = targetClass == "android.app.ActivityThread\$H",
        )
    }
}
```

- [x] **步骤 4：运行 Pending 测试并编译 SDK**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.pending.PendingQueueAnalyzerTest
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

预期：两个命令都 PASS。

- [x] **步骤 5：提交 Pending 队列证据**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/pending anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/pending
git commit -m "新增 Pending 队列快照与 Barrier 证据分析"
```

预期：提交成功。

### 任务 7：新增基础归因规则

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionThresholds.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`

- [x] **步骤 1：编写失败的归因测试**

创建 `AttributionAnalyzerTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.analyzer

import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionAnalyzerTest {
    @Test
    fun analyzeReturnsSpLoadWaitWhenStackContainsAwaitLoadedLocked(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(seq = 1L, wallMs = 6_000L, cpuMs = 20L),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("android.app.SharedPreferencesImpl.awaitLoadedLocked(SharedPreferencesImpl.java:300)"),
            ),
        )

        assertEquals(AnrAttributionCode.SP_LOAD_WAIT, result.primaryCode)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun analyzeReturnsBarrierWhenQueueHeadIsBarrierAndSyncMessageBlocked(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(seq = 1L, wallMs = 6_000L, cpuMs = 10L),
                history = emptyList(),
                pending = listOf(
                    pending(index = 0, isBarrierLike = true, blockedMs = 12_000L, targetClass = null),
                    pending(index = 1, isBarrierLike = false, blockedMs = 11_000L, targetClass = "android.os.Handler"),
                ),
                frames = listOf("android.os.MessageQueue.nativePollOnce(Native Method)"),
            ),
        )

        assertEquals(AnrAttributionCode.SYNC_BARRIER_STUCK, result.primaryCode)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun analyzeReturnsCurrentSlowWhenCurrentWallAndCpuAreHigh(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(seq = 1L, wallMs = 6_000L, cpuMs = 5_000L),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("com.example.Feature.render(Feature.kt:20)"),
            ),
        )

        assertEquals(AnrAttributionCode.CURRENT_MESSAGE_SLOW, result.primaryCode)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun analyzeReturnsHistorySlowWhenPreviousMessageIsSlowAndCurrentIsShort(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(seq = 3L, wallMs = 20L, cpuMs = 10L),
                history = listOf(message(seq = 2L, wallMs = 7_000L, cpuMs = 6_000L)),
                pending = emptyList(),
                frames = emptyList(),
            ),
        )

        assertEquals(AnrAttributionCode.HISTORY_MESSAGE_SLOW, result.primaryCode)
    }

    @Test
    fun analyzeReturnsMessageStormWhenPendingHasRepeatedTarget(): Unit {
        val pending = (0 until 30).map { index ->
            pending(index = index, isBarrierLike = false, blockedMs = 1_000L, targetClass = "com.example.RefreshHandler")
        }

        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(seq = 1L, wallMs = 20L, cpuMs = 15L),
                history = emptyList(),
                pending = pending,
                frames = emptyList(),
            ),
        )

        assertEquals(AnrAttributionCode.MESSAGE_STORM, result.primaryCode)
    }

    private fun snapshot(
        current: MessageRecord?,
        history: List<MessageRecord>,
        pending: List<PendingMessage>,
        frames: List<String>,
    ): AnrSnapshot {
        return AnrSnapshot(
            eventId = "test",
            eventType = AnrEventType.SUSPECT_ANR,
            appId = "demo",
            environment = "test",
            timeUptimeMs = 10_000L,
            currentMessage = current,
            historyMessages = history,
            pendingQueue = PendingQueueSnapshot(
                available = true,
                truncated = false,
                maxDepth = 200,
                messages = pending,
                failureReason = null,
            ),
            mainThreadStack = StackTraceSnapshot(
                stackId = "main",
                threadName = "main",
                frames = frames,
            ),
        )
    }

    private fun message(
        seq: Long,
        wallMs: Long,
        cpuMs: Long,
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = MessageRecordKind.HISTORY,
            messageType = "looper_dispatch",
            what = null,
            targetClass = "android.os.Handler",
            callbackClass = null,
            isCriticalComponent = false,
            startUptimeMs = 0L,
            endUptimeMs = wallMs,
            wallMs = wallMs,
            cpuMs = cpuMs,
        )
    }

    private fun pending(
        index: Int,
        isBarrierLike: Boolean,
        blockedMs: Long,
        targetClass: String?,
    ): PendingMessage {
        return PendingMessage(
            index = index,
            whenUptimeMs = 0L,
            delayMs = -blockedMs,
            blockedMs = blockedMs,
            what = null,
            arg1 = 41,
            arg2 = 0,
            targetClass = targetClass,
            callbackClass = null,
            objClass = null,
            isAsynchronous = false,
            isBarrierLike = isBarrierLike,
            isCriticalComponent = false,
        )
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest
```

预期：FAIL，出现未解析引用 `AttributionAnalyzer`.

- [x] **步骤 3：新增归因代码**

创建 `AttributionThresholds.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.analyzer

data class AttributionThresholds(
    val slowMessageMs: Long = 1_000L,
    val suspectAnrMs: Long = 5_000L,
    val highCpuRatio: Double = 0.5,
    val messageStormCount: Int = 20,
)
```

创建 `AttributionAnalyzer.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.analyzer

import com.valiantyan.anrmonitor.collector.pending.PendingQueueAnalyzer
import com.valiantyan.anrmonitor.collector.pending.PendingQueueSummary
import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.MessageRecord

class AttributionAnalyzer(
    private val thresholds: AttributionThresholds = AttributionThresholds(),
) {
    fun analyze(snapshot: AnrSnapshot): AttributionResult {
        val frames: List<String> = snapshot.mainThreadStack.frames
        val spResult: AttributionResult? = analyzeSharedPreferences(frames = frames)
        if (spResult != null) {
            return spResult
        }
        val pendingSummary: PendingQueueSummary = PendingQueueAnalyzer.analyze(
            messages = snapshot.pendingQueue.messages,
        )
        val barrierResult: AttributionResult? = analyzeBarrier(summary = pendingSummary)
        if (barrierResult != null) {
            return barrierResult
        }
        val currentResult: AttributionResult? = analyzeCurrentMessage(current = snapshot.currentMessage)
        if (currentResult != null) {
            return currentResult
        }
        val historyResult: AttributionResult? = analyzeHistory(history = snapshot.historyMessages)
        if (historyResult != null) {
            return historyResult
        }
        val stormResult: AttributionResult? = analyzeMessageStorm(summary = pendingSummary)
        if (stormResult != null) {
            return stormResult
        }
        return unknownResult(snapshot = snapshot)
    }

    private fun analyzeSharedPreferences(frames: List<String>): AttributionResult? {
        val joinedFrames: String = frames.joinToString(separator = "\n")
        if (joinedFrames.contains(other = "SharedPreferencesImpl.awaitLoadedLocked")) {
            return result(
                code = AnrAttributionCode.SP_LOAD_WAIT,
                confidence = Confidence.HIGH,
                evidence = listOf("main stack contains SharedPreferencesImpl.awaitLoadedLocked"),
                suggestion = "将首次读取前置到后台线程，拆分过大的 shared_prefs 文件。",
            )
        }
        if (joinedFrames.contains(other = "QueuedWork.waitToFinish") || joinedFrames.contains(other = "writtenToDiskLatch.await")) {
            return result(
                code = AnrAttributionCode.SP_APPLY_WAIT,
                confidence = Confidence.HIGH,
                evidence = listOf("main stack contains QueuedWork.waitToFinish or writtenToDiskLatch.await"),
                suggestion = "治理生命周期边界前的高频 apply，强一致数据不要跳过等待。",
            )
        }
        return null
    }

    private fun analyzeBarrier(summary: PendingQueueSummary): AttributionResult? {
        val firstBlockedMs: Long = summary.firstSynchronousBlockedMs ?: return null
        if (!summary.hasBarrierHead || firstBlockedMs < thresholds.suspectAnrMs) {
            return null
        }
        return result(
            code = AnrAttributionCode.SYNC_BARRIER_STUCK,
            confidence = Confidence.HIGH,
            evidence = listOf("pending queue head is Sync Barrier", "first synchronous message blocked ${firstBlockedMs}ms"),
            suggestion = "检查 postSyncBarrier/removeSyncBarrier 配对和 UI 调度清理逻辑。",
        )
    }

    private fun analyzeCurrentMessage(current: MessageRecord?): AttributionResult? {
        if (current == null || current.wallMs < thresholds.suspectAnrMs) {
            return null
        }
        val ratio: Double = current.cpuMs.toDouble() / current.wallMs.coerceAtLeast(minimumValue = 1L).toDouble()
        if (ratio < thresholds.highCpuRatio) {
            return null
        }
        return result(
            code = AnrAttributionCode.CURRENT_MESSAGE_SLOW,
            confidence = Confidence.MEDIUM,
            evidence = listOf("current message wall=${current.wallMs}ms cpu=${current.cpuMs}ms"),
            suggestion = "优化当前消息对应业务逻辑，拆分主线程重计算或同步等待。",
        )
    }

    private fun analyzeHistory(history: List<MessageRecord>): AttributionResult? {
        val slowMessage: MessageRecord = history.firstOrNull { record -> record.wallMs >= thresholds.suspectAnrMs } ?: return null
        return result(
            code = AnrAttributionCode.HISTORY_MESSAGE_SLOW,
            confidence = Confidence.MEDIUM,
            evidence = listOf("history message seq=${slowMessage.seq} wall=${slowMessage.wallMs}ms"),
            suggestion = "回看 ANR 前历史消息，而不是只按当前 Trace 派单。",
        )
    }

    private fun analyzeMessageStorm(summary: PendingQueueSummary): AttributionResult? {
        if (summary.repeatedTargetCount < thresholds.messageStormCount) {
            return null
        }
        return result(
            code = AnrAttributionCode.MESSAGE_STORM,
            confidence = Confidence.MEDIUM,
            evidence = listOf("pending repeated target count=${summary.repeatedTargetCount}"),
            suggestion = "合并重复 Handler 消息，增加去重、防抖或队列清理。",
        )
    }

    private fun unknownResult(snapshot: AnrSnapshot): AttributionResult {
        val missingEvidence = mutableListOf<String>()
        if (!snapshot.pendingQueue.available) {
            missingEvidence += "pending queue unavailable: ${snapshot.pendingQueue.failureReason}"
        }
        if (snapshot.historyMessages.isEmpty()) {
            missingEvidence += "history messages empty"
        }
        return AttributionResult(
            primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
            secondaryCodes = emptyList(),
            confidence = Confidence.UNKNOWN,
            evidenceItems = emptyList(),
            missingEvidence = missingEvidence,
            actionSuggestions = listOf("补齐 Pending、历史消息或主线程栈后重新评估。"),
        )
    }

    private fun result(
        code: AnrAttributionCode,
        confidence: Confidence,
        evidence: List<String>,
        suggestion: String,
    ): AttributionResult {
        return AttributionResult(
            primaryCode = code,
            secondaryCodes = emptyList(),
            confidence = confidence,
            evidenceItems = evidence,
            missingEvidence = emptyList(),
            actionSuggestions = listOf(suggestion),
        )
    }
}
```

- [x] **步骤 4：运行归因测试**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest
```

预期：PASS。

- [x] **步骤 5：提交归因规则**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer
git commit -m "新增 ANR 基础归因规则"
```

预期：提交成功。

### 任务 8：编码报告并写入本地 JSON

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/JsonEscaperTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/local/LocalAnrReportWriterTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/JsonEscaper.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/local/LocalAnrReportWriter.kt`

- [x] **步骤 1：编写失败的 JSON 编码器测试**

创建 `AnrReportJsonEncoderTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.reporter.encoder

import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.SdkDiagnostics
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrReportJsonEncoderTest {
    @Test
    fun encodeIncludesSchemaAndDoesNotExposeMessageObjectContent(): Unit {
        val report = AnrReport(
            schemaVersion = 1,
            snapshot = AnrSnapshot(
                eventId = "event-1",
                eventType = AnrEventType.SUSPECT_ANR,
                appId = "demo",
                environment = "test",
                timeUptimeMs = 123L,
                currentMessage = null,
                historyMessages = emptyList(),
                pendingQueue = PendingQueueSnapshot.unavailable(
                    maxDepth = 200,
                    failureReason = "reflection failed",
                ),
                mainThreadStack = StackTraceSnapshot(
                    stackId = "main",
                    threadName = "main",
                    frames = listOf("com.example.Feature.render(Feature.kt:20)"),
                ),
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
                secondaryCodes = emptyList(),
                confidence = Confidence.UNKNOWN,
                evidenceItems = emptyList(),
                missingEvidence = listOf("pending queue unavailable"),
                actionSuggestions = listOf("capture more evidence"),
            ),
            diagnostics = SdkDiagnostics(
                pendingAvailable = false,
                reportBuildCostMs = 12L,
                collectorFailures = listOf("PendingQueueSnapshotter"),
            ),
        )

        val json: String = AnrReportJsonEncoder().encode(report = report)

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"primary\":\"UNKNOWN_INSUFFICIENT_EVIDENCE\""))
        assertFalse(json.contains("obj="))
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest
```

预期：FAIL，出现未解析引用 `AnrReportJsonEncoder`.

- [x] **步骤 3：新增 JSON 编码器和本地写入器**

创建 `JsonEscaper.kt`：

```kotlin
package com.valiantyan.anrmonitor.reporter.encoder

object JsonEscaper {
    fun escape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
```

创建 `AnrReportJsonEncoder.kt`：

```kotlin
package com.valiantyan.anrmonitor.reporter.encoder

import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.PendingMessage

class AnrReportJsonEncoder {
    fun encode(report: AnrReport): String {
        return buildString {
            append("{")
            append("\"schemaVersion\":${report.schemaVersion},")
            append("\"event\":{")
            append("\"eventId\":\"${escape(report.snapshot.eventId)}\",")
            append("\"eventType\":\"${report.snapshot.eventType.name}\",")
            append("\"appId\":\"${escape(report.snapshot.appId)}\",")
            append("\"environment\":\"${escape(report.snapshot.environment)}\",")
            append("\"timeUptimeMs\":${report.snapshot.timeUptimeMs}")
            append("},")
            append("\"mainThread\":{")
            append("\"current\":${messageOrNull(report.snapshot.currentMessage)},")
            append("\"history\":${messages(report.snapshot.historyMessages)},")
            append("\"stackFrames\":${strings(report.snapshot.mainThreadStack.frames)}")
            append("},")
            append("\"pendingQueue\":{")
            append("\"available\":${report.snapshot.pendingQueue.available},")
            append("\"truncated\":${report.snapshot.pendingQueue.truncated},")
            append("\"failureReason\":${stringOrNull(report.snapshot.pendingQueue.failureReason)},")
            append("\"messages\":${pendingMessages(report.snapshot.pendingQueue.messages)}")
            append("},")
            append("\"attribution\":{")
            append("\"primary\":\"${report.attribution.primaryCode.name}\",")
            append("\"confidence\":\"${report.attribution.confidence.name}\",")
            append("\"evidence\":${strings(report.attribution.evidenceItems)},")
            append("\"missingEvidence\":${strings(report.attribution.missingEvidence)},")
            append("\"suggestions\":${strings(report.attribution.actionSuggestions)}")
            append("},")
            append("\"sdkDiagnostics\":{")
            append("\"pendingAvailable\":${report.diagnostics.pendingAvailable},")
            append("\"reportBuildCostMs\":${report.diagnostics.reportBuildCostMs},")
            append("\"collectorFailures\":${strings(report.diagnostics.collectorFailures)}")
            append("}")
            append("}")
        }
    }

    private fun messageOrNull(record: MessageRecord?): String {
        if (record == null) {
            return "null"
        }
        return "{${messageFields(record = record)}}"
    }

    private fun messages(records: List<MessageRecord>): String {
        return records.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { record -> "{${messageFields(record = record)}}" }
    }

    private fun messageFields(record: MessageRecord): String {
        return listOf(
            "\"seq\":${record.seq}",
            "\"kind\":\"${record.kind.name}\"",
            "\"what\":${record.what ?: "null"}",
            "\"targetClass\":\"${escape(record.targetClass)}\"",
            "\"callbackClass\":${stringOrNull(record.callbackClass)}",
            "\"wallMs\":${record.wallMs}",
            "\"cpuMs\":${record.cpuMs}",
            "\"count\":${record.count}",
        ).joinToString(separator = ",")
    }

    private fun pendingMessages(messages: List<PendingMessage>): String {
        return messages.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { message ->
            "{${pendingFields(message = message)}}"
        }
    }

    private fun pendingFields(message: PendingMessage): String {
        return listOf(
            "\"index\":${message.index}",
            "\"blockedMs\":${message.blockedMs}",
            "\"what\":${message.what ?: "null"}",
            "\"arg1\":${message.arg1}",
            "\"arg2\":${message.arg2}",
            "\"targetClass\":${stringOrNull(message.targetClass)}",
            "\"callbackClass\":${stringOrNull(message.callbackClass)}",
            "\"objClass\":${stringOrNull(message.objClass)}",
            "\"isAsynchronous\":${message.isAsynchronous ?: "null"}",
            "\"isBarrierLike\":${message.isBarrierLike}",
        ).joinToString(separator = ",")
    }

    private fun strings(values: List<String>): String {
        return values.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { value -> "\"${escape(value)}\"" }
    }

    private fun stringOrNull(value: String?): String {
        if (value == null) {
            return "null"
        }
        return "\"${escape(value)}\""
    }

    private fun escape(value: String): String {
        return JsonEscaper.escape(value = value)
    }
}
```

创建 `LocalAnrReportWriter.kt`：

```kotlin
package com.valiantyan.anrmonitor.reporter.local

import android.content.Context
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import java.io.File

class LocalAnrReportWriter(
    context: Context,
    private val encoder: AnrReportJsonEncoder = AnrReportJsonEncoder(),
) {
    private val reportDirectory: File = File(context.filesDir, "anr-monitor-reports")

    fun write(report: AnrReport): File {
        if (!reportDirectory.exists()) {
            reportDirectory.mkdirs()
        }
        val file = File(reportDirectory, "${report.snapshot.eventId}.json")
        file.writeText(
            text = encoder.encode(report = report),
            charset = Charsets.UTF_8,
        )
        return file
    }
}
```

- [x] **步骤 4：运行报告测试**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

预期：两个命令都 PASS。

- [ ] **步骤 5：提交报告编码器**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter
git commit -m "新增 ANR 报告 JSON 编码与本地落盘"
```

预期：提交成功。

### 任务 9：集成运行时和 Demo App 场景

**文件：**
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitor.kt`
- 确认： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollector.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrReportAssembler.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/MainThreadCpuClock.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorRuntimeLifecycleTest.kt`
- 修改： `app/build.gradle.kts`
- 创建： `app/src/main/java/com/valiantyan/vibeanrmonitoring/VibeAnrApplication.kt`
- 修改： `app/src/main/AndroidManifest.xml`
- 修改： `app/src/main/res/layout/activity_main.xml`
- 修改： `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

执行说明：实际实现将报告拼装拆分到 `AnrReportAssembler.kt`，将 CPU 时间源适配拆分到 `MainThreadCpuClock.kt`，并通过 `AnrMonitorRuntimeLifecycleTest.kt` 覆盖 `install/uninstall` 的运行时生命周期。

- [x] **步骤 1：在运行时接线前先运行编译**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：PASS。这个结果用于确认现有 app 基线健康。

- [x] **步骤 2：新增运行时编排**

确认 `MainLooperTimelineCollector.kt` 已暴露任务 4 中展示的历史缓冲快照方法，并保持 `CpuClock` 在类内公开。保留任务 4 中的 `currentMessage()` 和 `historyMessages()` 方法不变。

创建 `AnrMonitorRuntime.kt`：

```kotlin
package com.valiantyan.anrmonitor.internal

import android.content.Context
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller
import com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollector
import com.valiantyan.anrmonitor.collector.pending.PendingQueueSnapshotter
import com.valiantyan.anrmonitor.collector.stack.MainThreadStackCollector
import com.valiantyan.anrmonitor.collector.watchdog.AnrWatchdog
import com.valiantyan.anrmonitor.collector.watchdog.HeartbeatState
import com.valiantyan.anrmonitor.core.clock.AndroidClock
import com.valiantyan.anrmonitor.core.clock.ThreadCpuClock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzer
import com.valiantyan.anrmonitor.domain.analyzer.AttributionThresholds
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.SdkDiagnostics
import com.valiantyan.anrmonitor.reporter.local.LocalAnrReportWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AnrMonitorRuntime(
    context: Context,
    private val config: AnrMonitorConfig,
    private val uploader: AnrReportUploader,
    private val listener: AnrEventListener,
) {
    private val appContext: Context = context.applicationContext
    private val clock = AndroidClock()
    private val sanitizer = ClassNameSanitizer(privacyMode = config.privacyMode)
    private val historyBuffer = MessageRingBuffer(capacity = config.historyBufferSize)
    private val timelineCollector = MainLooperTimelineCollector(
        clock = clock,
        threadCpuClock = object : MainLooperTimelineCollector.CpuClock {
            private val threadCpuClock = ThreadCpuClock()
            override fun currentThreadCpuMs(): Long {
                return threadCpuClock.currentThreadCpuMs()
            }
        },
        sanitizer = sanitizer,
        historyBuffer = historyBuffer,
    )
    private val pendingSnapshotter = PendingQueueSnapshotter(
        clock = clock,
        sanitizer = sanitizer,
    )
    private val stackCollector = MainThreadStackCollector()
    private val analyzer = AttributionAnalyzer(
        thresholds = AttributionThresholds(
            slowMessageMs = config.slowMessageMs,
            suspectAnrMs = config.suspectAnrMs,
        ),
    )
    private val localWriter = LocalAnrReportWriter(context = appContext)
    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var lastSuspectReportUptimeMs: Long = 0L
    private val watchdog = AnrWatchdog(
        clock = clock,
        intervalMs = config.watchdogIntervalMs,
        heartbeatState = HeartbeatState(timeoutMs = config.suspectAnrMs),
        onSuspectAnr = ::captureSuspectAnr,
    )

    fun start(): Unit {
        if (!config.enabled || !isRunning.compareAndSet(false, true)) {
            return
        }
        MainLooperPrinterInstaller().install(printer = timelineCollector)
        watchdog.start()
    }

    fun stop(): Unit {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }
        watchdog.stop()
    }

    private fun captureSuspectAnr(): Unit {
        if (!isRunning.get()) {
            return
        }
        val nowUptimeMs: Long = clock.uptimeMillis()
        if (nowUptimeMs - lastSuspectReportUptimeMs < config.suspectAnrMs) {
            return
        }
        lastSuspectReportUptimeMs = nowUptimeMs
        val buildStartMs: Long = clock.uptimeMillis()
        val snapshot = AnrSnapshot(
            eventId = UUID.randomUUID().toString(),
            eventType = AnrEventType.SUSPECT_ANR,
            appId = config.appId,
            environment = config.environment,
            timeUptimeMs = nowUptimeMs,
            currentMessage = timelineCollector.currentMessage(),
            historyMessages = timelineCollector.historyMessages(),
            pendingQueue = if (config.capturePendingQueue) {
                pendingSnapshotter.capture(maxDepth = config.pendingSnapshotMaxDepth)
            } else {
                com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot.unavailable(
                    maxDepth = config.pendingSnapshotMaxDepth,
                    failureReason = "pending capture disabled",
                )
            },
            mainThreadStack = stackCollector.capture(),
        )
        listener.onSuspectAnr(snapshot = snapshot)
        val report = AnrReport(
            schemaVersion = 1,
            snapshot = snapshot,
            attribution = analyzer.analyze(snapshot = snapshot),
            diagnostics = SdkDiagnostics(
                pendingAvailable = snapshot.pendingQueue.available,
                reportBuildCostMs = clock.uptimeMillis() - buildStartMs,
                collectorFailures = listOfNotNull(snapshot.pendingQueue.failureReason),
            ),
        )
        localWriter.write(report = report)
        if (config.uploadEnabled) {
            val result: UploadResult = uploader.upload(report = report)
            if (result is UploadResult.Failure) {
                listener.onMonitorError(error = IllegalStateException(result.reason))
            }
        }
    }
}
```

修改 `AnrMonitor.kt`：

```kotlin
package com.valiantyan.anrmonitor.api

import android.content.Context
import com.valiantyan.anrmonitor.internal.AnrMonitorRuntime

object AnrMonitor {
    @Volatile
    private var activeSession: AnrMonitorSession? = null

    @Volatile
    private var runtime: AnrMonitorRuntime? = null

    @Synchronized
    fun install(
        context: Context,
        config: AnrMonitorConfig,
        uploader: AnrReportUploader = AnrReportUploader { UploadResult.Skip },
        listener: AnrEventListener = object : AnrEventListener {},
    ): AnrMonitorSession {
        val existingSession: AnrMonitorSession? = activeSession
        if (existingSession != null) {
            return existingSession
        }
        val createdRuntime = AnrMonitorRuntime(
            context = context.applicationContext,
            config = config,
            uploader = uploader,
            listener = listener,
        )
        val session = AnrMonitorSession(
            config = config,
            stopAction = {
                createdRuntime.stop()
            },
        )
        runtime = createdRuntime
        activeSession = session
        createdRuntime.start()
        return session
    }

    @Synchronized
    fun uninstall(): Unit {
        activeSession?.stop()
        activeSession = null
        runtime = null
    }
}
```

- [x] **步骤 3：接入 demo app 依赖和 Application**

修改 `app/build.gradle.kts` 的 dependencies 块：

```kotlin
dependencies {
    implementation(project(":anr-monitor-sdk"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
```

创建 `VibeAnrApplication.kt`：

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.app.Application
import android.util.Log
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

class VibeAnrApplication : Application() {
    override fun onCreate(): Unit {
        super.onCreate()
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
            ),
            listener = object : AnrEventListener {
                override fun onSuspectAnr(snapshot: AnrSnapshot): Unit {
                    Log.w(TAG, "suspect ANR captured: ${snapshot.eventId}")
                }

                override fun onConfirmedAnr(report: AnrReport): Unit {
                    Log.w(TAG, "confirmed ANR report: ${report.snapshot.eventId}")
                }

                override fun onMonitorError(error: Throwable): Unit {
                    Log.e(TAG, "ANR monitor error: ${error.message}", error)
                }
            },
        )
    }

    private companion object {
        const val TAG: String = "VibeAnrApplication"
    }
}
```

修改 `app/src/main/AndroidManifest.xml` 中的 `<application>` 标签：

```xml
<application
    android:name=".VibeAnrApplication"
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    android:icon="@drawable/ic_launcher"
    android:label="@string/app_name"
    android:roundIcon="@drawable/ic_launcher"
    android:supportsRtl="true"
    android:theme="@style/Theme.VibeANRMonitoring">
```

- [x] **步骤 4：新增 demo 按钮和场景动作**

使用以下完整内容写入 `app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <Button
        android:id="@+id/currentSlowButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Current Slow Message" />

    <Button
        android:id="@+id/messageStormButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="Message Storm" />

    <Button
        android:id="@+id/spApplyButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="SharedPreferences Apply Burst" />
</LinearLayout>
```

使用以下完整内容写入 `MainActivity.kt`:

```kotlin
package com.valiantyan.vibeanrmonitoring

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * ANR SDK 示例入口，提供 阶段一验收所需的主线程慢消息、消息风暴和 SP apply 场景。
 */
class MainActivity : AppCompatActivity() {
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.currentSlowButton).setOnClickListener {
            blockMainThread()
        }
        findViewById<Button>(R.id.messageStormButton).setOnClickListener {
            postMessageStorm()
        }
        findViewById<Button>(R.id.spApplyButton).setOnClickListener {
            writeSharedPreferencesBurst()
        }
    }

    private fun blockMainThread(): Unit {
        Thread.sleep(6_000L)
    }

    private fun postMessageStorm(): Unit {
        repeat(times = 2_000) {
            mainHandler.post {
                val ignoredValue: Int = it * it
                ignoredValue.toString()
            }
        }
    }

    private fun writeSharedPreferencesBurst(): Unit {
        val preferences: SharedPreferences = getSharedPreferences(
            "anr_demo_prefs",
            MODE_PRIVATE,
        )
        repeat(times = 500) { index ->
            preferences.edit()
                .putString("key_$index", "value_$index")
                .apply()
        }
        Thread.sleep(4_000L)
    }
}
```

- [x] **步骤 5：运行 app 编译**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

预期：所有命令都 PASS。

- [x] **步骤 6：提交运行时和 demo 集成**

运行：

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/valiantyan/vibeanrmonitoring app/src/main/res/layout/activity_main.xml
git commit -m "接入 ANR SDK 运行时与示例场景"
```

预期：提交成功。

### 任务 10：手动验证和验收记录

**文件：**
- 创建： `docs-anr/100-ANR监控SDK-阶段一验收记录.md`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`
- 修改： `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`

- [x] **步骤 1：安装 debug app**

运行：

```bash
./gradlew :app:installDebug
```

预期：PASS，设备或模拟器中包含 `com.valiantyan.vibeanrmonitoring`.

- [x] **步骤 2：清理之前的 demo 报告**

运行：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring rm -rf files/anr-monitor-reports
```

预期：命令成功退出。如果当前设备构建不支持 `run-as`，在验收记录中说明手动拉取文件需要 debug-capable emulator。

- [x] **步骤 3：触发当前消息慢场景**

运行：

```bash
adb shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

Then tap `Current Slow Message` in the app.

预期：约 3 秒后，Logcat 包含 `suspect ANR captured`，并且 `files/anr-monitor-reports` 下出现 JSON 文件。

- [x] **步骤 4：拉取并检查本地报告**

运行：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
REPORT_FILE=$(adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports | tail -n 1 | tr -d '\r')
adb shell run-as com.valiantyan.vibeanrmonitoring cat "files/anr-monitor-reports/${REPORT_FILE}"
```

预期 JSON 字段：

```json
{
  "schemaVersion": 1,
  "event": {
    "eventType": "SUSPECT_ANR",
    "appId": "vibe-anr-demo"
  },
  "attribution": {
    "primary": "CURRENT_MESSAGE_SLOW"
  }
}
```

- [x] **步骤 5：触发消息风暴场景**

Tap `Message Storm`.

预期：生成一份报告。如果主归因为 `MESSAGE_STORM`，则该场景记为 PASS。如果主归因为 `UNKNOWN_INSUFFICIENT_EVIDENCE`，检查 `pendingQueue.messages`，确认消息是否在 Watchdog 快照前已经被消费；如果是，将 `VibeAnrApplication.kt` 中的 `suspectAnrMs` 降到 `1_500L`，重新运行 `./gradlew :app:installDebug`，再重复本步骤。

- [x] **步骤 6：创建验收记录**

创建 `docs-anr/100-ANR监控SDK-阶段一验收记录.md`，使用以下结构，并记录实际运行得到的精确命令输出：

```markdown
# ANR 监控 SDK 阶段一验收记录

验收日期：2026-06-05

## 构建结果

- `./gradlew :anr-monitor-sdk:testDebugUnitTest`：PASS
- `./gradlew :app:assembleDebug`：PASS
- `./gradlew :app:installDebug`：PASS

## 当前消息慢场景

- 触发入口：`Current Slow Message`
- 期望归因：`CURRENT_MESSAGE_SLOW`
- 实际归因：`CURRENT_MESSAGE_SLOW`
- 关键证据：当前消息 `wallMs` 大于 `suspectAnrMs`，主线程栈来自 demo 按钮点击链路。

## 消息风暴场景

- 触发入口：`Message Storm`
- 期望归因：`MESSAGE_STORM`
- 实际归因：`MESSAGE_STORM`
- 关键证据：Pending 队列或历史消息中同一 Handler 重复次数超过阈值。

## SharedPreferences apply 场景

- 触发入口：`SharedPreferences Apply Burst`
- 期望归因：`SP_APPLY_WAIT` 或当前慢消息中包含 SP 证据。
- 实际归因：`CURRENT_MESSAGE_SLOW`
- 关键证据：阶段一 demo 使用 `apply()` burst 后主动 sleep，不能稳定制造系统生命周期 `QueuedWork.waitToFinish` 栈；SP 专项稳定复现进入 专项 instrumentation 场景。

## 阶段一结论

阶段一通过：SDK 可初始化、可采集主线程消息时间线、可触发疑似 ANR 快照、可输出本地 JSON、可给出基础归因和缺失证据。
```

- [x] **步骤 7：提交验收记录**

运行：

```bash
git add docs-anr/100-ANR监控SDK-阶段一验收记录.md
git commit -m "新增 ANR SDK 阶段一验收记录"
```

预期：提交成功。

执行记录：

- 已在 `Pixel_9a` AVD 上安装 debug app，包名为 `com.valiantyan.vibeanrmonitoring`。
- 已创建 `docs-anr/100-ANR监控SDK-阶段一验收记录.md`，记录真实 Gradle、adb、logcat 和关键 JSON 字段输出。
- 验收中发现当前消息低 CPU 等待型阻塞被误判为 `UNKNOWN_INSUFFICIENT_EVIDENCE`，已补充单测并修复为按 `wallMs >= suspectAnrMs` 识别 `CURRENT_MESSAGE_SLOW`，CPU 比例只影响置信度。
- 验收中发现 `Message Storm` demo 只投递短任务无法触发 watchdog，已补充单测、调整归因顺序为 SP、Barrier、Message Storm、Current、History，并让 demo 在堆积 pending 消息后阻塞当前点击消息。
- 最终验证通过：`./gradlew :anr-monitor-sdk:testDebugUnitTest`、`./gradlew :app:assembleDebug`、`./gradlew :app:installDebug`、`Current Slow Message`、`Message Storm`、`SharedPreferences Apply Burst`。

### 任务 11：新增慢消息堆栈采样

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/stack/SlowMessageStackSamplerTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/stack/SlowMessageStackSampler.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MessageRecord.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`

- [x] **步骤 1：编写失败的慢消息采样测试**

创建 `SlowMessageStackSamplerTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.stack

import org.junit.Assert.assertEquals
import org.junit.Test

class SlowMessageStackSamplerTest {
    @Test
    fun collectSampleAggregatesSameStackHash(): Unit {
        val sampler = SlowMessageStackSampler(
            maxSamplesPerMessage = 3,
            frameProvider = { listOf("com.example.Feature.render(Feature.kt:42)") },
        )

        sampler.startMessage(seq = 1L)
        sampler.collectSample(seq = 1L)
        sampler.collectSample(seq = 1L)

        val samples = sampler.finishMessage(seq = 1L)

        assertEquals(1, samples.size)
        assertEquals(2, samples.first().hitCount)
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSamplerTest
```

预期：FAIL，出现未解析引用 `SlowMessageStackSampler`。

- [x] **步骤 3：新增慢消息采样实现**

创建 `SlowMessageStackSampler.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.stack

data class StackSample(
    val stackHash: String,
    val frames: List<String>,
    val hitCount: Int,
)

class SlowMessageStackSampler(
    private val maxSamplesPerMessage: Int,
    private val frameProvider: () -> List<String>,
) {
    private val samplesBySeq: MutableMap<Long, MutableMap<String, StackSample>> = mutableMapOf()

    @Synchronized
    fun startMessage(seq: Long): Unit {
        samplesBySeq[seq] = mutableMapOf()
    }

    @Synchronized
    fun collectSample(seq: Long): Unit {
        val samples = samplesBySeq[seq] ?: return
        if (samples.values.sumOf { sample -> sample.hitCount } >= maxSamplesPerMessage) {
            return
        }
        val frames: List<String> = frameProvider()
        val hash: String = frames.joinToString(separator = "\n").hashCode().toString()
        val previous: StackSample? = samples[hash]
        samples[hash] = StackSample(
            stackHash = hash,
            frames = frames,
            hitCount = (previous?.hitCount ?: 0) + 1,
        )
    }

    @Synchronized
    fun finishMessage(seq: Long): List<StackSample> {
        return samplesBySeq.remove(seq)?.values?.toList().orEmpty()
    }
}
```

- [x] **步骤 4：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSamplerTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/stack anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/stack
git commit -m "新增慢消息堆栈采样能力"
```

预期：测试 PASS，提交成功。

执行记录：

- 已新增 `SlowMessageStackSamplerTest`，先运行指定测试并确认 RED：`SlowMessageStackSampler` 与 `StackSample` 未解析。
- 已新增 `SlowMessageStackSampler` 与 `StackSample`，支持按消息 `seq` 开启/结束采样、按栈帧 hash 聚合相同栈、按 `maxSamplesPerMessage` 限制单消息采样次数。
- 已运行指定测试：`./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSamplerTest`，结果 PASS。
- 已运行 SDK 全量单测：`./gradlew :anr-monitor-sdk:testDebugUnitTest`，结果 PASS。
- `MessageRecord` 已在前置任务包含 `sampleStackIds`，本任务按计划步骤先完成独立采样器；`AnrSnapshot` 暂未新增字段，后续报告整合任务再统一接入采样结果。

### 任务 12：新增线程 CPU 排名和进程内资源证据

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/threadcpu/ThreadCpuSnapshotterTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/threadcpu/ThreadCpuSnapshotter.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`

- [x] **步骤 1：编写失败的线程 CPU 排名测试**

创建 `ThreadCpuSnapshotterTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.threadcpu

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadCpuSnapshotterTest {
    @Test
    fun rankThreadsSortsByCpuDeltaDescending(): Unit {
        val snapshotter = ThreadCpuSnapshotter(
            statReader = {
                listOf(
                    ThreadCpuStat(tid = 1, threadName = "main", userMs = 10L, systemMs = 5L),
                    ThreadCpuStat(tid = 2, threadName = "io-worker", userMs = 10L, systemMs = 90L),
                )
            },
        )

        val result = snapshotter.captureTopThreads(maxCount = 1)

        assertEquals("io-worker", result.first().threadName)
        assertEquals(100L, result.first().totalCpuMs)
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.threadcpu.ThreadCpuSnapshotterTest
```

预期：FAIL，出现未解析引用 `ThreadCpuSnapshotter`。

- [x] **步骤 3：新增线程 CPU 采集实现**

创建 `ThreadCpuSnapshotter.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.threadcpu

data class ThreadCpuStat(
    val tid: Int,
    val threadName: String,
    val userMs: Long,
    val systemMs: Long,
)

data class ThreadCpuRecord(
    val tid: Int,
    val threadName: String,
    val totalCpuMs: Long,
)

class ThreadCpuSnapshotter(
    private val statReader: () -> List<ThreadCpuStat>,
) {
    fun captureTopThreads(maxCount: Int): List<ThreadCpuRecord> {
        return statReader()
            .map { stat ->
                ThreadCpuRecord(
                    tid = stat.tid,
                    threadName = stat.threadName,
                    totalCpuMs = stat.userMs + stat.systemMs,
                )
            }
            .sortedByDescending { record -> record.totalCpuMs }
            .take(n = maxCount)
    }
}
```

- [x] **步骤 4：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.threadcpu.ThreadCpuSnapshotterTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/threadcpu anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/threadcpu
git commit -m "新增线程 CPU 排名和资源证据"
```

预期：测试 PASS，提交成功。

执行记录：

- 已新增 `ThreadCpuSnapshotterTest`，先运行指定测试并确认 RED：`ThreadCpuSnapshotter`、`ThreadCpuStat`、`ThreadCpuRecord` 和 `AnrSnapshot.threadCpuRecords` 未解析。
- 已新增 `ThreadCpuSnapshotter` 与 `ThreadCpuStat`，支持注入 stat reader 做稳定测试；生产默认读取 `/proc/self/task/<tid>/stat`，按 user + system CPU 毫秒降序输出 TopN，读取失败降级为空列表。
- 已新增领域模型 `ThreadCpuRecord`，并将 `AnrSnapshot.threadCpuRecords` 接入运行时快照；`AnrMonitorConfig.captureThreadCpu` 默认开启，运行时单次报告最多保留 5 条线程 CPU 记录。
- 已将线程 CPU 记录输出到 JSON 的 `threadCpu.topThreads`，并在 `AttributionAnalyzer` 中追加最高 CPU 线程 evidence，不改变既有主因优先级。
- 已运行 RED/GREEN 指定测试：`./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.threadcpu.ThreadCpuSnapshotterTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest`，结果 PASS。
- 已运行 SDK 全量单测：`./gradlew :anr-monitor-sdk:testDebugUnitTest`，结果 PASS。
- 已运行 Demo 编译验证：`./gradlew :app:compileDebugKotlin`，结果 PASS。

### 任务 13：新增 Checktime 和系统环境采集

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/checktime/ChecktimeMonitorTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/environment/EnvironmentSnapshotterTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/checktime/ChecktimeMonitor.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/environment/EnvironmentSnapshotter.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/environment/EnvironmentDefaultReaders.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/ChecktimeSummary.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/SystemEnvironmentSnapshot.kt`
- 修改：`AnrSnapshot`、`AnrReportJsonEncoder`、`AnrMonitorRuntime`、`AnrReportAssembler`、`AnrMonitorConfig`、`AnrWatchdog`

- [x] **步骤 1：编写失败的 Checktime 测试**

创建 `ChecktimeMonitorTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.checktime

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecktimeMonitorTest {
    @Test
    fun summarizeRecordsMaxAndSevereDelay(): Unit {
        val monitor = ChecktimeMonitor(
            expectedIntervalMs = 300L,
            severeDelayMs = 800L,
        )

        monitor.recordDelay(actualIntervalMs = 320L)
        monitor.recordDelay(actualIntervalMs = 1_200L)

        val summary = monitor.summary()

        assertEquals(900L, summary.maxDelayMs)
        assertEquals(1, summary.severeDelayCount)
    }
}
```

- [x] **步骤 2：新增 Checktime 和环境实现**

创建 `ChecktimeMonitor.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.checktime

data class ChecktimeSummary(
    val maxDelayMs: Long,
    val severeDelayCount: Int,
    val recentDelayMs: List<Long>,
)

class ChecktimeMonitor(
    private val expectedIntervalMs: Long,
    private val severeDelayMs: Long,
) {
    private val delays: MutableList<Long> = mutableListOf()

    fun recordDelay(actualIntervalMs: Long): Unit {
        delays += (actualIntervalMs - expectedIntervalMs).coerceAtLeast(minimumValue = 0L)
    }

    fun summary(): ChecktimeSummary {
        return ChecktimeSummary(
            maxDelayMs = delays.maxOrNull() ?: 0L,
            severeDelayCount = delays.count { delay -> delay >= severeDelayMs },
            recentDelayMs = delays.takeLast(n = 20),
        )
    }
}
```

创建 `EnvironmentSnapshotter.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.environment

data class EnvironmentSnapshot(
    val loadAverage1m: Double?,
    val availableStorageBytes: Long?,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
)

class EnvironmentSnapshotter(
    private val buildProvider: () -> Triple<String, String, String>,
    private val loadAverageReader: () -> Double?,
    private val storageReader: () -> Long?,
) {
    fun capture(): EnvironmentSnapshot {
        val build = buildProvider()
        return EnvironmentSnapshot(
            loadAverage1m = loadAverageReader(),
            availableStorageBytes = storageReader(),
            androidVersion = build.first,
            manufacturer = build.second,
            model = build.third,
        )
    }
}
```

- [x] **步骤 3：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.checktime.ChecktimeMonitorTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/checktime anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/environment anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/checktime anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/environment
git commit -m "新增 Checktime 与系统环境采集"
```

预期：测试 PASS，提交成功。

**执行记录（2026-06-06）：**
- RED：新增 `ChecktimeMonitorTest`、`EnvironmentSnapshotterTest`，并扩展 `AnrMonitorConfigTest`、`AnrReportJsonEncoderTest`；目标测试首次失败，错误为 `ChecktimeMonitor`、环境模型、配置开关和快照字段未定义。
- GREEN：新增 `ChecktimeMonitor`，新增系统环境采集器及默认 reader，覆盖 load average、内存、存储、进程 I/O、设备信息、证据可得性和失败原因。
- 接线：`AnrWatchdog` 回调实际循环间隔，`AnrMonitorRuntime` 写入 `checktimeSummary` 和 `environmentSnapshot`，`AnrReportJsonEncoder` 输出 `checktime` 与 `environmentSnapshot`，`AnrReportAssembler` 汇总环境采集失败原因。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.checktime.ChecktimeMonitorTest --tests com.valiantyan.anrmonitor.collector.environment.EnvironmentSnapshotterTest --tests com.valiantyan.anrmonitor.api.AnrMonitorConfigTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest` PASS。
- 验证：`./gradlew :app:compileDebugKotlin` PASS。

### 任务 14：新增系统确认 ANR 和组件阈值模型

**文件：**
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/anrinfo/AnrInfoCollector.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrInfoSnapshot.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrType.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrReportAssembler.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/anrinfo/AnrInfoCollectorTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorConfigTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`

- [x] **步骤 1：新增组件阈值配置**

在 `AnrMonitorConfig.kt` 中增加字段：

```kotlin
val componentTimeoutMs: Map<AnrType, Long> = mapOf(
    AnrType.INPUT to 5_000L,
    AnrType.SERVICE to 10_000L,
    AnrType.BROADCAST_FOREGROUND to 10_000L,
    AnrType.BROADCAST_BACKGROUND to 60_000L,
    AnrType.PROVIDER to 10_000L,
    AnrType.ACTIVITY to 10_000L,
    AnrType.FINALIZER to 10_000L,
)
```

创建 `AnrType.kt`：

```kotlin
package com.valiantyan.anrmonitor.domain.model

enum class AnrType {
    INPUT,
    SERVICE,
    BROADCAST_FOREGROUND,
    BROADCAST_BACKGROUND,
    PROVIDER,
    ACTIVITY,
    FINALIZER,
    UNKNOWN,
}
```

- [x] **步骤 2：新增系统确认 ANR 采集器**

创建 `AnrInfoCollector.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.anrinfo

data class AnrInfoSnapshot(
    val isConfirmedAnr: Boolean,
    val shortMsg: String?,
    val longMsg: String?,
    val condition: Int?,
)

class AnrInfoCollector(
    private val stateReader: () -> AnrInfoSnapshot?,
) {
    fun collect(): AnrInfoSnapshot {
        return stateReader() ?: AnrInfoSnapshot(
            isConfirmedAnr = false,
            shortMsg = null,
            longMsg = null,
            condition = null,
        )
    }
}
```

- [x] **步骤 3：运行编译并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:compileDebugKotlin
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/anrinfo anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrType.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt
git commit -m "新增系统确认 ANR 与组件阈值模型"
```

预期：编译 PASS，提交成功。

**执行记录（2026-06-06）：**
- RED：新增 `AnrInfoCollectorTest`，扩展 `AnrMonitorConfigTest` 与 `AnrReportJsonEncoderTest`；目标测试首次失败，错误为 `AnrType`、`componentTimeoutMs`、`AnrInfoSnapshot`、`AnrInfoCollector`、`AnrSnapshot.anrInfo`、`AnrSnapshot.componentTimeoutMs` 和 `systemAnr` JSON 字段未定义。
- GREEN：新增 `AnrType`、`AnrInfoSnapshot` 和 `AnrInfoCollector`，支持 ActivityManager confirmed ANR 读取、Input/Service/Broadcast/Provider/Activity/Finalizer 类型推断、系统接口异常降级和失败原因记录。
- 接线：`AnrMonitorConfig` 新增组件阈值 map；`AnrMonitorRuntime` 采集 `anrInfo` 并根据系统确认状态设置 `SUSPECT_ANR`/`CONFIRMED_ANR`；`AnrSnapshot` 承载系统确认信息和当前组件阈值；`AnrReportJsonEncoder` 输出 `systemAnr`；`AnrReportAssembler` 汇总 AnrInfo 采集失败原因。
- 评审口径：`longMsg` 中的组件类型只作为系统确认与阈值证据，不直接替代当前/历史/Pending/栈/资源归因，避免把系统 Reason/Trace 当根因。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.api.AnrMonitorConfigTest --tests com.valiantyan.anrmonitor.collector.anrinfo.AnrInfoCollectorTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:compileDebugKotlin` PASS。
- 验证：`./gradlew :app:compileDebugKotlin` PASS。

### 任务 15：新增 SharedPreferences 全量监控和治理能力

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/sharedprefs/SharedPreferencesHealthScannerTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/sharedprefs/MonitoredSharedPreferencesTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/sharedprefs/QueuedWorkBypassControllerTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorConfigTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/SharedPreferencesSnapshot.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/SharedPreferencesHealthScanner.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/MonitoredSharedPreferences.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/MonitoredSharedPreferencesEditor.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/SharedPreferencesOperationRecorder.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/SharedPreferencesOperationReporter.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs/QueuedWorkBypassController.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitor.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrReportAssembler.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`

- [x] **步骤 1：编写失败的 SP 健康度测试**

创建 `SharedPreferencesHealthScannerTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.sharedprefs

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedPreferencesHealthScannerTest {
    @Test
    fun scanSortsLargeFilesFirst(): Unit {
        val scanner = SharedPreferencesHealthScanner(
            fileReader = {
                listOf(
                    SharedPreferencesFileStat(fileName = "small.xml", sizeBytes = 100L, keyCount = 2),
                    SharedPreferencesFileStat(fileName = "large.xml", sizeBytes = 2_000L, keyCount = 80),
                )
            },
        )

        val result = scanner.scanTopFiles(maxCount = 1)

        assertEquals("large.xml", result.first().fileName)
    }
}
```

- [x] **步骤 2：新增 SP 健康度和治理实现**

创建 `SharedPreferencesHealthScanner.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.sharedprefs

data class SharedPreferencesFileStat(
    val fileName: String,
    val sizeBytes: Long,
    val keyCount: Int,
)

class SharedPreferencesHealthScanner(
    private val fileReader: () -> List<SharedPreferencesFileStat>,
) {
    fun scanTopFiles(maxCount: Int): List<SharedPreferencesFileStat> {
        return fileReader()
            .sortedWith(compareByDescending<SharedPreferencesFileStat> { stat -> stat.sizeBytes }.thenByDescending { stat -> stat.keyCount })
            .take(n = maxCount)
    }
}
```

创建 `QueuedWorkBypassController.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.sharedprefs

data class QueuedWorkBypassPolicy(
    val enabled: Boolean,
    val allowedFiles: Set<String>,
    val blockedFiles: Set<String>,
)

class QueuedWorkBypassController(
    private val policyProvider: () -> QueuedWorkBypassPolicy,
) {
    fun canBypass(fileName: String): Boolean {
        val policy: QueuedWorkBypassPolicy = policyProvider()
        if (!policy.enabled) {
            return false
        }
        if (fileName in policy.blockedFiles) {
            return false
        }
        return fileName in policy.allowedFiles
    }
}
```

- [x] **步骤 3：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.sharedprefs.SharedPreferencesHealthScannerTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/sharedprefs anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/sharedprefs
git commit -m "新增 SharedPreferences 全量监控与治理"
```

预期：测试 PASS，提交成功。

执行记录：

- RED：先新增 `SharedPreferencesHealthScannerTest`、`MonitoredSharedPreferencesTest`、`QueuedWorkBypassControllerTest`，并扩展 `AnrMonitorConfigTest`、`AnrReportJsonEncoderTest`；定向测试按预期因缺少 `SharedPreferencesSnapshot`、`MonitoredSharedPreferences`、`QueuedWorkBypassController`、配置字段和 JSON 字段失败。
- GREEN：新增 SP 领域模型，覆盖文件名、大小、key 数、首次加载耗时、apply/commit 次数、最近写入耗时、调用栈、线程、pending finisher 和 QueuedWork 治理状态。
- 接线：`AnrMonitor` 新增 `openSharedPreferences`/`monitorSharedPreferences` 包装入口；`AnrMonitorRuntime` 按 `captureSpHealth` 采集 SP 快照；`AnrSnapshot`、`AnrReportJsonEncoder` 和 `SdkDiagnostics` 输出 SP 专项证据与失败原因。
- 治理边界：`QueuedWorkBypassController` 只做决策，默认关闭；白名单、黑名单、ROM 厂商边界、SDK 版本边界和回滚开关全部参与判断，避免默认改变系统等待语义。
- 评审口径：主因仍由主线程栈中的 `SharedPreferencesImpl.awaitLoadedLocked`、`QueuedWork.waitToFinish` 或 `writtenToDiskLatch.await` 决定；SP 文件健康和 wrapper 操作记录只增强证据，不替代归因。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.sharedprefs.SharedPreferencesHealthScannerTest --tests com.valiantyan.anrmonitor.collector.sharedprefs.MonitoredSharedPreferencesTest --tests com.valiantyan.anrmonitor.collector.sharedprefs.QueuedWorkBypassControllerTest --tests com.valiantyan.anrmonitor.api.AnrMonitorConfigTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:compileDebugKotlin` PASS。
- 验证：`./gradlew :app:compileDebugKotlin` PASS。

### 任务 16：新增 Barrier token 和 nativePollOnce 增强证据

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/barrier/BarrierTokenTrackerTest.kt`
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/nativepoll/NativePollOnceMonitorTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/api/AnrMonitorConfigTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`
- 修改： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/BarrierEvidenceSnapshot.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/barrier/BarrierTokenTracker.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/barrier/BarrierEvidenceCollector.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/nativepoll/NativePollOnceMonitor.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/api/AnrMonitorConfig.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrReport.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrReportAssembler.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`

- [x] **步骤 1：编写失败的 Barrier token 测试**

创建 `BarrierTokenTrackerTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.barrier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierTokenTrackerTest {
    @Test
    fun trackReturnsStuckTokenWhenNotRemoved(): Unit {
        val tracker = BarrierTokenTracker()

        tracker.onPostBarrier(token = 7, uptimeMs = 1_000L, stack = listOf("postSyncBarrier"))

        val stuck = tracker.findStuckTokens(nowUptimeMs = 7_000L, thresholdMs = 5_000L)

        assertEquals(7, stuck.first().token)
        assertTrue(stuck.first().aliveMs >= 6_000L)
    }
}
```

- [x] **步骤 2：新增 Barrier token 和 nativePollOnce 实现**

创建 `BarrierTokenTracker.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.barrier

data class BarrierTokenRecord(
    val token: Int,
    val postUptimeMs: Long,
    val aliveMs: Long,
    val postStack: List<String>,
)

class BarrierTokenTracker {
    private val activeTokens: MutableMap<Int, BarrierTokenRecord> = mutableMapOf()

    fun onPostBarrier(
        token: Int,
        uptimeMs: Long,
        stack: List<String>,
    ): Unit {
        activeTokens[token] = BarrierTokenRecord(
            token = token,
            postUptimeMs = uptimeMs,
            aliveMs = 0L,
            postStack = stack,
        )
    }

    fun onRemoveBarrier(token: Int): Unit {
        activeTokens.remove(token)
    }

    fun findStuckTokens(
        nowUptimeMs: Long,
        thresholdMs: Long,
    ): List<BarrierTokenRecord> {
        return activeTokens.values
            .map { record -> record.copy(aliveMs = nowUptimeMs - record.postUptimeMs) }
            .filter { record -> record.aliveMs >= thresholdMs }
    }
}
```

创建 `NativePollOnceMonitor.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.nativepoll

data class NativePollOnceRecord(
    val timeoutMillis: Int,
    val uptimeMs: Long,
)

class NativePollOnceMonitor {
    private val records: MutableList<NativePollOnceRecord> = mutableListOf()

    fun record(
        timeoutMillis: Int,
        uptimeMs: Long,
    ): Unit {
        records += NativePollOnceRecord(
            timeoutMillis = timeoutMillis,
            uptimeMs = uptimeMs,
        )
    }

    fun recentRecords(): List<NativePollOnceRecord> {
        return records.takeLast(n = 20)
    }
}
```

- [x] **步骤 3：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.barrier.BarrierTokenTrackerTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/barrier anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/nativepoll anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/barrier
git commit -m "新增 Barrier token 与 nativePollOnce 增强证据"
```

预期：测试 PASS，提交成功。

执行记录：

- RED：先新增 `BarrierTokenTrackerTest`、`NativePollOnceMonitorTest`，并扩展 `AnrMonitorConfigTest`、`AttributionAnalyzerTest`、`AnrReportJsonEncoderTest`；定向测试按预期因缺少 `captureBarrierEvidence`、`BarrierTokenTracker`、`NativePollOnceMonitor`、`BarrierEvidenceSnapshot`、`AnrSnapshot.barrierEvidenceSnapshot` 和 JSON 字段失败。
- GREEN：新增 `BarrierEvidenceSnapshot`、`BarrierTokenRecord`、`NativePollOnceRecord`，覆盖 Barrier token 插入/移除配对、卡住 token 存活时间、插入栈、`nativePollOnce(timeoutMillis)` 进入/退出时间、持续时间、`timeoutMillis=-1` 无限等待统计和 in-flight 状态。
- 接线：新增 `BarrierEvidenceCollector` 将 token、`nativePollOnce` 与 Pending 队头 Barrier 对齐；`AnrMonitorConfig` 增加 `captureBarrierEvidence`、`barrierTokenStuckThresholdMs`、`barrierEvidenceMaxRecords`，默认关闭；`AnrMonitorRuntime` 只消费全局 tracker/monitor 记录，不主动改变 Looper 调度；`AnrSnapshot`、`AnrReportAssembler`、`AttributionAnalyzer` 和 `AnrReportJsonEncoder` 输出增强证据与降级原因。
- 评审口径：`SYNC_BARRIER_STUCK` 主因仍由 Pending 队头 Barrier 和同步消息阻塞决定；Barrier token 与 `nativePollOnce(-1)` 只作为增强证据，不替代 Pending 队列事实，也不能默认启用高风险 hook。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.barrier.BarrierTokenTrackerTest --tests com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitorTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest --tests com.valiantyan.anrmonitor.api.AnrMonitorConfigTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:testDebugUnitTest` PASS。
- 验证：`./gradlew :anr-monitor-sdk:compileDebugKotlin` PASS。
- 验证：`./gradlew :app:compileDebugKotlin` PASS。

### 任务 17：新增 Binder 和跨进程阻塞疑似识别

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifierTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder/BinderBlockClassifier.kt`
- 修改： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`

- [ ] **步骤 1：编写失败的 Binder 疑似识别测试**

创建 `BinderBlockClassifierTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.binder

import org.junit.Assert.assertTrue
import org.junit.Test

class BinderBlockClassifierTest {
    @Test
    fun classifyReturnsSuspectedWhenMainStackContainsBinderProxy(): Unit {
        val classifier = BinderBlockClassifier()

        val result = classifier.isBinderBlockSuspected(
            mainFrames = listOf("android.os.BinderProxy.transactNative(Native Method)"),
            binderThreadFrames = listOf("com.example.Service.waitMainThread(Service.kt:10)"),
        )

        assertTrue(result)
    }
}
```

- [ ] **步骤 2：新增 Binder 分类器**

创建 `BinderBlockClassifier.kt`：

```kotlin
package com.valiantyan.anrmonitor.collector.binder

class BinderBlockClassifier {
    fun isBinderBlockSuspected(
        mainFrames: List<String>,
        binderThreadFrames: List<String>,
    ): Boolean {
        val mainInBinder: Boolean = mainFrames.any { frame -> frame.contains(other = "BinderProxy") || frame.contains(other = "transact") }
        val binderWaitsMain: Boolean = binderThreadFrames.any { frame -> frame.contains(other = "main", ignoreCase = true) || frame.contains(other = "wait") }
        return mainInBinder && binderWaitsMain
    }
}
```

- [ ] **步骤 3：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.binder.BinderBlockClassifierTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/binder anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/binder anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt
git commit -m "新增 Binder 与跨进程阻塞疑似识别"
```

预期：测试 PASS，提交成功。

### 任务 18：新增报告治理、压缩重试、隐私和 SDK 自监控

**文件：**
- 创建： `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetentionPolicyTest.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetentionPolicy.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetryQueue.kt`
- 创建： `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics/SdkSelfMonitor.kt`

- [ ] **步骤 1：编写失败的报告保留策略测试**

创建 `ReportRetentionPolicyTest.kt`：

```kotlin
package com.valiantyan.anrmonitor.reporter.retry

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportRetentionPolicyTest {
    @Test
    fun selectExpiredReportsKeepsNewestWithinLimit(): Unit {
        val policy = ReportRetentionPolicy(
            maxFileCount = 2,
        )

        val expired = policy.selectExpiredReports(
            reports = listOf(
                LocalReportMeta(fileName = "1.json", createUptimeMs = 1L),
                LocalReportMeta(fileName = "2.json", createUptimeMs = 2L),
                LocalReportMeta(fileName = "3.json", createUptimeMs = 3L),
            ),
        )

        assertEquals(listOf("1.json"), expired.map { report -> report.fileName })
    }
}
```

- [ ] **步骤 2：新增报告治理实现**

创建 `ReportRetentionPolicy.kt`：

```kotlin
package com.valiantyan.anrmonitor.reporter.retry

data class LocalReportMeta(
    val fileName: String,
    val createUptimeMs: Long,
)

class ReportRetentionPolicy(
    private val maxFileCount: Int,
) {
    fun selectExpiredReports(reports: List<LocalReportMeta>): List<LocalReportMeta> {
        return reports
            .sortedByDescending { report -> report.createUptimeMs }
            .drop(n = maxFileCount)
    }
}
```

创建 `SdkSelfMonitor.kt`：

```kotlin
package com.valiantyan.anrmonitor.internal.diagnostics

data class SdkMetric(
    val name: String,
    val count: Long,
)

class SdkSelfMonitor {
    private val counters: MutableMap<String, Long> = mutableMapOf()

    fun increment(name: String): Unit {
        counters[name] = (counters[name] ?: 0L) + 1L
    }

    fun snapshot(): List<SdkMetric> {
        return counters.map { entry ->
            SdkMetric(
                name = entry.key,
                count = entry.value,
            )
        }
    }
}
```

- [ ] **步骤 3：运行测试并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.reporter.retry.ReportRetentionPolicyTest
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/retry anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/diagnostics anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/retry
git commit -m "新增报告治理与 SDK 自监控"
```

预期：测试 PASS，提交成功。

### 任务 19：新增全量场景测试矩阵和性能验收

**文件：**
- 创建： `docs-anr/101-ANR监控SDK全量验收记录.md`
- 修改： `app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt`
- 修改： `app/src/main/res/layout/activity_main.xml`

- [ ] **步骤 1：补充 demo 场景入口**

在 `activity_main.xml` 中追加按钮：

```xml
<Button
    android:id="@+id/currentBusyButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="16dp"
    android:text="Current Busy Loop" />

<Button
    android:id="@+id/binderLikeButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="16dp"
    android:text="Binder Like Wait" />
```

在 `MainActivity.kt` 中追加方法：

```kotlin
private fun runBusyLoop(): Unit {
    val endAt: Long = System.currentTimeMillis() + 6_000L
    while (System.currentTimeMillis() < endAt) {
        Math.sqrt(42.0)
    }
}

private fun waitBinderLikeLock(): Unit {
    val lock = Object()
    synchronized(lock) {
        Thread.sleep(6_000L)
    }
}
```

- [ ] **步骤 2：创建全量验收记录**

创建 `docs-anr/101-ANR监控SDK全量验收记录.md`：

```markdown
# ANR 监控 SDK 全量验收记录

验收日期：2026-06-05

## 覆盖能力

- 当前消息慢：已验证
- 历史消息慢：已验证
- 消息风暴：已验证
- Pending 队列快照：已验证，失败时记录 `available=false`
- Barrier 疑似：已验证 Pending 队头 `target == null` 证据
- 慢消息堆栈采样：已验证 stack hash 和 hit count
- 线程 CPU：已验证 Top N 排名
- Checktime：已验证 max delay 和 severe count
- SP_LOAD_WAIT：已验证栈命中归因
- SP_APPLY_WAIT：已验证栈命中归因
- Binder 阻塞疑似：已验证 BinderProxy 栈识别
- 报告治理：已验证保留策略和 SDK 自监控指标

## 结论

全量 SDK 能力覆盖 01 到 05 文档的核心输入，阶段一到阶段四仅表示交付顺序，不表示范围裁剪。
```

- [ ] **步骤 3：运行全量构建并提交**

运行：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest
./gradlew :app:assembleDebug
git add docs-anr/101-ANR监控SDK全量验收记录.md app/src/main/java/com/valiantyan/vibeanrmonitoring/MainActivity.kt app/src/main/res/layout/activity_main.xml
git commit -m "新增 ANR SDK 全量场景验收"
```

预期：测试和构建 PASS，提交成功。

### 任务 20：新增服务端消费协议和全量设计追溯

**文件：**
- 创建： `docs-anr/102-ANR监控SDK服务端消费协议.md`
- 修改： `docs/superpowers/plans/2026-06-05-anr-monitor-sdk-full.md`

- [ ] **步骤 1：创建服务端消费协议**

创建 `docs-anr/102-ANR监控SDK服务端消费协议.md`：

```markdown
# ANR 监控 SDK 服务端消费协议

## 聚类维度

- 归因码：`CURRENT_MESSAGE_SLOW`、`HISTORY_MESSAGE_SLOW`、`MESSAGE_STORM`、`PROCESS_IO_PRESSURE`、`EXTERNAL_SYSTEM_LOAD`、`BINDER_BLOCK_SUSPECTED`、`SYNC_BARRIER_STUCK`、`SP_LOAD_WAIT`、`SP_APPLY_WAIT`
- ANR 类型：Input、Service、Broadcast、Provider、Activity、Finalizer
- 当前栈 hash、历史慢消息栈 hash、Pending target/callback hash
- Barrier token、SP 文件名、设备/ROM/Android 版本、App 版本、页面、进程名

## 报告页面

1. 结论卡片：归因码、置信度、业务可治理性。
2. 证据链：系统 Reason、当前 Trace、当前消息、历史消息、Pending、线程 CPU、Checktime、SP/Barrier/Binder。
3. 时间线：过去、当前、Pending 三段联动。
4. 专项卡片：SP、Barrier、Binder、环境负载。
5. 缺失证据：collector 失败、权限限制、ROM 限制。
6. 治理建议：owner hint、版本分布、设备分布、回滚建议。
```

- [ ] **步骤 2：最终扫描计划覆盖**

运行：

```bash
rg -n "单阶段 P[0]|P[1]/P[2].*不[纳]入|能力可[以]省略" docs/superpowers/plans/2026-06-05-anr-monitor-sdk-full.md
```

预期：无结果。若有结果，必须改为“阶段一到阶段四分阶段覆盖，全量能力均在本计划内”。

- [ ] **步骤 3：提交服务端协议和计划修订**

运行：

```bash
git add docs-anr/102-ANR监控SDK服务端消费协议.md docs/superpowers/plans/2026-06-05-anr-monitor-sdk-full.md
git commit -m "完善 ANR SDK 全量实施计划和服务端协议"
```

预期：提交成功。

## 最终验证命令

所有任务完成后运行以下命令：

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest
./gradlew :anr-monitor-sdk:compileDebugKotlin
./gradlew :app:assembleDebug
git status --short
```

预期：

- `:anr-monitor-sdk:testDebugUnitTest` PASS。
- `:anr-monitor-sdk:compileDebugKotlin` PASS。
- `:app:assembleDebug` PASS。
- `git status --short` 不显示已跟踪文件变更。除非用户明确要求提交 IDE 文件，否则未跟踪的 `.idea/` 可以保留在实现范围之外。

## 对应回 `99-ANR监控SDK设计开发文档.md`

- 第 3 节模块形态：任务 1 创建 `anr-monitor-sdk`。
- 第 4 节对外 API：任务 2 创建 `AnrMonitor.install`、config、listener、uploader。
- 第 5.1 和 5.2 节主线程时间线和当前消息：任务 3 和任务 4。
- 第 5.3 节 Pending 队列快照：任务 6。
- 第 5.5 节 Watchdog：任务 5 和任务 9。
- 第 6.1 节 Sync Barrier 首阶段识别：任务 6 和任务 7。
- 第 6.2 节 SP 首阶段栈识别：任务 7。
- 第 7 节本地报告模型和 JSON：任务 2 和任务 8。
- 第 8 节基础归因：任务 7。
- 第 9 节性能和稳定性预算：任务 3 有界缓冲区、任务 6 深度上限、任务 8 本地报告大小意识。
- 第 10 节隐私：任务 3 脱敏器、任务 6 不记录 `obj.toString()`、任务 8 JSON 只输出类名和字段。
- 第 12 节 阶段一/阶段二里程碑：任务 1 到任务 10。
- 第 13 节测试：单元测试见任务 2 到任务 18，手动 demo 和性能验收见任务 10、任务 19。
- 第 14 节服务端消费建议：任务 20 创建服务端消费协议。
- 第 21 节 阶段一/阶段二 拆分：任务 1 到任务 10 属于首个可运行里程碑，任务 11 到任务 20 补齐 01 到 05 的全量能力。

## 自审

规格覆盖：

- 已覆盖 阶段一 骨架版本：模块、API、配置、Looper 采集器、环形缓冲区、Watchdog、栈快照和本地报告路径。
- 已覆盖 阶段二 证据版本：Pending 快照、Barrier 疑似摘要、SP 栈归因、当前/历史/消息风暴归因、demo 场景和验收记录。
- 已覆盖完整诊断版本：慢消息堆栈采样、线程 CPU 排名、Checktime、系统环境、系统确认 ANR、SP 文件健康度、QueuedWork 绕过灰度、Barrier token、nativePollOnce、Binder 阻塞疑似、报告治理、SDK 自监控、全量验收和服务端消费协议。

占位扫描：

- 没有未解决标记。
- 没有未填写的实现位置。
- 命令、预期结果、文件路径和代码片段均已明确。

类型一致性：

- `AnrMonitorConfig.suspectAnrMs` 输入到 `HeartbeatState`、`AnrWatchdog` 和 `AttributionThresholds`。
- `MessageRecord`、`PendingMessage`、`PendingQueueSnapshot`、`AnrSnapshot` 和 `AnrReport` 命名在 collector、analyzer、encoder 和测试中保持一致。
- 归因码命名与设计文档一致：`CURRENT_MESSAGE_SLOW`、`HISTORY_MESSAGE_SLOW`、`MESSAGE_STORM`、`SYNC_BARRIER_STUCK`、`SP_LOAD_WAIT`、`SP_APPLY_WAIT` 和 `UNKNOWN_INSUFFICIENT_EVIDENCE`。
- 分阶段命名保持一致：阶段一到阶段四是交付顺序，不是范围排除；任务 20 的扫描命令用于防止重新出现“仅限首阶段”的矛盾表述。

## 举一反三提问

### 1. 全量范围和里程碑

- 阶段一到阶段四是否被表达为交付顺序，而不是范围裁剪？
- 任务 11 到任务 20 是否补齐 01 到 05 文档中的慢栈采样、线程 CPU、Checktime、SP 治理、Barrier 增强、Binder 疑似、报告治理和服务端消费？
- 全量 SDK 的成功标准是否仍然是“证据完整、成本可控、不误导”，而不是承诺端侧确认所有复杂根因？
- 如果任务 6 Pending 反射在部分 ROM 失败，全量报告是否仍然能结合历史消息、当前栈、线程 CPU、Checktime 和缺失证据继续解释问题？
- 如果手动 demo 场景不稳定，任务 19 是否补充了全量验收矩阵，而不是只依赖 阶段一 demo？

### 2. 模块和依赖边界

- `anr-monitor-sdk` 是否是独立 Android Library，`app` 是否只作为示例接入，不向 SDK 暴露业务实现？
- `domain/model` 和 `domain/analyzer` 是否保持纯 Kotlin，方便 JVM 单测覆盖归因逻辑？
- Android 运行时依赖是否集中在 `collector`、`reporter/local`、`internal`，没有泄漏到 domain 层？
- 跨层依赖是否通过稳定接口或清晰构造参数传递，避免后续把 SDK 做成难以替换的单体？
- 计划里的每个文件职责是否单一，是否存在一个类同时采集、归因、落盘和上报的情况？

### 3. 对外 API 和宿主接入

- `AnrMonitor.install` 是否幂等？重复调用是否返回同一个 session，而不是重复安装 Printer 和 Watchdog？
- `AnrMonitorSession.stop()` 是否能停止 Watchdog，避免 Activity 或测试结束后后台线程残留？
- `AnrMonitorConfig` 默认值是否对线上安全：`uploadEnabled=false`、`enableQueuedWorkBypass=false`、Pending 深度受限？
- `sampleRate` 是否做了归一化，避免宿主传入负数或大于 1 的值影响采样判断？
- `AnrEventListener` 是否能满足 debug 面板和自动化测试，不强制宿主实现全部回调？

### 4. Looper 时间线

- `Looper.setMessageLogging` 是否会覆盖宿主已有 Printer？计划是否读取并转发旧 Printer？
- 如果其他库在 SDK 之后再次设置 Printer，阶段一是否能暴露诊断指标说明代理链被破坏？
- Looper 日志解析是否只依赖稳定的 start/end 前缀，并对无法解析的 `what` 做空值处理？
- 当前消息和历史消息是否使用同一时间源，保证 Wall/Cpu 可以和 Pending 的 `blockedMs` 对齐？
- 长消息结束时是否能从 CURRENT 正确转入 HISTORY，避免同一消息既在当前又在历史重复出现？

### 5. Watchdog 和快照触发

- Watchdog 是否会在主线程真实卡住时持续刷新心跳时间，导致永远达不到超时阈值？
- Watchdog 是否有重复快照抑制，避免一次 20 秒卡顿生成大量 JSON 报告？
- `suspectAnrMs` 默认 5 秒、demo 3 秒是否和 Input ANR 阈值及手动验证目标一致？
- 快照是否在后台线程组装，避免在主线程卡住时继续制造主线程负担？
- 主线程恢复后是否需要单独记录 recovery 事件？阶段一未接入时，报告是否仍然可解释？

### 6. Pending 队列和 Barrier

- Pending 反射是否只在疑似 ANR 时触发，避免高频遍历 Message 链？
- `PendingQueueSnapshot` 是否在失败时显式记录 `available=false` 和 `failureReason`？
- Barrier 归因是否要求队头 `target == null` 加同步消息等待超阈值，而不是只凭 `NativePollOnce` 下结论？
- `objClass` 是否只上传类型，不上传 `obj.toString()` 或业务参数？
- Pending 深度超过 `maxDepth` 时是否标记 `truncated=true`，避免服务端误以为队列只有这些消息？

### 7. SharedPreferences 专项

- 阶段一可以先做栈命中识别，但任务 15 是否继续补齐 SP 文件健康度、包装 API 和 `QueuedWork.waitToFinish` 绕过灰度治理？
- `SP_LOAD_WAIT` 和 `SP_APPLY_WAIT` 是否是两个独立归因码，避免把首次加载和 apply 写盘等待混在一起？
- Demo 中的 `SharedPreferences Apply Burst` 是否明确是近似场景，稳定专项复现进入 专项 instrumentation？
- `QueuedWork` 绕过是否放在任务 15 的灰度控制器中，默认关闭，并要求白名单、黑名单、回滚和一致性风险边界？
- 任务 15 是否需要补 SP 文件大小、key 数量、首次加载耗时和写入调用栈？

### 8. 归因和证据链

- 归因规则是否按机制强证据优先：SP、Barrier，再看当前慢、历史慢、消息风暴？
- `CURRENT_MESSAGE_SLOW` 是否要求 Wall 和 Cpu 同时高，避免把等待型问题误判为业务计算慢？
- `HISTORY_MESSAGE_SLOW` 是否能表达“当前 Trace 不是根因，需要回看历史消息”？
- `MESSAGE_STORM` 是否基于重复 target/callback 数量，而不是单条消息耗时？
- `UNKNOWN_INSUFFICIENT_EVIDENCE` 是否输出缺失证据，避免报告看起来像失败而不是降级？

### 9. 报告和隐私

- JSON 是否包含 `schemaVersion`，为后续服务端协议演进留空间？
- 报告是否包含 event、mainThread、pendingQueue、attribution、sdkDiagnostics 这些阶段一关键字段？
- 类名在 Strict 模式是否 hash，Safe 模式是否仍避免对象内容和参数内容？
- 本地落盘目录是否在 app 私有目录，避免其他应用读取？
- 本地 JSON 是否可能在大量疑似事件下无限增长？阶段一未接入清理时是否在风险清单中保留任务 18 补项？

### 10. 测试和验收

- 每个纯 Kotlin 组件是否都有先失败、再实现、再通过的单元测试步骤？
- Android-only 组件是否至少通过 `compileDebugKotlin` 和 demo 手动验证兜底？
- 验收记录是否要求保存真实命令结果，而不是只写理论 PASS？
- 当前慢、消息风暴、Pending 缺失、SP 近似场景是否分别有可观察输出？
- `git status --short` 是否明确允许未跟踪 `.idea/` 留在实现范围之外？

### 11. 后续扩展

- 任务 11 到任务 18 引入线程 CPU、Checktime、SP 文件健康度、Barrier token、`nativePollOnce` 和 Binder 疑似时，是否能复用当前 `AnrSnapshot` 和 `AnrReport` 模型？
- 高风险增强能力是否作为增强证据和灰度治理实现，不改变保守归因口径？
- 服务端聚类需要的字段是否已经有雏形：归因码、栈、Pending target、Barrier token、SP 归因？
- 如果未来要发布 AAR，是否还需要补 consumer keep rules、README 接入文档和版本号策略？
- 如果宿主有远程配置系统，`AnrMonitorConfig` 是否容易扩展为动态更新？

## 三轮审核

### 第一轮：规格一致性审核

审核结论：通过。计划已经修正为全量覆盖计划，阶段一到阶段四只表示交付顺序，任务 1 到任务 20 覆盖 01 到 05 文档的全部核心能力。

已确认覆盖的规格点：

- 独立 SDK 模块：任务 1 新增 `anr-monitor-sdk`，`app` 只负责 demo 接入。
- 对外 API：任务 2 定义 `AnrMonitor.install`、`AnrMonitorConfig`、`AnrEventListener`、`AnrReportUploader` 和 session。
- 主线程消息时间线：任务 3 和 任务 4 覆盖环形缓冲、当前消息、历史消息、Wall/Cpu。
- Watchdog 和主线程栈：任务 5 覆盖疑似 ANR 心跳检测和主线程 Java 栈。
- Pending 队列：任务 6 覆盖反射快照、失败降级、深度截断和 Barrier 队头证据。
- 基础归因：任务 7 覆盖当前慢、历史慢、消息风暴、Barrier、SP load/apply 和 unknown。
- 本地报告：任务 8 覆盖 JSON schema 和 app 私有目录落盘。
- 示例验收：任务 9 和 任务 10 覆盖 demo 接入、手动场景和验收记录。
- 慢栈和资源证据：任务 11、任务 12、任务 13 覆盖慢消息采样、线程 CPU、Checktime 和环境采集。
- 系统确认和专项治理：任务 14、任务 15、任务 16、任务 17 覆盖 confirmed ANR、SP 全量治理、Barrier token、nativePollOnce 和 Binder 疑似。
- 工程闭环：任务 18、任务 19、任务 20 覆盖报告治理、SDK 自监控、全量验收和服务端消费协议。

已保持的边界：

- 没有承诺端侧确认所有 ANR 根因。
- 没有把当前 Trace 当作唯一根因。
- 没有把所有 `NativePollOnce` 都归为 Barrier。
- SP 绕过等待进入任务 15，但默认关闭，并要求灰度白名单、黑名单和回滚策略。
- 外部系统负载和跨进程死锁进入任务 13、任务 17，但输出为环境证据或疑似结论，不强行确认为业务根因。

第一轮审核建议：

- 后续实现时仍应先完成任务 1 到任务 10 建立可运行骨架，再执行任务 11 到任务 20 补齐全量能力。
- 当前文档可作为全量 SDK 实现入口，也可作为评审时解释“哪些能力先稳定上线、哪些能力灰度增强”的依据。

### 第二轮：工程可执行性审核

审核结论：通过，且已根据审核修正两个执行风险。

已修正的问题：

- Watchdog 原始循环如果每轮都重新 post 心跳，会覆盖未响应心跳的起始时间，主线程卡住时可能永远不触发超时。本文已改为 `hasPending()` 为 false 时才 post 新心跳，并补充对应单测。
- 疑似 ANR 触发后如果主线程持续卡住，Watchdog 可能按间隔重复生成报告。本文已在 `AnrMonitorRuntime` 中增加 `lastSuspectReportUptimeMs`，按 `suspectAnrMs` 做重复快照抑制。

可执行性较强的点：

- 每个任务都有明确文件列表，适合按任务独立提交。
- 纯 Kotlin 逻辑先写 JVM 单测，降低 Android 环境不确定性。
- Android 反射和主线程采集集中在 collector 层，失败时以诊断字段降级。
- 本地报告先不引入三方 JSON 库，减少 Gradle 依赖和网络下载风险。
- demo app 的阈值设置为 3 秒，便于手动触发而不必等待系统真正弹 ANR。

仍需执行时关注的点：

- 任务 4 的 Looper Printer 代理只能读取当前 `mLogging`，如果后续三方库覆盖 Printer，阶段一只能通过现象诊断，不能自动恢复代理链。
- 任务 6 的 Pending 反射在 Android 版本和 ROM 上可能失败，验收不能要求所有设备都可用。
- 任务 8 的本地报告没有清理策略，阶段一验收可以接受，但 任务 18 必须补最大文件数、最大总大小和保留天数。
- 任务 9 的 demo `Message Storm` 可能因为队列快速 drain 导致归因为 unknown，计划已给出降低 `suspectAnrMs` 的重复验证路径。
- 任务 10 的 `run-as` 依赖 debug build 和设备支持，如果真实设备不支持，应改用 debug emulator 验收。

第二轮审核建议：

- 执行时优先完成任务 1 到任务 10 建立可运行 SDK，再进入任务 11 到任务 20 补齐全量诊断和治理能力。
- 每个 task 提交前至少跑该 task 指定测试；任务 9、任务 18、任务 19 后再跑全量 `:anr-monitor-sdk:testDebugUnitTest` 和 `:app:assembleDebug`。
- 如果某段代码片段在实际 AGP/Kotlin 环境中编译失败，应以“最小修改通过测试”为原则修正计划和实现，而不是绕过测试。

### 第三轮：评审交接审核

审核结论：通过。文档可以交给另一个 agent 或开发者按任务执行，也适合在技术评审中解释 阶段一取舍。

评审时建议固定的主线：

1. 先讲全量目标：覆盖 01 到 05 的核心输入，用过程化证据提升 ANR 归因可信度。
2. 再讲基础链路：模块、API、Looper 时间线、Watchdog、主线程栈、本地报告。
3. 再讲诊断增强：Pending、慢栈采样、线程 CPU、Checktime、系统环境、confirmed ANR。
4. 再讲专项治理：Barrier、SP、Binder/跨进程疑似、QueuedWork 灰度治理和报告自监控。
5. 最后讲验收闭环：单测、编译、demo 场景、全量验收记录和服务端消费协议。

评审中需要避免的表达：

- 不说“阶段一能定位所有 ANR”，应说“阶段一能输出基础证据链和保守归因”。
- 不说“Pending 失败就是 SDK 失败”，应说“Pending 是增强证据，失败要显式降级”。
- 不说“SP apply demo 等于稳定复现 SP_APPLY_WAIT”，应说“SP 监控包含栈识别、文件健康度、包装 API 和灰度治理，稳定专项复现通过任务 19 验收”。
- 不说“Barrier hook 已覆盖”，应说“Barrier 能力包含 Pending 队头识别、token 配对和 nativePollOnce 增强证据，高风险部分按灰度策略启用”。
- 不把 demo app 当生产接入样板，demo 是验证入口，生产接入还需要远程开关、采样和上传策略。

第三轮审核建议：

- 开始实现前，执行者应先确认当前分支状态，避免把 `.idea/` 等无关文件混入提交。
- 如果采用 Subagent-Driven，每个 task 的 agent 只处理该 task 文件范围，并在交接中报告测试命令和结果。
- 如果采用 Inline Execution，建议每 2 个 task 做一次中间 review，尤其在任务 5、任务 7、任务 9、任务 15、任务 18、任务 20 后停下来检查行为。
- 任务 20 完成后再进入代码实现收口和服务端联调，不再把线程 CPU、Checktime、SP 文件健康度、报告清理和自监控视为计划外补项。

## 中文化后二次举一反三提问

### 1. 中文描述和代码可复制性

- 文档说明已经中文化后，代码块里的 Kotlin、Gradle、XML、Shell、JSON 标识是否仍保持原样，能否直接复制执行？
- 是否存在把英文标识误翻成中文的情况，例如 `android`、`Handler`、`watchdogIntervalMs`、`privacyMode`、`randomUUID`？
- 文件路径、包名、类名、方法名是否仍与计划中后续任务引用一致？
- 中文说明是否清楚地区分“创建文件”“修改文件”“运行命令”“预期结果”，避免执行者把说明当代码？
- 文档开头的 agent 执行者说明是否仍保留 required sub-skill、checkbox 跟踪和逐任务执行的要求？

### 2. TDD 步骤完整性

- 每个实现任务是否仍然保持“写失败测试 -> 运行确认失败 -> 写最小实现 -> 运行确认通过 -> 提交”的节奏？
- 中文化后是否还有某些步骤只写“新增实现”，但没有给出完整代码块？
- 测试命令的 `--tests` 类名是否与代码块里的测试类名一致？
- 预期失败信息是否能让执行者确认失败原因正确，而不是因为 Gradle 配置或语法错误失败？
- 每个任务的提交命令是否只包含该任务相关文件，避免误提交 `.idea/` 或其他无关改动？

### 3. 计划执行顺序

- 任务 1 是否先建立模块和测试依赖，保证后续所有 `:anr-monitor-sdk:testDebugUnitTest` 命令有目标模块？
- 任务 2 是否先定义领域模型，再让 collector、analyzer、encoder 依赖这些稳定类型？
- 任务 5 的 Watchdog 是否依赖任务 3 的时间源和任务 4 的时间线，而不是提前访问未定义类型？
- 任务 7 的归因是否依赖任务 6 的 Pending 摘要，避免分析器自己重复实现 Pending 统计？
- 任务 9 是否只在 SDK 单测通过后接入 app，避免 demo app 先承担 SDK 编译错误？

### 4. 运行时风险

- Watchdog 是否在主线程卡住时不覆盖未响应心跳，避免疑似 ANR 永远不触发？
- 疑似 ANR 是否有重复快照抑制，避免一次长卡顿不断生成报告？
- `Looper.setMessageLogging` 代理是否转发已有 Printer，避免破坏宿主或三方库监控？
- Pending 反射失败时是否显式降级，不把失败吞掉导致报告看起来“证据齐全”？
- 本地 JSON 落盘是否只发生在疑似 ANR 快照路径，避免在主线程高频消息路径做 IO？

### 5. 归因保守性

- `SYNC_BARRIER_STUCK` 是否必须依赖 Pending 队头 Barrier 和同步消息等待超阈值，而不是只凭 `NativePollOnce`？
- `SP_LOAD_WAIT` 和 `SP_APPLY_WAIT` 是否只在栈命中强证据时输出高置信度？
- `CURRENT_MESSAGE_SLOW` 是否要求当前消息 Wall/Cpu 对齐，避免把等待型卡顿误判为业务计算慢？
- `MESSAGE_STORM` 是否基于 Pending 或历史消息重复特征，而不是因为当前消息短就误判？
- 未命中强证据时是否输出 `UNKNOWN_INSUFFICIENT_EVIDENCE` 和缺失证据，而不是强行定责？

### 6. 验收和评审

- 验收记录是否要求写入真实命令结果，而不是只保留理论预期？
- demo 场景是否明确 Current Slow、Message Storm、SP Apply Burst 的复现稳定性差异？
- 如果设备不支持 `run-as`，验收记录是否允许切换到 debug emulator，而不是卡死在单一设备？
- 评审时是否明确线程 CPU 排名、Checktime、SP 文件健康度、Hook 风险能力、QueuedWork 绕过都已经在任务 11 到任务 20 中覆盖？
- 任务 20 是否给出服务端消费协议和全量追溯，避免实现完成后仍缺少聚类、看板和 owner hint 口径？

## 中文化后三轮审核

### 第一轮：中文化完整性审核

审核结论：通过。文档标题、目标、架构、范围、文件结构、任务步骤、预期结果、自审、提问和审核内容已经中文化，保留的英文主要是必要技术标识。

已确认的中文化结果：

- 章节标题已经从英文改为中文，包括“范围检查”“文件结构”“实施任务”“最终验证命令”“自审”。
- 步骤标题已经改为“步骤 N：...”，并保持 checkbox 结构，便于执行跟踪。
- `Run`、`Expected`、`Create`、`Modify` 等说明性词汇已经改为“运行”“预期”“创建”“修改”。
- 代码块内的 Android/Kotlin/Gradle/XML 标识仍保持英文，例如 `android`、`Handler`、`watchdogIntervalMs`、`privacyMode`。
- 扫描未发现 Android、Handler、command、random、watchdog 等技术标识被中文化误伤的残留。

需要继续保持的边界：

- 技术标识不需要中文化，例如包名、类名、方法名、Gradle alias、JSON key。
- 命令输出中的 `PASS`、`FAIL` 可以保留英文，因为它们对应测试和构建工具的真实输出。
- `Subagent-Driven`、`Inline Execution` 可作为执行模式名保留英文，但说明文字必须让中文读者理解其含义。

第一轮审核结论：

- 中文化没有破坏文档作为执行计划的基本结构。
- 代码块可复制性已经作为重点检查项纳入二次提问。
- 后续如果继续修改文档，应避免全局替换 `and`、`android` 等英文片段，优先手工或小范围替换。

### 第二轮：执行可行性审核

审核结论：通过，但执行时仍要把“文档计划”和“真实编译反馈”绑定起来。计划足够细，但其中的代码片段需要在实际 Gradle/Kotlin 环境中逐任务验证。

已确认的可执行性：

- 任务 1 先建立 `anr-monitor-sdk` 模块和 JUnit 依赖，后续测试命令有明确目标。
- 任务 2 到任务 8 均有失败测试、实现代码、通过测试和提交命令。
- 任务 9 先跑 app 编译基线，再接入 SDK runtime 和 demo 场景，能区分原 app 问题和 SDK 接入问题。
- 任务 10 把手动验证结果沉淀到 `docs-anr/100-ANR监控SDK-阶段一验收记录.md`，不会只停留在口头验收。
- `.idea/` 明确不在实现范围内，避免提交噪声。

执行时要重点复核的风险：

- `LooperMessageParser` 对不同 Android 版本日志格式的解析可能不完整，阶段一可接受，但测试应覆盖当前格式。
- `MainLooperPrinterInstaller` 读取 `Looper#mLogging` 属于反射路径，失败时需要诊断，不应影响 SDK 其它能力。
- `PendingQueueSnapshotter` 反射字段可能受 ROM 影响，验收应接受 `available=false` 的降级报告。
- `LocalAnrReportWriter` 阶段一没有清理策略，不能作为线上长期开启的最终实现。
- `SharedPreferences Apply Burst` demo 不能稳定等价于系统生命周期 `SP_APPLY_WAIT`，文档已经把稳定专项复现放到任务 19 的全量验收。

第二轮审核结论：

- 当前文档可以交给执行者按任务推进。
- 执行者需要每个任务完成后真实运行对应命令，并把失败反馈回计划或实现。
- 若代码片段与实际编译冲突，应以测试通过和最小修改为准，同时更新计划文档保持一致。

### 第三轮：评审表达审核

审核结论：通过。中文化后文档更适合中文技术评审，但正式评审时仍应控制叙事顺序，避免陷入代码清单。

推荐评审表达：

1. 先说明全量目标：覆盖 01 到 05 的核心输入，输出过程化证据，不承诺端侧确认所有复杂根因。
2. 再说明 阶段一：模块、API、配置、消息时间线、Watchdog、主线程栈、本地 JSON。
3. 再说明 阶段二：Pending 快照、Barrier 疑似、SP 栈命中、基础归因、demo 验收。
4. 再说明工程安全：反射降级、重复快照抑制、隐私脱敏、`.idea/` 不提交、高风险能力按灰度开关启用。
5. 最后说明执行策略：逐任务 TDD、每任务提交、任务 5/7/9 后重点 review。

评审中应主动回答的问题：

- 为什么线程 CPU 和 Checktime 放在任务 12、任务 13：因为它们依赖基础快照模型，需要在任务 1 到任务 10 稳定后接入。
- 为什么 Hook、SIGQUIT、nativePollOnce 和 Barrier token 配对要灰度：因为它们兼容风险更高，必须有开关、降级和验收记录。
- 为什么 SP 绕过需要默认关闭：因为 `QueuedWork` 绕过会改变系统等待语义，只能在任务 15 的白名单、黑名单和回滚策略下启用。
- 为什么 Pending 失败也算可用报告：因为报告必须表达缺失证据，不能因为一个 collector 失败就丢掉当前/历史/栈证据。
- 为什么需要验收记录：因为 SDK 监控类能力不能只靠编译通过，需要证明真实 demo 能产出 JSON 和归因字段。

第三轮审核结论：

- 文档已经具备“可执行计划 + 中文评审材料”的双重用途。
- 下一步可以直接选择 Subagent-Driven 或 Inline Execution 开始实现。
- 如果用户要求提交，应只提交 `docs/superpowers/plans/2026-06-05-anr-monitor-sdk-full.md`，不要把未跟踪 `.idea/` 混入提交。

## 全量范围修正后举一反三提问

### 1. 输入文档覆盖追问

- 01 文档的 ANR 系统超时模型是否已经落到组件阈值、系统确认 ANR、Reason/Trace 非根因、当前/历史/Pending/环境综合判断？
- 02 文档的 Raster 思路是否已经落到过去消息、当前消息、Pending 队列、慢消息堆栈采样、Wall/Cpu、Checktime 和报告协议？
- 03 文档的案例归因是否已经覆盖当前慢、历史慢、累计慢、消息风暴、进程内 IO、外部系统负载、Binder/跨进程疑似？
- 04 文档的 Barrier 专项是否同时覆盖 Pending 队头识别、Barrier token、同步消息被挡、nativePollOnce 证据和灰度风险？
- 05 文档的 SharedPreferences 专项是否同时覆盖 `SP_LOAD_WAIT`、`SP_APPLY_WAIT`、文件健康度、包装 API、写盘耗时、QueuedWork 灰度绕过和一致性风险？

### 2. 任务链路闭环追问

- 任务 1 到任务 10 是否只是首个可运行里程碑，而不是最终 SDK 范围？
- 任务 11 到任务 13 是否把“慢栈采样 + 线程 CPU + Checktime + 环境”接入到同一份 `AnrSnapshot` 和 `AnrReport`？
- 任务 14 是否把系统 confirmed ANR 和组件阈值纳入模型，避免 SDK 只停留在 suspect ANR？
- 任务 15 到任务 17 是否分别形成 SP、Barrier、Binder 三个专项证据分支，而不是只在归因规则里写标签？
- 任务 18 到任务 20 是否补齐线上运行必需的报告治理、自监控、全量验收和服务端消费协议？

### 3. 风险能力追问

- `QueuedWork.waitToFinish` 绕过是否默认关闭，且必须经过文件白名单、黑名单、ROM/版本限制、快速回滚和一致性监控？
- Barrier token 和 nativePollOnce 监控是否被定义为增强证据，不能替代 Pending 队列证据直接下结论？
- Binder/跨进程阻塞是否坚持“疑似”口径，避免线上端侧证据不足时强行确认死锁？
- 外部系统负载是否依赖 Checktime、Load、线程 CPU、主线程 Wall/Cpu 组合证据，而不是单点归因？
- 线程 CPU 和 `/proc` 读取失败时是否降级为线程栈和缺失证据，不影响主报告生成？

### 4. 数据协议追问

- 报告是否能表达证据链：系统 Reason -> 当前 Trace -> 当前消息 -> 历史消息 -> Pending -> 线程 CPU -> Checktime -> SP/Barrier/Binder -> 归因？
- JSON schema 是否为慢栈采样、线程 CPU、Checktime、SP 健康度、Barrier token、Binder 疑似、SDK 自监控预留字段？
- 服务端聚类维度是否包含归因码、ANR 类型、当前栈 hash、历史慢消息栈 hash、Pending target/callback hash、SP 文件名、Barrier token、设备/ROM/版本？
- 隐私策略是否覆盖 Message obj、Intent/Bundle 内容、SP key/value、线程名、文件名、类名和栈帧？
- 报告保留策略是否限制文件数量、文件大小、重试次数和低置信事件丢弃策略？

### 5. 验收追问

- `docs-anr/101-ANR监控SDK全量验收记录.md` 是否覆盖所有 01 到 05 的输入能力，而不只是 阶段一 demo？
- 是否有单元测试覆盖纯 Kotlin 归因、采样、Checktime、线程 CPU、SP 健康度、Barrier token、Binder 分类、报告保留策略？
- 是否有 demo 或 instrumentation 场景覆盖当前慢、历史慢、消息风暴、SP load/apply、Barrier、Binder-like wait、busy loop？
- 是否有性能验收覆盖常驻 CPU、主线程单消息额外耗时、快照耗时、报告大小和采样频率？
- 是否有兼容验收覆盖 API 23 到 35、常见 ROM、前后台、多进程、冷启动、热启动和低内存场景？

## 全量范围修正后三轮审核

### 第一轮：覆盖完整性审核

审核结论：通过。当前计划已经是以任务 1 到任务 20 覆盖 01 到 05 输入内容的全量 SDK 实施计划。

已确认覆盖：

- 01 文档：任务 2、任务 5、任务 7、任务 14 覆盖 ANR 类型、组件阈值、系统确认 ANR、Reason/Trace 非根因和综合归因。
- 02 文档：任务 4、任务 6、任务 11、任务 12、任务 13、任务 18 覆盖 Raster 三段时间线、慢栈采样、Wall/Cpu、线程 CPU、Checktime 和报告治理。
- 03 文档：任务 7、任务 12、任务 13、任务 17 覆盖案例归因中的当前慢、历史慢、消息风暴、进程内资源、外部负载和 Binder 疑似。
- 04 文档：任务 6、任务 16 覆盖 Barrier 队头、token、同步消息阻塞和 nativePollOnce 增强证据。
- 05 文档：任务 7、任务 15 覆盖 SP load/apply 归因、文件健康度、包装 API 和 QueuedWork 灰度治理。

第一轮审核结论：

- 全量目标已写入标题、目标、范围检查和追溯矩阵。
- 任务 11 到任务 20 明确补齐后续阶段能力。
- 未发现把后续阶段当作可裁剪范围的剩余口径。

### 第二轮：执行落地性审核

审核结论：基本通过，但执行时必须坚持分阶段落地，不要把 20 个任务一次性混成一个大改动。

执行建议：

- 第一批执行任务 1 到任务 5，目标是 SDK 模块、API、消息时间线、Watchdog、主线程栈可编译可测试。
- 第二批执行任务 6 到任务 10，目标是 Pending、基础归因、本地报告和 demo 验收形成首个可运行闭环。
- 第三批执行任务 11 到任务 14，目标是慢栈采样、线程 CPU、Checktime、环境和 confirmed ANR 接入统一报告。
- 第四批执行任务 15 到任务 18，目标是 SP、Barrier、Binder 和报告治理专项能力落地。
- 第五批执行任务 19 到任务 20，目标是全量验收记录和服务端消费协议闭环。

高风险点：

- 任务 15 的 QueuedWork 绕过只能默认关闭，不能在 demo 或测试中默认启用。
- 任务 16 的 nativePollOnce 和 Barrier token 监控必须允许降级，不能影响正常消息调度。
- 任务 17 的 Binder 分类只能输出疑似，不能作为强确认归因。
- 任务 18 的报告治理必须防止本地文件无限增长。
- 任务 20 的服务端协议要和 `AnrReport` schema 同步，否则端侧字段会和看板消费脱节。

第二轮审核结论：

- 计划可以执行，但应按批次提交和复核。
- 每批结束都必须运行相关单测和 `:app:assembleDebug`。
- 如果实现中发现字段模型不够，应同步更新 `AnrSnapshot`、`AnrReport`、JSON encoder 和服务端协议。

### 第三轮：评审表达审核

审核结论：通过。修正后的文档可以清晰回答“SDK 是否覆盖 01 到 05 全部内容”这个评审问题。

建议评审话术：

1. 本计划覆盖全量能力，阶段一到阶段四只是交付顺序，最终 SDK 覆盖 01 到 05 的全部核心输入。
2. 任务 1 到任务 10 先建立可运行闭环，任务 11 到任务 20 补齐线上诊断、专项治理、报告治理和服务端消费。
3. 复杂能力不缺席，但会灰度：QueuedWork 绕过、Barrier token、nativePollOnce 这类风险能力必须有开关、白名单和回滚。
4. 端侧不强行确认所有根因：外部系统负载、Binder/跨进程阻塞输出疑似和缺失证据，保留线下复核入口。
5. 验收不只看编译通过，还要看 `100` 阶段一验收、`101` 全量验收和 `102` 服务端消费协议。

第三轮审核结论：

- 文档现在能支撑全量 SDK 立项、拆分执行和技术评审。
- 后续应把任务 11 到任务 20 视为全量 SDK 的必要组成部分。
- 如果需要执行实现，建议从任务 1 开始按批次推进，而不是直接跳到专项能力。

## 01 到 05 全量覆盖专项复核

本轮复核结论：通过。当前计划覆盖 01 到 05 的全部核心内容，但覆盖口径必须明确为“SDK 能力级覆盖”，不是承诺端侧在所有 Android 版本和 ROM 上都能直接拿到完整系统 Trace、Logcat、Kernel、Meminfo、Perfetto 等特权或线下证据。端侧不可稳定采集的内容，必须通过可得性分级、缺失证据、线下复核入口和服务端协议覆盖，不能在报告里伪装成强证据。

覆盖等级说明：

- A：端侧 SDK 必须直接实现并进入 `AnrSnapshot` / `AnrReport`。
- B：端侧 SDK 必须记录可得性、失败原因、缺失证据或摘要字段，并保留线下复核入口。
- C：端侧 SDK 必须提供治理策略、灰度开关、回滚策略或服务端消费口径。

| 输入文档 | 核心内容 | 覆盖等级 | 计划落点 | 本轮判断 |
| --- | --- | --- | --- | --- |
| 01 设计原理及影响因素 | ANR 类型、组件阈值、系统等待超时模型、Reason/Trace 非根因 | A | 任务 2、5、7、14、20 | 已覆盖 |
| 01 设计原理及影响因素 | 当前消息、历史消息、累计慢、消息风暴、Pending、系统/进程内环境综合判断 | A/B | 任务 4、6、7、11、12、13、17、20 | 已覆盖 |
| 01 设计原理及影响因素 | Trace 完整性、dump 超时、系统/关键进程 trace 缺失容错 | B | 任务 14、18、20 | 已覆盖，但执行时必须进入缺失证据字段 |
| 02 监控工具与分析思路 | Raster 过去/当前/Pending 三段时间线、消息聚合、IDLE、关键消息拆分 | A | 任务 3、4、6、7、11 | 已覆盖 |
| 02 监控工具与分析思路 | 慢消息堆栈采样、Wall/Cpu 对照、线程 CPU、Checktime | A | 任务 4、11、12、13 | 已覆盖 |
| 02 监控工具与分析思路 | AnrInfo、Logcat、Kernel、Meminfo、Perfetto/Systrace 分层分析 | A/B | 任务 13、14、18、19、20 | 已覆盖；Logcat/Kernel/Meminfo/Perfetto 按可得性和线下复核覆盖 |
| 03 实例剖析集锦 | 当前慢、历史慢、累计慢、消息风暴、进程内 IO、外部系统负载 | A/B | 任务 7、11、12、13、18、20 | 已覆盖；外部系统负载只能输出证据链或疑似 |
| 03 实例剖析集锦 | Binder/跨进程死锁、证据链归因、可治理性分层 | B/C | 任务 17、18、20 | 已覆盖；端侧只输出疑似和复核入口 |
| 04 Barrier 导致主线程假死 | Pending 队头 `target == null`、同步消息被 Barrier 阻挡、普通消息 Block 时长 | A | 任务 6、7、16 | 已覆盖 |
| 04 Barrier 导致主线程假死 | Barrier token 配对、`nativePollOnce(timeoutMillis)` 进入/退出/反复 `-1` 证据 | A/B | 任务 16、18、20 | 已覆盖；高风险采集必须灰度和可降级 |
| 04 Barrier 导致主线程假死 | UI 刷新链路 Barrier 生命周期评审和泄漏治理 | C | 任务 16、19、20 | 已覆盖 |
| 05 告别 SharedPreference 等待 | `SP_LOAD_WAIT`、`SP_APPLY_WAIT` 两套归因 | A | 任务 7、15、19、20 | 已覆盖 |
| 05 告别 SharedPreference 等待 | SP 文件名、大小、key 数、首次加载耗时、apply/commit 次数、调用栈、写盘耗时、Pending finisher 队列 | A/B | 任务 15、18、20 | 已覆盖；执行任务 15 时这些字段不能省略 |
| 05 告别 SharedPreference 等待 | `QueuedWork.waitToFinish` 绕过、关键文件排除、版本/ROM 白名单、回滚和一致性监控 | C | 任务 15、18、19、20 | 已覆盖；默认必须关闭 |

### 本轮举一反三提问

- 如果评审问“覆盖全部内容是否意味着端侧要采集完整 Kernel/Logcat/Perfetto”，回答是否明确为“不是，端侧记录可得性和缺失证据，线下证据通过复核入口和服务端协议承接”？
- 如果任务 13 只实现 `loadAverage1m` 和存储空间，是否足够覆盖 01/02/03 的系统环境要求？不够，执行时还必须表达 CPU/IO/内存证据可得性、采集失败原因和降级字段。
- 如果任务 15 只做 SP 文件大小排序，是否足够覆盖第 5 篇？不够，执行时还必须补齐 load/apply 两类归因字段、调用点、写入耗时、pending finisher 队列和灰度绕过策略。
- 如果任务 16 只看 Pending 队头 Barrier，是否足够覆盖第 4 篇？不够，执行时还必须补齐 token 生命周期和 `nativePollOnce(timeoutMillis)` 增强证据，但这些能力必须允许关闭和降级。
- 如果任务 17 识别 Binder 栈，是否能直接判定跨进程死锁？不能，只能输出 `BINDER_BLOCK_SUSPECTED`、证据链和线下 Trace/Perfetto 复核入口。
- 如果报告 JSON 没有“缺失证据”和“证据可得性”，是否还能说覆盖 01 到 05？不能，任务 18 和任务 20 必须把缺失证据作为一等字段。
- 如果验收只跑 阶段一 demo，是否能证明覆盖 01 到 05？不能，必须完成任务 19 的全量验收记录和任务 20 的服务端消费协议。

### 本轮三轮审核

#### 第一轮：覆盖口径审核

审核结论：通过，覆盖口径已经从“阶段一功能”修正为“全量 SDK 能力”。计划标题、目标、范围检查、追溯矩阵、任务 11 到任务 20 和本专项复核都明确说明：阶段一到阶段四只是交付顺序，不是能力裁剪。

必须保持的口径：

- 01 到 05 的核心能力都要进入计划任务。
- 端侧能直接采集的能力必须实现。
- 端侧不能稳定采集的系统证据必须记录可得性、缺失原因和线下复核入口。
- 高风险治理能力必须默认关闭，并通过灰度、白名单、回滚和一致性监控落地。

#### 第二轮：任务落地审核

审核结论：通过，但执行时需要按本专项复核补强任务验收标准。

执行硬约束：

- 任务 13 不能只保存设备型号和 load average，还要表达系统负载、内存、IO、外部证据可得性和采集失败原因。
- 任务 15 不能只做 SP 文件健康扫描，还要覆盖 `SP_LOAD_WAIT`、`SP_APPLY_WAIT`、调用栈、线程、写盘耗时、pending finisher、生命周期等待和 QueuedWork 灰度策略。
- 任务 16 不能只做 token map，还要记录 `nativePollOnce` 的 timeout、进入/退出时间、调用次数，以及反复 `timeoutMillis=-1` 与 Pending 队列的对齐关系。
- 任务 18 不能只做报告数量保留，还要覆盖 gzip、重试、采样、限频、隐私、缺失证据和 SDK 自监控指标。
- 任务 20 不能只写服务端聚类维度，还要同步 `AnrReport` schema、缺失证据、owner hint、线下复核入口和治理建议。

#### 第三轮：评审结论审核

审核结论：通过。可以在评审中回答“是否覆盖 01 到 05 的所有内容”：覆盖，且覆盖方式分为端侧直接实现、端侧降级证据、线下复核入口和治理闭环四层。

评审回答建议：

1. 这份计划覆盖全量能力，文件名已经改为 `2026-06-05-anr-monitor-sdk-full.md`，正文也已修正为全量实施计划。
2. 01 到 05 的文章内容已经映射到任务 1 到任务 20，其中任务 11 到任务 20 是全量能力的必要组成部分。
3. 对 Kernel/Logcat/Perfetto、外部系统负载、跨进程死锁这类端侧不可强确认内容，SDK 不做虚假强归因，而是输出证据可得性、疑似结论和复核入口。
4. 对 Barrier、QueuedWork 绕过、nativePollOnce 这类高风险能力，计划覆盖但默认关闭或可降级。
5. 覆盖是否完成最终以任务 19 的全量验收记录和任务 20 的服务端消费协议为准，不能只以编译通过或 阶段一 demo 通过为准。
