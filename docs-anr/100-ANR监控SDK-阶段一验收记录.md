# ANR 监控 SDK 阶段一验收记录

验收日期：2026-06-06

## 验收环境

- 项目路径：`/Users/yanhao/Desktop/demo/Vibe-ANR-Monitoring`
- 验收设备：`Pixel_9a` AVD
- 设备型号：`sdk_gphone64_arm64`
- Android 版本：`16`
- SDK API：`36`
- 屏幕尺寸：`1080x2424`
- 应用包名：`com.valiantyan.vibeanrmonitoring`

设备信息命令输出：

```bash
$ adb shell getprop ro.product.model
sdk_gphone64_arm64

$ adb shell getprop ro.build.version.release
16

$ adb shell getprop ro.build.version.sdk
36

$ adb shell wm size
Physical size: 1080x2424
```

## 构建结果

- `./gradlew :anr-monitor-sdk:testDebugUnitTest`：PASS
- `./gradlew :app:assembleDebug`：PASS
- `./gradlew :app:installDebug`：PASS

关键命令输出：

```bash
$ ./gradlew :anr-monitor-sdk:testDebugUnitTest
BUILD SUCCESSFUL in 1s
16 actionable tasks: 2 executed, 14 up-to-date

$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 5s
54 actionable tasks: 2 executed, 52 up-to-date

$ ./gradlew :app:installDebug
Installing APK 'app-debug.apk' on 'Pixel_9a(AVD) - 16' for :app:debug
Installed on 1 device.

BUILD SUCCESSFUL in 2s
55 actionable tasks: 7 executed, 48 up-to-date
```

## 准备步骤

清理旧报告：

```bash
$ adb shell run-as com.valiantyan.vibeanrmonitoring rm -rf files/anr-monitor-reports
```

命令成功退出，无 stdout。

启动 demo：

```bash
$ adb shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
Starting: Intent { cmp=com.valiantyan.vibeanrmonitoring/.MainActivity }
```

UI 坐标来自 `uiautomator dump`：

- `Current Slow Message`：`[63,981][1017,1107]`，点击中心点 `540,1044`
- `Message Storm`：`[63,1149][1017,1275]`，点击中心点 `540,1212`

> 2026-06-08 范围纠偏：`SharedPreferences Apply Burst` 已从当前 Demo 和 SDK 验收中移除。第五篇文档只作为 ANR 监控完成后的实战分析案例，不再作为 SDK 需求或 Demo 场景。

## 当前消息慢场景

- 触发入口：`Current Slow Message`
- 触发命令：`adb shell input tap 540 1044`
- 报告文件：`6d722839-938b-4d62-a6d5-faf6f3311605.json`
- 期望归因：`CURRENT_MESSAGE_SLOW`
- 实际归因：`CURRENT_MESSAGE_SLOW`
- 结论：PASS

报告目录输出：

```bash
$ adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
6d722839-938b-4d62-a6d5-faf6f3311605.JSON
```

Logcat 输出：

```bash
$ adb logcat -d -s VibeAnrApplication:W '*:S'
--------- beginning of main
06-06 11:43:27.786  5373  5389 W VibeAnrApplication: suspect ANR captured: 6d722839-938b-4d62-a6d5-faf6f3311605
06-06 11:43:27.797  5373  5389 W VibeAnrApplication: confirmed ANR report: 6d722839-938b-4d62-a6d5-faf6f3311605
```

关键 JSON 字段输出：

```bash
$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"schemaVersion\":[0-9]*' files/anr-monitor-reports/6d722839-938b-4d62-a6d5-faf6f3311605.json"
"schemaVersion":1

$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"eventType\":\"[A-Z_]*\"' files/anr-monitor-reports/6d722839-938b-4d62-a6d5-faf6f3311605.json"
"eventType":"SUSPECT_ANR"

$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"appId\":\"[^\"]*\"' files/anr-monitor-reports/6d722839-938b-4d62-a6d5-faf6f3311605.json"
"appId":"vibe-anr-demo"

$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"primary\":\"[A-Z_]*\"' files/anr-monitor-reports/6d722839-938b-4d62-a6d5-faf6f3311605.json"
"primary":"CURRENT_MESSAGE_SLOW"

$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"current\":{[^}]*}' files/anr-monitor-reports/6d722839-938b-4d62-a6d5-faf6f3311605.json"
"current":{"seq":62,"kind":"CURRENT","messageType":"looper_dispatch","what":0,"targetClass":"android.view.ViewRootImpl$ViewRootHandler","callbackClass":"android.view.View$PerformClick@8b40464","isCriticalComponent":false,"startUptimeMs":248309,"endUptimeMs":null,"wallMs":3022,"cpuMs":0,"count":1,"sampleStackIds":[]}
```

