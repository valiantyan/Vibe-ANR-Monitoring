# 本地 AAR 接入与 Monkey 验收指南

本文面向把当前 ANR Monitor SDK 接入到普通 Android 项目并跑 monkey 的同学。更完整的 API、字段和报告说明见 [103-ANR监控SDK使用说明.md](./103-ANR监控SDK使用说明.md)。

## 1. 交付物

本次已构建 Release AAR：

```text
dist/anr-monitor-sdk-release-minsdk22-20260616.aar
```

构建来源：

```bash
./gradlew :anr-monitor-sdk:assembleRelease
```

产物校验信息：

```text
文件大小：约 298K
SHA-256：c99674d28e046a20246ce4215ae718d1133e38420b0586717d01680b6a91438b
minSdk：22
Manifest 权限：无额外权限声明
```

原始 Gradle 输出目录：

```text
anr-monitor-sdk/build/outputs/aar/anr-monitor-sdk-release.aar
```

## 2. 接入前检查

宿主项目需要满足：

| 项目 | 要求 |
| --- | --- |
| Android minSdk | `>= 22` |
| 编译语言 | Kotlin 接入最自然；Java 项目也可接入，但公开 API 当前是 Kotlin-first |
| 初始化位置 | 主进程 `Application.onCreate()` 尽早安装 |
| 运行进程 | 建议只在主进程安装，避免远端 service 进程重复采集 |
| 混淆 | AAR 内置 `consumer-rules.pro`；当前不需要宿主额外添加 keep 规则 |
| 权限 | SDK 不要求新增 Android 权限 |

## 3. 放置 AAR

把 AAR 放到宿主 app 模块的 `libs` 目录，例如：

```text
your-app/
└── app/
    └── libs/
        └── anr-monitor-sdk-release-minsdk22-20260616.aar
```

如果 `libs` 目录不存在，先创建：

```bash
mkdir -p app/libs
```

## 4. 添加 Gradle 依赖

在宿主 `app/build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(files("libs/anr-monitor-sdk-release-minsdk22-20260616.aar"))
}
```

如果宿主使用 Groovy DSL：

```groovy
dependencies {
    implementation files("libs/anr-monitor-sdk-release-minsdk22-20260616.aar")
}
```

同步并编译宿主项目：

```bash
./gradlew :app:assembleDebug
```

## 5. 初始化 SDK

在宿主 `Application.onCreate()` 中安装 SDK。普通项目建议使用 `AnrMonitor.installMainProcessOnly()`，SDK 会内部判断当前进程是否为主进程；monkey 阶段先关闭上传，只在本地落盘报告，避免把测试噪声打到线上链路。

```kotlin
import android.app.Application
import android.util.Log
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrMonitorSession
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val session: AnrMonitorSession? = AnrMonitor.installMainProcessOnly(
            context = this,
            config = AnrMonitorConfig(
                appId = "your-app-id",
                environment = "monkey",
                enabled = true,
                uploadEnabled = false,
                sampleRate = 1.0f,
                suspectAnrMs = 5_000L,
                watchdogIntervalMs = 1_000L,
                captureChecktime = true,
                captureSystemEnvironment = true,
                captureThreadCpu = true,
                capturePendingQueue = true,
                captureBarrierEvidence = false,
                captureBinderEvidence = true,
            ),
            listener = object : AnrEventListener {
                override fun onSuspectAnr(snapshot: AnrSnapshot) {
                    Log.w("AnrMonitor", "suspect ANR captured: ${snapshot.eventId}")
                }

                override fun onReportGenerated(report: AnrReport) {
                    Log.w("AnrMonitor", "ANR report generated: ${report.snapshot.eventId}")
                }

                override fun onMonitorError(error: Throwable) {
                    Log.e("AnrMonitor", "ANR monitor error: ${error.message}", error)
                }
            },
        )
        if (session == null) {
            Log.i("AnrMonitor", "skip ANR monitor outside main process")
            return
        }
        Log.i("AnrMonitor", "ANR monitor installed: $session")
    }
}
```

如果宿主明确需要监控某个非主进程，可以在该进程内显式调用 `AnrMonitor.install()`；不要在所有进程无差别安装，否则每个进程都会各自创建 Watchdog 和报告目录。

在 `AndroidManifest.xml` 中确认宿主声明了自己的 Application：

```xml
<application
    android:name=".App"
    ...>
</application>
```

## 6. Monkey 前置验证

安装宿主 debug 包：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

启动一次应用，确认 SDK 安装日志没有异常：

```bash
adb shell monkey -p your.package.name 1
adb logcat -d | grep -E "AnrMonitor|ANR monitor|suspect ANR|ANR report"
```

查看本地报告目录是否可访问：

```bash
adb shell run-as your.package.name ls files
```

