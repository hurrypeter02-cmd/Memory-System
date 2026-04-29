# Memory Timeline API Design

日期：2026-04-29

## 背景

本项目希望为企业内的 AI/Agent 提供一套长期记忆系统，让 Agent 不再每次从零开始，而是能够记住项目决策、上下文、用户偏好和团队历史，并在合适场景中主动或按需使用这些历史信息。

第一版不直接接入飞书、知识库、Git 或 CLI，而是使用模拟企业资料验证核心价值：系统能否从历史资料中抽取项目记忆，并准确还原项目演进时间线。

## MVP 目标

第一版聚焦“模拟企业资料 + 项目演进时间线 API”。

核心目标：

- 导入模拟企业资料，例如 PRD、会议纪要、周报、变更说明、决策记录。
- 从资料中抽取结构化记忆事件。
- 基于记忆事件生成稳定、可复现的项目时间线。
- 支持项目演进类问答。
- 默认返回轻量答案，用户追问依据时再返回来源片段。

价值验证标准：

- 能准确还原项目演进时间线。
- 关键需求、决策、风险、交付节点尽量不遗漏。
- 事件顺序、发生时间、类型和状态尽量准确。
- 追问依据时能定位到来源文档和原文片段。

## 设计取舍

采用“结构化记忆抽取 + 时间线 API”的方案作为第一版。

它相比纯 RAG 更稳定：时间线不是每次临时总结出来，而是由持久化的结构化记忆事件生成。它相比完整事件溯源系统更轻：第一版不做完整审计、重放、权限继承和版本治理，但数据模型会把每条记忆视为 `MemoryEvent`，为未来升级到事件溯源架构预留空间。

长期目标是逐步演进到完整事件流能力。第一版不能把未来的事件溯源、冲突检测、版本管理和权限治理堵死。

## 总体架构

第一版 API 服务分为四层。

### 资料层

资料层负责接收模拟企业资料，形成统一的 `DocumentSource` 抽象。

第一版支持本地或测试数据导入，不做真实飞书、知识库、Git、CLI 接入。后续真实接入方都应适配到同一个 `DocumentSource` 接口。

### 记忆抽取层

记忆抽取层从资料中识别混合类型记忆单元，并写入 `MemoryEvent`。

第一版支持的事件类型：

- `requirement`：需求提出、确认、变更、废弃。
- `decision`：方案选择、原因、约束、反对意见。
- `event`：会议、里程碑、项目进展。
- `risk`：风险、阻塞、延期原因。
- `delivery`：交付节点、验收、上线。

每条记忆事件都应包含项目、主题、摘要、发生时间、状态、置信度和来源引用。

### 时间线层

时间线层基于持久化的 `MemoryEvent` 生成 `TimelineView`。

时间线查询支持按项目、时间范围、事件类型、主体和状态过滤。核心要求是输出稳定、可复现，而不是依赖模型每次自由总结。

### 问答层

问答层支持项目演进类问题，例如：

- 这个需求经历了哪些变化？
- 某个方案是什么时候确定的？
- 为什么从方案 A 改成方案 B？
- 当前资料中是否存在互相冲突的结论？

默认回答不展开来源片段，只返回答案、相关事件和时间范围提示。用户追问依据时，再通过来源追溯接口返回文档和片段。

## 核心数据模型

### DocumentSource

表示一份被导入的企业资料。

```json
{
  "source_id": "doc_001",
  "project_id": "proj_openclaw",
  "title": "OpenClaw Memory 系统需求评审纪要",
  "source_type": "meeting_note",
  "created_at": "2026-04-10T10:00:00+08:00",
  "content_hash": "sha256:6f1ed002ab5595859014ebf0951522d9f6a4f0a2b7c8d9e0f11223344556677889900",
  "metadata": {}
}
```

建议的 `source_type`：

- `prd`
- `meeting_note`
- `weekly_report`
- `change_log`
- `decision_record`
- `manual_note`

### SourceFragment

表示一段可被引用的原文证据。

```json
{
  "fragment_id": "frag_001",
  "source_id": "doc_001",
  "text": "团队确认第一版先聚焦项目时间线能力。",
  "location": {
    "page": 2,
    "paragraph": 6
  }
}
```

第一版默认不在问答中展示片段，但必须保留片段索引，支持后续依据追溯。

### MemoryEvent

