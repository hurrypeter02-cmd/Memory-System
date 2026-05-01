# 版本 1 迭代说明：最小 MVP

## 版本目标

V1 的目标是交付一个可本地运行、可验证、可演示闭环的 Memory 后端 MVP：能够导入资料，抽取项目记忆，查询时间线，并完成飞书项目决策记忆的读取替代、结构化存储、召回和卡片生成。

本版本不做数据库持久化，不引入 Spring/Maven/Gradle，不直接接入飞书 OpenAPI，不由 Memory 后端保存飞书 token。

## 文档来源摘要

- `memory-timeline-api-implementation-plan.md`：定义基础 Memory Timeline API 的 Java 21 纯 JDK 实现方案。
- `API_USAGE.md`：记录基础 Memory API 的构建、测试、运行和导入/抽取/查询用法。
- `FEISHU_INTEGRATION.md`：记录飞书 CLI 接入、项目决策记忆、手工消息导入和召回流程。
- `cli-main/cli-main/scripts/memory-windows/README.zh-CN.md`：记录 `lark-cli.exe` Windows 交付包的使用边界。
- `cli-main/cli-main/scripts/memory-windows/execution-review.zh-CN.md`：记录飞书 CLI Windows 包的打包、验收和安全边界。

## 已添加功能

### 基础 Memory 能力

- 支持通过 HTTP 导入项目资料源。
- 支持从资料内容中按固定格式抽取记忆事件。
- 支持按项目查询时间线。
- 支持基于问题召回相关事件。
- 支持根据事件 ID 追溯来源片段。

### 飞书 CLI 接入能力

- 新增 `LarkCliClient`，通过 `ProcessBuilder` 调用 `lark-cli.exe`。
- 支持默认 CLI 路径 `cli-main\cli-main\dist\lark-cli-memory-windows\lark-cli.exe`。
- 支持通过环境变量 `MEMORY_LARK_CLI_PATH` 覆盖 CLI 路径。
- 统一捕获 `stdout`、`stderr`、`exit_code` 和实际执行命令。
- Memory 后端不读取、不保存、不改写飞书凭证。

### 飞书搜索结果导入能力

- 支持查询飞书 CLI 登录状态。
- 支持调用飞书 CLI 执行 `drive/docs` 搜索。
- 支持把飞书搜索结果或手工内容导入现有 `DocumentSource` 流程。

### 项目决策记忆能力

- 新增项目决策记忆模型，保存决策、理由、反对意见、结论、阶段、时间点、来源消息和关键词。
- 支持自动飞书群消息读取入口：调用 `im +chat-messages-list`。
- 支持手工消息导入入口：用于当前飞书应用不能读取群消息时完成 MVP 验收。
- 支持从约定消息格式中抽取决策记忆。
- 支持用普通讨论文本匹配历史决策。
- 支持生成历史决策 Markdown 卡片。
- 支持 `send=true` 时调用 `im +messages-send` 推送卡片，测试中使用 fake CLI，不触发真实发送。

## 已添加接口

### 基础 Memory API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/projects/{project_id}/sources` | 导入项目资料源 |
| `POST` | `/projects/{project_id}/memory/extract` | 从资料源抽取记忆事件 |
| `GET` | `/projects/{project_id}/timeline` | 查询项目时间线 |
| `POST` | `/projects/{project_id}/query` | 基于问题召回项目记忆 |
| `GET` | `/memory/events/{event_id}/sources` | 查看事件来源片段 |

### 飞书接入 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/integrations/feishu/status` | 查看飞书 CLI 登录/配置状态 |
| `POST` | `/projects/{project_id}/integrations/feishu/search` | 调用飞书 CLI 搜索，默认 `dry_run=true` |
| `POST` | `/projects/{project_id}/integrations/feishu/import` | 把飞书搜索结果或手工内容导入 Memory |

### 项目决策记忆 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/projects/{project_id}/project-memory/feishu/run` | 从飞书群读取消息、抽取决策、匹配讨论、可选推送卡片 |
| `POST` | `/projects/{project_id}/project-memory/messages/import` | 手工导入消息并跑通决策记忆闭环 |
| `GET` | `/projects/{project_id}/project-memory/decisions` | 查看当前项目已存决策记忆 |
| `POST` | `/projects/{project_id}/project-memory/match` | 输入讨论文本，召回历史决策卡片 |

## 跑通的测试

已通过 `src/test/java/com/memorysystem/TestRunner.java` 中的自包含测试，覆盖范围包括：

- JSON 解析和序列化。
- 资料源导入、片段生成、记忆事件抽取。
- 时间线过滤、状态处理、来源追溯。
- 项目问答召回和证据不足场景。
- HTTP 基础 API 工作流和非法请求处理。
- 飞书 CLI 路径解析、环境变量覆盖、stdout/stderr/exitCode 捕获。
- 飞书状态、搜索、导入接口。
- CLI 缺失或参数非法时返回结构化错误。
- 项目决策消息读取、抽取、匹配、卡片预览。
- `send=true` 时发送命令参数生成。
- 发送失败时保留已抽取记忆并返回失败状态。
- 手工消息导入接口完整闭环。

验证命令：

```powershell
New-Item -ItemType Directory -Force -Path out\verify-project-memory | Out-Null
$files = Get-ChildItem -Recurse src\main\java,src\test\java -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d out\verify-project-memory $files
java -cp out\verify-project-memory com.memorysystem.TestRunner
```

期望输出：

```text
All tests passed.
```

## 当前已验证的业务闭环

### 基础 Memory 闭环

```text
资料源导入 -> 规则抽取 -> 时间线查询 -> 问答召回 -> 来源追溯
```

### 飞书项目决策记忆闭环

```text
飞书消息或手工消息 -> 决策结构化抽取 -> 内存存储 -> 后续讨论匹配 -> 历史决策卡片生成
```

### 当前真实环境限制

当前飞书应用调用群消息读取接口时返回：

```text
HTTP 400: The app type is not supported, ext=b2c app not support
```

因此 V1 使用 `/project-memory/messages/import` 作为可交付替代入口，验证核心的决策抽取、存储、召回和卡片生成逻辑。后续如果更换为支持 IM 群消息读取的企业内部应用，现有 `/project-memory/feishu/run` 可以继续复用。

## 后续迭代注意事项

- 如果要读取真实飞书群消息，需要使用支持 IM 权限的企业内部应用，并完成 `im:message.group_msg:get_as_user` 等权限授权。
- `send=true` 会真实调用飞书发送接口，联调阶段默认使用 `send=false`。
- 当前数据只存在内存中，服务重启后项目资料、事件和决策记忆都会丢失。
- 后续如要交付真实项目管理能力，优先增加本地持久化、决策看板和按阶段导出的项目报告。
- 当前抽取依赖固定格式，非格式化自然语言需要后续引入规则增强或 LLM 抽取。
- PowerShell 复杂 JSON 请求建议使用 `Invoke-RestMethod`，避免 `curl.exe` 转义导致 JSON 解析失败。
- 不要在日志、文档或提交内容中保存飞书 token、keychain 信息、企业真实敏感文档内容。
- 修改后必须重新编译并重启 8080 服务，否则新增接口可能返回 `{"error":"not_found"}`。
