# lark-cli Memory Windows 交付包

本目录是面向 Memory 系统接入飞书的 Windows 本地交付包。交付包基于现有 `lark-cli` 构建，不重写 CLI 核心逻辑。

## 目录内容

- `lark-cli.exe`：从当前仓库构建出的 Windows 可执行文件。
- `README.md`：本交付包使用说明。
- `verify.ps1`：本地验收脚本，用于验证 help、schema 和 dry-run。
- `execution-review.zh-CN.md`：本轮执行思路与结果，供审查使用。
- `examples/`：dry-run 示例命令和 Memory 进程调用契约。

## 本地验收

在本交付目录中执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1
```

验收脚本会检查：

- `lark-cli.exe --help`
- `lark-cli.exe schema --help`
- `docs`、`drive`、`wiki` 相关 dry-run 命令
- 使用临时占位 App 信息和隔离的临时 CLI 配置目录

验收脚本不会执行登录、OAuth 授权、上传、删除、权限修改，也不会发起真实业务 API 调用。

## 手动飞书登录

真实授权仍由用户手动完成。准备好后，在交付目录中执行：

```powershell
.\lark-cli.exe config init --new
.\lark-cli.exe auth login --recommend
.\lark-cli.exe auth status
```

Memory 系统不保存飞书 token，应沿用 `lark-cli` 自身配置与 keychain 行为。

## Memory 进程调用接口

Memory 后端建议以子进程方式调用 `lark-cli.exe`。

- 输入：命令行参数，以及底层命令支持时通过 `--params` 或 `--data` 传入的 JSON。
- 输出：从 stdout 读取 JSON 数据。
- 错误：从 stderr 读取 CLI 错误 envelope 或人类可读诊断信息。
- 凭证：Memory 不读取、不持久化、不转换飞书 token。

调用时应设置超时时间，分别捕获 stdout 和 stderr；日志只记录命令名和必要上下文，不要记录密钥、token 或企业文档内容。

## 首批安全域

- 文档能力使用 CLI 域命令 `docs`。
- 知识库能力使用 `wiki`。
- 云空间能力使用 `drive`。

包内示例均为 dry-run，仅用于验证命令形态和输出结构，不读取真实租户数据。

## 安全边界

- 本交付包不自动登录。
- 本交付包不保存凭证。
- 本交付包不主动读取真实飞书数据。
- 上传、删除、权限修改等写操作不纳入本轮自动验收。