关键证据：当前消息 `wallMs=3022` 已超过 demo 配置 `suspectAnrMs=3000`，主线程栈来自 demo 按钮点击链路。

## 消息风暴场景

- 触发入口：`Message Storm`
- 触发命令：`adb shell input tap 540 1212`
- 报告文件：`e6ab7a35-09ae-4ee5-89c9-eae321e9ee48.json`
- 期望归因：`MESSAGE_STORM`
- 实际归因：`MESSAGE_STORM`
- 结论：PASS

报告目录输出：

```bash
$ adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
e6ab7a35-09ae-4ee5-89c9-eae321e9ee48.JSON
```

Logcat 输出：

```bash
$ adb logcat -d -s VibeAnrApplication:W '*:S'
--------- beginning of main
06-06 11:46:44.171  5665  5680 W VibeAnrApplication: suspect ANR captured: e6ab7a35-09ae-4ee5-89c9-eae321e9ee48
06-06 11:46:44.201  5665  5680 W VibeAnrApplication: confirmed ANR report: e6ab7a35-09ae-4ee5-89c9-eae321e9ee48
```

关键 JSON 字段输出：

```bash
$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"primary\":\"[A-Z_]*\"' files/anr-monitor-reports/e6ab7a35-09ae-4ee5-89c9-eae321e9ee48.json"
"primary":"MESSAGE_STORM"

$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"evidence\":\[[^]]*\]' files/anr-monitor-reports/e6ab7a35-09ae-4ee5-89c9-eae321e9ee48.json"
"evidence":["pending repeated target count=198"]

$ adb shell "run-as com.valiantyan.vibeanrmonitoring grep -o '\"current\":{[^}]*}' files/anr-monitor-reports/e6ab7a35-09ae-4ee5-89c9-eae321e9ee48.json"
"current":{"seq":70,"kind":"CURRENT","messageType":"looper_dispatch","what":0,"targetClass":"android.view.ViewRootImpl$ViewRootHandler","callbackClass":"android.view.View$PerformClick@2797bfc","isCriticalComponent":false,"startUptimeMs":444325,"endUptimeMs":null,"wallMs":3380,"cpuMs":0,"count":1,"sampleStackIds":[]}
```

关键证据：Pending 队列中同类 `android.os.Handler` / `MainActivity$$ExternalSyntheticLambda3` 消息重复，归因证据为 `pending repeated target count=198`。

## 验收中发现并修复的问题

### 当前慢消息低 CPU 误判 UNKNOWN

初次触发 `Current Slow Message` 时，报告中当前消息 `wallMs=3326` 已超过 `suspectAnrMs=3000`，但 `cpuMs=0`，原规则要求 CPU/Wall 比例大于 `0.5`，导致主归因为 `UNKNOWN_INSUFFICIENT_EVIDENCE`。

修复方式：

- 新增 `AttributionAnalyzerTest.analyzeReturnsCurrentSlowWhenCurrentWallIsHighAndCpuIsLow`
- 当前消息规则改为 `wallMs >= suspectAnrMs` 即归为 `CURRENT_MESSAGE_SLOW`
- CPU 比例只影响置信度：高 CPU 为 `MEDIUM`，低 CPU 等等待型阻塞为 `LOW`

### 消息风暴 demo 无法触发 Watchdog

初次触发 `Message Storm` 时，只投递 2000 个短 Runnable，没有阻塞主线程，watchdog 无法形成疑似 ANR 快照。

修复方式：

- 新增 `AttributionAnalyzerTest.analyzeReturnsMessageStormBeforeCurrentSlowWhenPendingHasRepeatedTarget`
- 归因顺序调整为 SP、Barrier、Message Storm、Current、History
- demo 的 `postMessageStorm()` 在堆积 pending 消息后阻塞当前点击消息，给 watchdog 留出采样窗口

## 阶段一结论

阶段一通过：SDK 可初始化、可采集主线程消息时间线、可触发疑似 ANR 快照、可输出本地 JSON、可给出基础归因和缺失证据。

本阶段已经通过真实 AVD 验收：

- `CURRENT_MESSAGE_SLOW`：PASS
- `MESSAGE_STORM`：PASS
