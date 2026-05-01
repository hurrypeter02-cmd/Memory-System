# 飞书 CLI Windows 交付包执行思路与结果

## 执行思路

1. 以现有 `lark-cli` 为主体，只做 Windows 本地目录包封装，不重写 CLI 核心。
2. 新增打包脚本，固定生成 `dist/lark-cli-memory-windows/` 目录结构。
3. 新增验收脚本，只验证 `--help`、`schema --help` 和 doc/wiki/drive dry-run。
4. dry-run 使用占位 App 信息和临时 `LARKSUITE_CLI_CONFIG_DIR`，避免读取用户真实配置。
5. Memory 后续通过子进程调用 `lark-cli.exe`，读取 stdout/stderr，不直接管理飞书凭证。
6. 真实登录和真实数据读取保留为用户手动步骤，不在自动验收中执行。

## 本轮完成结果

- 已新增 Windows 打包脚本：`scripts/package-memory-windows.ps1`。
- 已新增本地验收脚本：`scripts/verify-memory-windows.ps1`。
- 已新增脚本契约测试：`scripts/memory-windows-package.test.js`。
- 已生成交付目录：`dist/lark-cli-memory-windows/`。
- 已生成 `lark-cli.exe`、中文 `README.md`、`verify.ps1` 和 `examples/`。
- 已补充本审查文档：`execution-review.zh-CN.md`。

## 验证命令与结果

已执行并通过：

```powershell
node scripts\memory-windows-package.test.js
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package-memory-windows.ps1 -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File dist\lark-cli-memory-windows\verify.ps1
```

已抽查并通过：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File dist\lark-cli-memory-windows\examples\docs-search-dry-run.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File dist\lark-cli-memory-windows\examples\drive-search-dry-run.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File dist\lark-cli-memory-windows\examples\wiki-node-create-dry-run.ps1
```

## 交付边界

- 本轮只交付可执行、可配置、可验证的 Windows 本地包。
- 本轮不做 npm 发布。
- 本轮不自动登录飞书，不保存凭证，不调用真实敏感数据。
- `docs` 命令可用，但长期建议优先把云空间搜索能力落在 `drive` 域上。
- Windows/沙箱下全量 Go 测试存在既有失败，本轮未把全量测试全绿作为阻塞条件。

## 建议审查点

- 是否认可 Memory 后端只依赖 CLI 进程接口，而不直接管理 token。
- 是否认可首批只覆盖 `docs`、`wiki`、`drive` 的 dry-run 验收。
- 是否需要把 `examples/memory-process-contract.md` 也统一改为中文。
- 是否需要下一轮增加 Java 后端调用样例代码。