表示一条结构化记忆事件，是第一版最核心的数据对象。

```json
{
  "event_id": "mem_001",
  "project_id": "proj_openclaw",
  "event_type": "decision",
  "subject": "MVP 范围",
  "summary": "第一版聚焦模拟企业资料下的项目演进时间线。",
  "occurred_at": "2026-04-10T10:30:00+08:00",
  "time_precision": "exact",
  "status": "active",
  "confidence": 0.86,
  "source_refs": ["frag_001", "frag_002"],
  "relations": [
    {
      "relation_type": "updates",
      "target_event_id": "mem_000"
    }
  ]
}
```

第一版固定的 `event_type`：

- `requirement`
- `decision`
- `event`
- `risk`
- `delivery`

第一版固定的 `status`：

- `active`：当前有效或未发现失效。
- `superseded`：已被后续事件明确更新。
- `cancelled`：已被取消。
- `uncertain`：抽取结果不确定，需要弱提示展示或人工确认。

第一版固定的 `time_precision`：

- `exact`：资料中有明确发生时间。
- `date`：只有日期，没有具体时间。
- `document_level`：只能使用文档时间推断。
- `unknown`：无法可靠确定时间。

### 事件关系

`relations` 用来表达记忆事件之间的关系。

第一版至少支持：

- `updates`：后来的事件明确更新或覆盖前面的事件。
- `conflicts_with`：两个事件互相矛盾，但资料不足以判断谁是最终结论。
- `related_to`：两个事件有关联，但没有更新或冲突关系。

示例：

```json
{
  "event_id": "mem_002",
  "summary": "MVP 范围调整为只做文档资料问答和项目时间线。",
  "relations": [
    {
      "relation_type": "updates",
      "target_event_id": "mem_001"
    }
  ]
}
```

`updates` 表示 `mem_002` 明确更新了 `mem_001`，旧事件可被标记为 `superseded`。

`conflicts_with` 表示存在矛盾但无法裁决。例如一份资料写交付时间为 5 月 10 日，另一份资料写交付时间仍按 5 月 15 日推进，而没有最终确认记录。此时系统应保留冲突关系，并在问答中说明存在冲突，不应强行选择一个答案。

### TimelineView

表示由 `MemoryEvent` 聚合出的项目时间线视图。

```json
{
  "project_id": "proj_openclaw",
  "items": [
    {
      "event_id": "mem_001",
      "occurred_at": "2026-04-10T10:30:00+08:00",
      "event_type": "decision",
      "title": "确定 MVP 范围",
      "summary": "第一版聚焦项目演进时间线。",
      "status": "active"
    }
  ]
}
```

`TimelineView` 不是原始事实，而是查询结果。原始事实以 `MemoryEvent` 和 `SourceFragment` 为准。

## API 契约

### 导入资料

```http
POST /projects/{project_id}/sources
```

用途：导入模拟企业资料。

响应：

```json
{
  "source_id": "doc_001",
  "project_id": "proj_openclaw",
  "status": "parsed"
}
```

### 触发记忆抽取

```http
POST /projects/{project_id}/memory/extract
```

用途：从已导入资料中抽取 `MemoryEvent`。

第一版可同步返回结果；后续可扩展为异步任务。

响应：

```json
{
  "project_id": "proj_openclaw",
  "created_events": 24,
  "uncertain_events": 3
}
```

### 查询项目时间线

```http
GET /projects/{project_id}/timeline?from=2026-04-01&to=2026-04-30&types=decision,requirement
```

用途：返回稳定的项目演进时间线。

该接口是第一版价值验证主接口。

响应：

```json
{
  "project_id": "proj_openclaw",
  "items": [
    {
      "event_id": "mem_001",
      "occurred_at": "2026-04-10T10:30:00+08:00",
      "event_type": "decision",
      "title": "确定 MVP 范围",
      "summary": "第一版聚焦项目演进时间线。",
      "status": "active"
    }
  ]
}
```

### 项目记忆问答

```http
POST /projects/{project_id}/query
```

请求：

```json
{
  "question": "这个需求为什么从方案 A 改成方案 B？",
  "include_sources": false
}
```

响应：

```json
{
  "answer": "该需求从 A 改为 B，主要因为后续评审确认 A 的集成成本过高，B 更适合作为第一版范围。",
  "related_events": ["mem_001", "mem_004"],
  "timeline_hint": {
    "from": "2026-04-01",
    "to": "2026-04-15"
  }
}
```

