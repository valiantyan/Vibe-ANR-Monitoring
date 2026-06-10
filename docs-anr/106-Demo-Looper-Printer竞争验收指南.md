# Demo Looper Printer 竞争验收指南

本文用于验证 Demo App 中新增的 Looper Printer 竞争场景，重点确认两个结论：

1. 多个 Looper 同时存在时，不会天然竞争同一个 `Printer` 槽位。
2. 后装第三方替换主 Looper `Printer` 后，SDK 不默认抢回槽位，但能在报告诊断中记录 `looper_printer_replaced`。

## 1. 准备环境

编译并安装 Debug 包：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

清理旧报告和旧日志，避免误读历史数据：

```bash
adb logcat -c
adb shell run-as com.valiantyan.vibeanrmonitoring rm -rf files/anr-monitor-reports
```

启动 Demo App：

```bash
adb shell am start -S -n com.valiantyan.vibeanrmonitoring/.MainActivity
```

## 2. 验证多个 Looper 不影响 SDK

### 2.1 触发 worker Looper Printer

方式一：在 Demo 页面点击“多 Looper Printer 验证”。

方式二：使用 adb intent：

```bash
adb shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity \
  --es anr_demo_scenario looper_worker_printer
```

检查 logcat：

```bash
adb logcat -d -s MainActivity LooperPrinterProbe DemoWorkerLooper DemoWorkerPrinter VibeAnrApplication
```

预期能看到类似日志：

```text
LooperPrinterProbe: worker looper printer installed, main looper printer untouched
DemoWorkerLooper: worker looper printer installed
```

这一步只证明 worker Looper 已经有自己的 `Printer`。它不制造 ANR，也不应该替换主 Looper `Printer`。

### 2.2 触发一个已有 ANR 场景

不要使用 `-S`，避免重启进程导致 worker Looper 场景丢失：

```bash
adb shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity \
  --es anr_demo_scenario io_database_file_block
```

等待 logcat 出现报告 ID：

```bash
adb logcat -d -s VibeAnrApplication MainActivity AnrMonitor
```

预期能看到：

```text
VibeAnrApplication: suspect ANR captured: <eventId>
VibeAnrApplication: ANR report written: <eventId>
```

拉取最新报告：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > /tmp/anr-report.json
```

检查报告：

```bash
grep -n "mainThread" /tmp/anr-report.json
grep -n "looper_printer_replaced" /tmp/anr-report.json
```

预期：

- `mainThread.current` 或 `mainThread.history` 有主线程 Looper 时间线数据。
- 不出现 `looper_printer_replaced`。

验收结论可以写为：worker Looper 的 `Printer` 与主 Looper `Printer` 是不同槽位，多个 Looper 并不会让 SDK 主 Looper 采集天然失效。

## 3. 验证主 Looper Printer 被替换

### 3.1 触发主 Looper Printer 替换

方式一：在 Demo 页面点击“主 Looper Printer 被替换”。

方式二：使用 adb intent：

```bash
adb shell am start -S -n com.valiantyan.vibeanrmonitoring/.MainActivity \
  --es anr_demo_scenario looper_main_printer_replaced
```

检查 logcat：

```bash
adb logcat -d -s MainActivity LooperPrinterProbe DemoThirdPartyPrinter VibeAnrApplication
```

预期能看到：

```text
LooperPrinterProbe: main looper printer replaced by demo third-party printer
```

这一步模拟第三方 SDK 在当前 SDK 安装后再次调用 `Looper.getMainLooper().setMessageLogging(...)`。

### 3.2 触发一个已有 ANR 场景

不要使用 `-S`，避免重启进程导致主 Looper 替换状态丢失：

```bash
adb shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity \
  --es anr_demo_scenario io_database_file_block
```

拉取报告：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > /tmp/anr-report.json
```

检查冲突指标：

```bash
grep -n "looper_printer_replaced" /tmp/anr-report.json
grep -n "sdkDiagnostics" /tmp/anr-report.json
```

预期能看到：

```json
"looper_printer_replaced":1
```

验收结论可以写为：后装第三方接管主 Looper `Printer` 后，SDK 不默认抢回单槽位，但会在疑似 ANR 报告的 `sdkDiagnostics.selfMetrics` 中记录替换事件。

## 4. 常见误区

| 现象 | 解释 |
| --- | --- |
| worker Looper 有自己的 Printer，但报告没有 `looper_printer_replaced` | 正常。不同 Looper 是不同槽位，不代表主 Looper 被抢占 |
| 点击“主 Looper Printer 被替换”后，主线程时间线变少或为空 | 可接受。SDK 的主 Looper Printer 已被后装 Printer 替换，当前 P0 策略只诊断不抢回 |
| 第二步触发 ANR 时用了 `am start -S` | `-S` 会重启进程，前一步安装的 worker Printer 或主 Looper 替换状态会丢失 |
| 没有报告文件 | 先看 `VibeAnrApplication` 日志，确认是否出现 `ANR report written`，并确认场景阻塞时间超过 Demo 的 `suspectAnrMs=3000` |

## 5. 验收清单

- [ ] “多 Looper Printer 验证”后，logcat 出现 `DemoWorkerLooper`。
- [ ] 随后触发 `io_database_file_block`，报告正常生成。
- [ ] 多 Looper 报告中不出现 `looper_printer_replaced`。
- [ ] “主 Looper Printer 被替换”后，logcat 出现 `LooperPrinterProbe: main looper printer replaced...`。
- [ ] 随后触发 `io_database_file_block`，报告中出现 `looper_printer_replaced`。
- [ ] 全流程没有出现 SDK 默认抢回主 Looper `Printer` 的行为。
