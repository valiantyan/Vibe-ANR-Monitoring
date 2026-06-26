# Vibe ANR Monitoring Agent 指南

本文件是给 coding agent 的项目备忘录，补充而不是替代 `README.md`。只写代理修改、验证和提交时真正需要的项目事实、命令和边界；具体 GitNexus 强制规则见下方自动块，不在人工部分重复展开。

用户明确指令优先；如果子目录以后出现更近的 `AGENTS.md`，以更近文件为准。

## 先读这个

- 修复问题时追求根因解决，不要用补丁式改动掩盖症状；如果根治方式改动太大或判断不稳，先向用户说明取舍并询问。
- 先理解再修改：读相关代码、测试、`docs-anr/` 和 GitNexus 结果，确认现有设计意图后再动手。
- 保护用户工作区：不要回滚、格式化或覆盖你没有创建的改动。
- 代码、测试、Demo 场景、JSON 协议和文档是同一交付面。改行为时同步更新能证明该行为的测试或文档。

## 常用命令

优先从仓库根目录运行命令。

| 任务 | 命令 |
| --- | --- |
| SDK 单元测试 | `./gradlew :anr-monitor-sdk:testDebugUnitTest` |
| App 单元测试 | `./gradlew :app:testDebugUnitTest` |
| SDK Kotlin 编译 | `./gradlew :anr-monitor-sdk:compileDebugKotlin` |
| App Kotlin 编译 | `./gradlew :app:compileDebugKotlin` |
| App Debug 构建 | `./gradlew :app:assembleDebug` |
| SDK Release AAR | `./gradlew :anr-monitor-sdk:assembleRelease` |
| 常用本地验收 | `./gradlew :anr-monitor-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` |
| 安装 Demo App | `./gradlew :app:installDebug` |
| 查看任务 | `./gradlew tasks`、`./gradlew :app:tasks`、`./gradlew :anr-monitor-sdk:tasks` |

报告拉取常用命令：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > anr-report.json
```

如果只是改文档，不要为了“完整验证”强行跑 Android 构建；如果改了 Kotlin、Gradle、Manifest、资源或报告协议，至少跑受影响模块的编译或单测。

## 项目地图

- 项目是 Android ANR 监控 SDK 与 Demo 验证工程，核心目标是把 ANR 现场证据整理成可读 JSON，而不是只抓系统 Trace。
- 技术栈：Android Gradle Plugin `8.5.2`，Kotlin `1.9.22`，Java `17`，`compileSdk=35`。
- `:anr-monitor-sdk` 是 SDK 模块，包名 `com.valiantyan.anrmonitor`，`minSdk=22`；`api` 暴露入口，`domain` 放纯模型和归因，`collector` 放 Android 采集，`reporter` 负责 JSON/本地写入/上传重试，`internal` 负责运行时编排。
- `:app` 是 Demo App，包名 `com.valiantyan.vibeanrmonitoring`，`minSdk=23`；`scenario` 目录承载可复现 ANR 场景。
- `docs-anr/01` 到 `docs-anr/04` 是 SDK 基础需求来源；`docs-anr/05-第五篇-告别SharedPreference等待.md` 只是实战复盘样例，不能反推为 SDK 专项能力。
- `SDK案例分析/` 保存真实 JSON 样本和人工分析报告，`dist/` 只放需要交付的本地 AAR。

## 变更联动

- 改 SDK API、配置默认值或接入方式：同步检查 `docs-anr/103-ANR监控SDK使用说明.md`、`docs-anr/111-本地AAR接入与Monkey验收指南.md` 和相关测试。
- 改 JSON 字段、归因码、报告目录或服务端消费语义：同步检查 `docs-anr/102-ANR监控SDK服务端消费协议.md`、`docs-anr/104-ANR监控JSON日志根因排查指南.md`、`FullAcceptanceMatrixTest`。
- 改 Demo 场景、按钮、Manifest、AIDL 或场景入口：同步检查 `README.md`、`docs-anr/105-Demo-ANR场景实现计划.md`、`app/src/test` 和相关案例目录。
- 纯文档改动只需做 Markdown/diff 自检；代码改动按影响范围跑最小但足够的 Gradle 命令。

## 代码约定

- Kotlin 保持现有风格：显式返回类型、优先 `val`、不可变模型、早返回，公开或关键内部 API 写 KDoc。
- `domain` 层不直接依赖 Android framework；Android、反射、隐藏 API、Looper 单槽位、Binder 和文件 I/O 风险隔离在 `collector` 或 `internal`。
- 采集失败不能让宿主崩溃；报告里应写 `available=false`、`failureReason` 或缺失证据。
- 不把 `nativePollOnce` 简化成“主线程空闲”；必须结合 Pending 队列、Barrier 证据和主线程栈判断。
- Binder 只能输出 suspected 级别端侧证据，不能把本地有限证据写成已确认跨进程死锁。

## 先问用户

- 调整 minSdk、targetSdk、compileSdk、AGP/Kotlin 版本或新增依赖。
- 引入 Hook、字节码、native、隐藏 API 强依赖，或扩大反射范围。
- 大幅改变 JSON 协议、归因码、报告目录、上传语义或隐私策略。
- 删除或批量替换 `SDK案例分析/` 中的样本和分析报告。
- 生成或替换 `dist/` 下的交付 AAR。
- 修改 GitNexus 自动生成块内的内容，除非明确是在更新 GitNexus 元数据。

## 不要做

- 不要提交密钥、设备私有日志、`local.properties`、`.gitnexus/`、`build/` 或无关构建产物。
- 不要为了让测试通过而删除失败测试、降低断言、关闭核心采集或吞掉异常。
- 不要把第五篇 SharedPreferences 复盘扩展成 SDK 专项 API、归因码、报告字段、文件扫描或 `QueuedWork` 绕过能力。
- 不要在 `app` 中实现 SDK 逻辑，也不要让 SDK 依赖 Demo。
- 不要回滚用户改动、执行破坏性 git 操作，或用普通 find-and-replace 重命名 Kotlin 符号。
- 保留下面 `gitnexus:start` 到 `gitnexus:end` 的自动块；人工规则放在自动块之外。
- 提交信息必须使用中文总结改动内容，保持简洁明确。(如果是 BUG 修复完毕提交需要写明：问题原因、解决方案)

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **Vibe-ANR-Monitoring** (1540 symbols, 2641 relationships, 0 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/Vibe-ANR-Monitoring/context` | Codebase overview, check index freshness |
| `gitnexus://repo/Vibe-ANR-Monitoring/clusters` | All functional areas |
| `gitnexus://repo/Vibe-ANR-Monitoring/processes` | All execution flows |
| `gitnexus://repo/Vibe-ANR-Monitoring/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
