# GitNexus 安装与使用说明

## 目标

当前项目接入 GitNexus，用于把代码仓库索引成知识图谱，方便后续通过 MCP 工具查询调用链、影响面、API 路由关系、执行流程和代码结构。

## 安装来源

- 项目地址：https://github.com/abhigyanpatwari/GitNexus
- 当前项目使用方式：CLI 索引 + Codex MCP 查询。

## 项目级配置

仓库根目录新增 `.gitnexusrc`，用于固定当前项目的索引参数：

```json
{
  "defaultBranch": "main",
  "embeddings": false,
  "workerTimeout": 60
}
```

配置说明：

- `defaultBranch`：指定默认对比基线为 `main`。
- `embeddings`：默认不生成向量索引，优先保证本地索引速度和稳定性。
- `workerTimeout`：解析 Kotlin / Java 文件时给 worker 更长等待时间，降低大文件或复杂文件解析超时概率。

仓库根目录新增 `.gitnexusignore`，用于限制代码地图范围：

```gitignore
*

!app/
!app/build.gradle.kts
!app/src/
!app/src/main/
!app/src/main/**

!anr-monitor-sdk/
!anr-monitor-sdk/build.gradle.kts
!anr-monitor-sdk/src/
!anr-monitor-sdk/src/main/
!anr-monitor-sdk/src/main/**
```

范围说明：

- 只索引 `app` 和 `anr-monitor-sdk` 两个模块。
- 只放行两个模块的 `src/main` 生产代码和模块 Gradle 文件。
- 不索引 `src/test`、`src/androidTest`、`docs-anr`、`SDK案例分析`、`session`、构建产物和其他项目辅助文档。

## 安装与索引命令

在仓库根目录执行：

```bash
npx -y gitnexus@latest analyze
```

执行完成后，GitNexus 会把当前仓库加入本机索引列表。索引数据属于本地运行产物，不应该提交到 Git，因此 `.gitignore` 已忽略 `/.gitnexus/`。

当前代码地图索引结果：

- 仓库名：`Vibe-ANR-Monitoring`
- 索引范围：`app/src/main`、`app/build.gradle.kts`、`anr-monitor-sdk/src/main`、`anr-monitor-sdk/build.gradle.kts`
- 节点数：1703
- 边数：3429
- 聚类数：49
- 执行流数：114
- 向量索引：0
- 验证结果：反查 `src/test`、`src/androidTest` 和非 `app/`、`anr-monitor-sdk/` 路径返回空结果。

## Codex MCP 接入

当前项目新增 `.codex/config.toml`，使用项目级 MCP 配置：

```toml
[mcp_servers.gitnexus]
command = "npx"
args = ["-y", "gitnexus@latest", "mcp"]
```

如果当前 Codex 环境还没有 GitNexus MCP，也可以执行全局配置命令：

```bash
codex mcp add gitnexus -- npx -y gitnexus@latest mcp
```

当前会话已经可以看到 `gitnexus` MCP 工具；本次已为当前项目建立索引，并补充项目级 MCP 配置。若当前会话里的 MCP 进程仍然使用旧版本，重启 Codex 会话后会按项目配置加载 `gitnexus@latest`。

## 常用查询方式

索引完成后，可以通过 GitNexus MCP 执行以下操作：

- `list_repos`：查看已经建立索引的仓库。
- `query`：按自然语言查询执行流程和相关符号。
- `context`：查看某个类、函数、方法的调用方、被调用方和所在文件。
- `impact`：分析修改某个符号可能影响哪些调用链。
- `detect_changes`：分析当前未提交改动影响到哪些执行流程。

## 当前项目建议用法

后续开发 ANR 监控 SDK 或 Demo 场景时，可以优先使用 GitNexus 做三类检查：

1. 修改 SDK 核心类前，先用 `impact` 查看上游调用方和受影响流程。
2. 修改 Demo 场景入口前，先用 `query` 搜索已有场景类、按钮接线和文档更新路径。
3. 提交前用 `detect_changes` 检查改动是否影响核心采集、归因、报告写入或 Demo 验收路径。

## 注意事项

- GitNexus 索引是本地开发辅助能力，不改变 APK 行为。
- `.gitnexusrc` 可以提交，用于让团队成员保持一致索引参数。
- `/.gitnexus/` 是本地索引缓存，不提交。
- 如果后续需要更强语义搜索，可以改为执行 `gitnexus analyze --embeddings`，但耗时和内存占用会更高。
- 如果出现 “数据库版本不兼容” 提示，通常是当前 MCP 进程版本旧于索引使用的 GitNexus CLI 版本；重启 Codex 会话，让项目级 `.codex/config.toml` 生效即可。
