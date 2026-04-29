# Memory Timeline API Usage

This MVP uses Java 21 and only JDK built-in libraries. Maven and Gradle are not required.

## Build And Test

```powershell
$files = Get-ChildItem -Recurse src\main\java,src\test\java -Filter *.java | ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force -Path out\test | Out-Null
javac -encoding UTF-8 -d out\test $files
java -cp out\test com.memorysystem.TestRunner
```

Expected output:

```text
All tests passed.
```

## Run

```powershell
$files = Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force -Path out\main | Out-Null
javac -encoding UTF-8 -d out\main $files
java -cp out\main com.memorysystem.App 8080
```

The API listens on `http://127.0.0.1:8080`.

## API Workflow

Import a simulated enterprise source:

```powershell
curl.exe -X POST http://127.0.0.1:8080/projects/proj_openclaw/sources `
  -H "Content-Type: application/json; charset=utf-8" `
  -d '{"title":"OpenClaw Memory 系统需求评审纪要","source_type":"meeting_note","content":"2026-04-10T10:30:00+08:00 | decision | active | 确定 MVP 范围 | 第一版聚焦项目演进时间线。"}'
```

Extract memory events:

```powershell
curl.exe -X POST http://127.0.0.1:8080/projects/proj_openclaw/memory/extract `
  -H "Content-Type: application/json; charset=utf-8" `
  -d '{}'
```

Query the timeline:

```powershell
curl.exe "http://127.0.0.1:8080/projects/proj_openclaw/timeline?types=decision"
```

Ask a project-evolution question:

```powershell
curl.exe -X POST http://127.0.0.1:8080/projects/proj_openclaw/query `
  -H "Content-Type: application/json; charset=utf-8" `
  -d '{"question":"MVP 范围为什么这样确定？","include_sources":false}'
```

Set `include_sources` to `true` when the answer should include source documents and fragments inline. The dedicated trace endpoint below can also fetch sources for a specific event.

Trace an event back to source fragments:

```powershell
curl.exe http://127.0.0.1:8080/memory/events/mem_001/sources
```

## Source Format

The first version uses deterministic extraction from one event per line:

```text
occurred_at | event_type | status | subject | summary
```

Example:

```text
2026-04-10T10:30:00+08:00 | decision | active | 确定 MVP 范围 | 第一版聚焦项目演进时间线。
```

Supported event types: `requirement`, `decision`, `event`, `risk`, `delivery`.

Supported statuses: `active`, `superseded`, `cancelled`, `uncertain`.