当资料中没有充分依据时，系统应明确说明“资料中未找到充分依据”，不要生成看似确定的结论。

### 追溯依据

```http
GET /memory/events/{event_id}/sources
```

用途：返回某个记忆事件对应的来源文档和原文片段。

响应：

```json
{
  "event_id": "mem_001",
  "sources": [
    {
      "source_id": "doc_001",
      "title": "OpenClaw Memory 系统需求评审纪要",
      "fragments": [
        {
          "fragment_id": "frag_001",
          "text": "团队确认第一版先聚焦项目时间线能力。",
          "location": {
            "page": 2,
            "paragraph": 6
          }
        }
      ]
    }
  ]
}
```

## 核心数据流

1. 导入模拟企业资料。
2. 解析资料，生成 `DocumentSource`。
3. 切分资料，生成 `SourceFragment`。
4. 记忆抽取器识别 `MemoryEvent`。
5. 时间线接口从 `MemoryEvent` 生成 `TimelineView`。
6. 问答接口先检索相关 `MemoryEvent`，再按需读取 `SourceFragment`。
7. 用户追问依据时，通过来源追溯接口返回文档片段。

## 错误处理与边界情况

### 时间不确定

如果资料中没有明确发生时间，不应强行编造。

处理方式：

- 可以使用文档时间作为弱推断。
- 设置 `time_precision` 为 `document_level`。
- 如果完全无法判断，设置 `time_precision` 为 `unknown`，并将事件标记为 `uncertain`。

### 信息冲突

如果新资料明确覆盖旧结论：

- 新事件建立 `updates` 关系。
- 旧事件标记为 `superseded`。

如果资料互相矛盾但无法判断最终结论：

- 事件之间建立 `conflicts_with` 关系。
- 问答时说明存在冲突和对应来源。
- 第一版不自动裁决。

### 来源不足

如果没有找到充分证据，问答层必须明确说明资料不足，不生成确定结论。

### 置信度低

低置信度事件可标记为 `uncertain`。时间线查询可以支持参数决定是否展示不确定事件。

## 第一版不做

- 不做真实飞书、知识库、Git、CLI 接入。
- 不做复杂权限系统，只在模型上预留 `tenant_id`、`visibility`、`allowed_principals` 等字段扩展位。
- 不做主动推送，只提供 API 查询能力。
- 不做完整知识图谱。
- 不做完整事件溯源的重放、撤销、审计和版本治理。
- 不做自动最终裁决，冲突只识别和呈现。

## 后续演进

第一版完成后，可以逐步升级到更接近方案 3 的事件溯源系统：

- 将 `MemoryEvent` 变成不可变事件流。
- 增加事件版本、撤销、重放和审计。
- 引入权限继承和多租户隔离。
- 接入飞书、文档、Git、CLI 等真实来源。
- 增加主动提醒机制。
- 将轻量事件关系升级为时序知识图谱。
- 增加冲突检测、旧结论失效提醒和人工确认工作流。

## 测试与评测

第一版需要准备一批模拟企业资料，并人工标注标准时间线。

评测维度：

- 关键节点召回率：重要需求、决策、风险、交付节点是否遗漏。
- 时间准确性：事件顺序和发生时间是否正确。
- 类型准确性：`requirement`、`decision`、`event`、`risk`、`delivery` 分类是否合理。
- 状态准确性：`active`、`superseded`、`cancelled`、`uncertain` 是否合理。
- 证据可追溯性：追问依据时是否能找到对应来源片段。

建议准备固定评测问题：

- 这个项目从立项到当前经历了哪些关键变化？
- 第一版 MVP 范围是如何确定的？
- 哪些需求被调整或废弃过？
- 项目中出现过哪些风险，后来如何处理？
- 当前资料中是否存在互相冲突的结论？

## 验收标准

第一版可以被认为完成，当它满足以下条件：

- 能导入一组模拟企业资料。
- 能生成 `DocumentSource` 和 `SourceFragment`。
- 能抽取混合类型 `MemoryEvent`。
- 能返回按时间排序的项目时间线。
- 能回答项目演进类问题。
- 能在追问依据时返回来源片段。
- 遇到冲突、不确定时间、来源不足时能明确表达边界。
- 数据模型支持未来演进到事件溯源系统，而不需要推倒重来。
