<!-- gitnexus:start -->
# GitNexus 代码智能上下文

当前项目已经由 GitNexus 建立索引，仓库名为 **Vibe-ANR-Monitoring**。

索引范围已经通过 `.gitnexusignore` 收窄：

- 只包含 `app/src/main` 生产代码和 `app/build.gradle.kts`
- 只包含 `anr-monitor-sdk/src/main` 生产代码和 `anr-monitor-sdk/build.gradle.kts`
- 不包含 `src/test`、`src/androidTest`、`docs-anr`、`SDK案例分析`、`session` 和其他辅助文档

当前索引结果：

- 符号数：1703
- 关系数：3429
- 执行流数：114

后续理解代码、评估影响面、排查问题或做重构时，应优先使用 GitNexus MCP 工具获得代码图谱上下文。

## 必须遵守

- 修改函数、类、方法前，必须先运行 `impact({target: "符号名", direction: "upstream"})`，并向用户说明直接调用方、受影响执行流和风险等级。
- 提交代码前，必须运行 `detect_changes()` 检查当前改动影响范围；做回归对比时使用 `detect_changes({scope: "compare", base_ref: "main"})`。
- 如果影响分析返回 HIGH 或 CRITICAL 风险，必须先提醒用户，再继续修改。
- 探索陌生代码时，优先使用 `query({query: "要理解的概念"})` 查找执行流，再按需读取源文件。
- 需要查看某个符号的调用方、被调用方和参与流程时，使用 `context({name: "符号名"})`。

## 禁止事项

- 不要在未做 `impact` 分析的情况下修改函数、类或方法。
- 不要忽略 HIGH 或 CRITICAL 风险提示。
- 不要用普通全文替换重命名符号；需要重命名时使用 GitNexus 的 `rename`。
- 不要在提交前跳过 `detect_changes()`。

## 常用资源

| 资源 | 用途 |
| --- | --- |
| `gitnexus://repo/Vibe-ANR-Monitoring/context` | 查看仓库概览和索引新鲜度 |
| `gitnexus://repo/Vibe-ANR-Monitoring/clusters` | 查看功能区域聚类 |
| `gitnexus://repo/Vibe-ANR-Monitoring/processes` | 查看全部执行流 |
| `gitnexus://repo/Vibe-ANR-Monitoring/process/{name}` | 查看某个执行流的逐步链路 |

## 技能文件

| 任务 | 阅读文件 |
| --- | --- |
| 理解架构、流程、调用链 | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| 修改前影响面分析 | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| 排查缺陷和异常链路 | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| 重命名、抽取、拆分、迁移代码 | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| 工具、资源、图谱结构参考 | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| 索引、状态、清理、Wiki 命令 | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

## 索引刷新

如果索引过期，在项目根目录执行：

```bash
node .gitnexus/run.cjs analyze
```

如果本地没有 `.gitnexus/run.cjs`，先执行：

```bash
npx gitnexus analyze
```

当前 npm 11 环境可能触发 GitNexus 官方已知的 `npx` 安装问题；如果遇到异常，可以参考 `docs-anr/107-GitNexus安装与使用说明.md`。
<!-- gitnexus:end -->
