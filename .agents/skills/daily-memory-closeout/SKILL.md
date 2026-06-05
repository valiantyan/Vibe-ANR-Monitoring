---
name: daily-memory-closeout
description: 仅当用户明确要求执行每日 memory 收口时使用，例如“每日 memory 收口”、“使用 daily-memory-closeout”或“run daily memory closeout”。这是当前项目的手动工作流，用于按 memory/WRITE_POLICY.md 清理 memory/brain.md，并把已完成或不再需要占用工作记忆的内容归档到 memory/journal/YYYY-MM-DD.md。
---

# 每日 Memory 收口

## 手动触发

仅在用户明确要求时使用本 skill，例如：

- `每日 memory 收口`
- `使用 daily-memory-closeout`
- `run daily memory closeout`
- 用户粘贴每日 memory 收口提示词并要求执行

普通 memory 讨论、查看、解释或提问时，不自动执行本 skill。没有明确执行指令时，只回答问题，不修改文件。

## 原始提示词

```text
每日 memory 收口：按 memory/WRITE_POLICY.md 清理 memory/brain.md，将不再需要占用工作记忆的内容保主题归档到今天的 memory/journal/YYYY-MM-DD.md；归档保留主题、结论、依据、状态、后续，并保留可检索关键词，如任务编号、功能名、类名、文档名或问题名；删除流水和噪音；brain.md 只保留当前进行中、等待、阻塞和下一步；除非满足准入，否则不写 L2。
```

## 执行流程

1. 先读取 `memory/MEMORY.md`，确认 memory 结构索引。
2. 读取 `memory/WRITE_POLICY.md`，用它判断归档位置和 L2 写入准入。
3. 读取 `memory/brain.md` 和 `memory/projects/android-harness-engineering/context.md`。
4. 读取 `memory/MIND.md`，确认长期认知边界，避免把临时进展写入核心认知。
5. 检查当天日志 `memory/journal/YYYY-MM-DD.md` 是否存在；不存在则创建，已存在则追加新的主题段落，不覆盖已有内容。
6. 从 `memory/brain.md` 提取不再需要占用工作记忆的内容，按主题归档到当天 journal。
7. 归档内容必须保留：主题、结论、依据、状态、后续、关键词。
8. 删除流水、重复和噪音；不要复制用户原话，压缩成事实、状态、阻塞或下一步。
9. 清理 `memory/brain.md`，只保留当前进行中、等待事项、阻塞事项和下一步。
10. 除非满足 `memory/WRITE_POLICY.md` 的 L2 准入，否则不写 `memory/projects/`、`memory/topics/`、`memory/user/` 或 `memory/MIND.md`。
11. 写入后检查 `memory/brain.md` 是否保持在 80 行以内。
12. 最后汇报修改了哪些文件、归档了什么、哪些内容仍留在 `brain.md`、是否写入 L2。

## Journal 模板

```markdown
## <主题名>

### 主题

<一句话说明归档主题和范围。>

### 结论

- <已验证或已完成的最小结论。>

### 依据

- 来源：<brain、计划文档、源码验证、测试结果、用户确认等。>
- 验证：<相关命令、评审、截图或观察。>
- 边界：<尚未验证或不应上升为 L2 的原因。>

### 状态

- 已完成：<完成内容。>
- 仍进行中：<还需要留在 brain 的事项。>
- 未进入 L2：<不满足准入的原因。>

### 后续

- <下一步或等待事项。>

### 关键词

`任务编号`、`功能名`、`类名`、`文档名`、`问题名`
```

## 质量门禁

- 归档不能变成流水账；优先保留对后续任务有价值的结论。
- 同一事实只保留在一个最合适的位置，避免 journal、brain、L2 重复。
- 未验证推断不进入 L2；必要时只保留在 journal 的边界说明里。
- 用户私人长期历史默认不加载、不记录。
- 本项目生成的 Markdown 描述内容使用中文。