如果 `run-as` 报错，通常是安装包不是 debug 包、包名不对，或设备不允许调试该 app。

## 7. Monkey 执行命令

建议先跑小流量冒烟：

```bash
adb logcat -c
adb shell monkey \
  -p your.package.name \
  --ignore-crashes \
  --ignore-timeouts \
  --monitor-native-crashes \
  --pct-touch 55 \
  --pct-motion 20 \
  --pct-appswitch 10 \
  --pct-nav 5 \
  --throttle 200 \
  -v -v -v \
  3000
```

冒烟稳定后再提高事件数：

```bash
adb logcat -c
adb shell monkey \
  -p your.package.name \
  --ignore-crashes \
  --ignore-timeouts \
  --monitor-native-crashes \
  --pct-touch 55 \
  --pct-motion 20 \
  --pct-appswitch 10 \
  --pct-nav 5 \
  --throttle 200 \
  -v -v -v \
  50000
```

参数说明：

| 参数 | 目的 |
| --- | --- |
| `--ignore-timeouts` | 遇到 ANR 后继续跑，方便一轮收集多个问题 |
| `--ignore-crashes` | 遇到 crash 后继续跑，避免自动化过早停止 |
| `--monitor-native-crashes` | 同步观察 native crash |
| `--throttle 200` | 控制事件间隔，降低纯随机点击造成的不可读噪声 |
| `-v -v -v` | 输出更详细 monkey 日志，方便和报告时间对齐 |

## 8. 提取日志和报告

导出 logcat：

```bash
adb logcat -d > monkey-logcat.txt
```

查看 SDK 报告目录：

```bash
adb shell run-as your.package.name ls files/anr-monitor-reports
```

拉取单个报告：

```bash
adb exec-out run-as your.package.name cat files/anr-monitor-reports/<eventId>.json > <eventId>.json
```

批量拉取可以先压缩：

```bash
adb shell run-as your.package.name sh -c 'cd files && tar -czf anr-monitor-reports.tar.gz anr-monitor-reports'
adb exec-out run-as your.package.name cat files/anr-monitor-reports.tar.gz > anr-monitor-reports.tar.gz
```

关键日志检索：

```bash
grep -E "suspect ANR captured|ANR report generated|ANR monitor error|ActivityManager.*ANR|am_anr" monkey-logcat.txt
```

## 9. 验收口径

一次 monkey 接入验收至少确认：

| 检查项 | 通过标准 |
| --- | --- |
| 宿主可编译 | `./gradlew :app:assembleDebug` 成功 |
| 应用可启动 | `adb shell monkey -p your.package.name 1` 成功启动 |
| SDK 不影响启动 | logcat 中无连续 `ANR monitor error` |
| monkey 可持续执行 | 冒烟事件数执行完，或出现问题时有可复现日志 |
| 报告可落盘 | 触发疑似 ANR 后 `files/anr-monitor-reports` 下存在 JSON |
| 报告可读取 | JSON 中包含 `event`、`attribution`、`mainThread`、`sdkDiagnostics` |
| 无额外权限要求 | 合并 Manifest 中没有因为 SDK 新增敏感权限 |

如果 monkey 没触发 ANR，不代表 SDK 未接入成功。可以先通过 logcat 确认初始化无异常，再用业务已知卡顿路径或 debug 包中的人为阻塞入口触发一次报告。

## 10. 常见问题

| 现象 | 排查方向 |
| --- | --- |
| 编译找不到 `com.valiantyan.anrmonitor` | 检查 AAR 是否放在 `app/libs`，以及 Gradle 是否添加 `implementation(files(...))` |
| `run-as` 失败 | 确认包名、debuggable、安装的是 debug 包 |
| 没有报告文件 | 确认只在主进程安装、`enabled=true`、`sampleRate=1.0f`、触发时间超过 `suspectAnrMs` |
| 报告字段为空 | 看 `sdkDiagnostics` 中的采集失败原因，部分系统字段受 ROM 和 Android 版本限制 |
| Looper Printer 被其他库覆盖 | logcat 和报告诊断中会出现冲突信号；优先排查其他性能监控库的安装顺序 |
| monkey 过早停止 | 使用 `--ignore-timeouts --ignore-crashes`，并同时保存 monkey 输出和 logcat |

## 11. 建议提交给 SDK 侧的材料

跑完 monkey 后，建议回传：

```text
1. 宿主包名、版本、commit、构建类型
2. 设备型号、Android 版本、ROM
3. monkey 完整命令和事件数
4. monkey 输出日志
5. logcat 文件
6. files/anr-monitor-reports 下的 JSON 报告
7. 是否同时接入其他性能监控/Looper Printer 类 SDK
```

这些材料能帮助判断问题是宿主业务 ANR、系统环境压力、monkey 噪声，还是 SDK 自身采集兼容性问题。
