# 项目备忘录

## V1 最小 MVP

- Memory 后端采用 Java 21 + JDK HttpServer + 内存存储，不引入 Spring、Maven、Gradle 或数据库。
- 基础 Memory 流程已支持资料导入、规则抽取、时间线查询、问答召回和事件来源追溯。
- 飞书接入采用方案三，由 Memory 后端通过子进程调用 `lark-cli.exe`，不直连飞书 OpenAPI，不保存 token。
- 当前飞书应用读取群消息返回 `b2c app not support`，因此 MVP 可通过手工消息导入接口完成决策记忆闭环验收。
- 项目决策记忆使用约定格式解析 `决策/主题/理由/反对/结论/阶段/时间`，不接入 LLM。
- 决策召回采用关键词匹配，命中后生成历史决策 Markdown 卡片，`send=true` 时才真实调用飞书发送。
- 自动化验证命令为 `javac -encoding UTF-8 -d out\verify-project-memory $files` 后运行 `com.memorysystem.TestRunner`。
