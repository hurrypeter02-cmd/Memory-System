# Memory Timeline API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable Java 21 MVP for the Memory Timeline API described in `docs/superpowers/specs/2026-04-29-memory-timeline-api-design.md`.

**Architecture:** Use pure JDK 21 to avoid missing Maven/Gradle dependencies. The app exposes HTTP endpoints with `com.sun.net.httpserver.HttpServer`, stores documents/fragments/events in memory, and uses deterministic extraction rules so tests are stable and repeatable.

**Tech Stack:** Java 21, JDK `HttpServer`, JDK `HttpClient`, custom lightweight JSON parser/writer, self-contained Java test runner.

---

## File Structure

- `README.md`: project overview plus build, test, and API usage commands.
- `src/main/java/com/memorysystem/App.java`: application entry point.
- `src/main/java/com/memorysystem/api/MemoryHttpServer.java`: HTTP routing, request parsing, and JSON responses.
- `src/main/java/com/memorysystem/model/DocumentSource.java`: imported source model.
- `src/main/java/com/memorysystem/model/SourceFragment.java`: source fragment model.
- `src/main/java/com/memorysystem/model/MemoryEvent.java`: extracted memory event model.
- `src/main/java/com/memorysystem/model/EventRelation.java`: event relation model.
- `src/main/java/com/memorysystem/model/TimelineItem.java`: timeline response item.
- `src/main/java/com/memorysystem/service/MemoryService.java`: import, extract, timeline, query, and source trace logic.
- `src/main/java/com/memorysystem/util/Json.java`: minimal JSON parser and serializer for API payloads.
- `src/main/java/com/memorysystem/util/HttpUtil.java`: path, query, and response helpers.
- `src/test/java/com/memorysystem/TestRunner.java`: self-contained tests for service and HTTP API.

## Verification Command

Run from repository root:

```powershell
$files = Get-ChildItem -Recurse src\main\java,src\test\java -Filter *.java | ForEach-Object { $_.FullName }
if (Test-Path out) { Remove-Item -Recurse -Force out }
javac -encoding UTF-8 -d out\test $files
java -cp out\test com.memorysystem.TestRunner
```

Expected final output:

```text
All tests passed.
```

## Task 1: Model And JSON Foundation

**Files:**
- Create: `src/main/java/com/memorysystem/model/DocumentSource.java`
- Create: `src/main/java/com/memorysystem/model/SourceFragment.java`
- Create: `src/main/java/com/memorysystem/model/MemoryEvent.java`
- Create: `src/main/java/com/memorysystem/model/EventRelation.java`
- Create: `src/main/java/com/memorysystem/model/TimelineItem.java`
- Create: `src/main/java/com/memorysystem/util/Json.java`
- Test: `src/test/java/com/memorysystem/TestRunner.java`

- [ ] **Step 1: Write failing JSON/model tests**

Add tests that parse nested JSON, serialize maps/lists, and create immutable model objects with required fields.

- [ ] **Step 2: Run test to verify it fails**

Run the verification command. Expected: compilation fails because model and JSON classes do not exist.

- [ ] **Step 3: Implement minimal model records and JSON parser/writer**

Create Java records for the data model. Implement JSON support for strings, numbers, booleans, null, arrays, and objects.

- [ ] **Step 4: Run test to verify it passes**

Run the verification command. Expected: JSON/model tests pass.

## Task 2: Memory Service

**Files:**
- Create: `src/main/java/com/memorysystem/service/MemoryService.java`
- Modify: `src/test/java/com/memorysystem/TestRunner.java`

- [ ] **Step 1: Write failing service tests**

Cover source import, fragment creation, deterministic extraction, time/type/status filtering, source trace, and insufficient-evidence query.

- [ ] **Step 2: Run test to verify it fails**

Run the verification command. Expected: compilation fails because `MemoryService` does not exist.

- [ ] **Step 3: Implement service**

Use in-memory maps. Split source content by non-empty lines into fragments. Extract events from lines shaped like:

```text
2026-04-10T10:30:00+08:00 | decision | active | 确定 MVP 范围 | 第一版聚焦项目演进时间线。
```

Fallback rules:

- Missing/invalid time -> `occurred_at = null`, `time_precision = unknown`, `status = uncertain`.
- Unknown event type -> `event`.
- Unknown status -> `uncertain`.
- Query with no matching event -> answer `资料中未找到充分依据`.

- [ ] **Step 4: Run test to verify it passes**

Run the verification command. Expected: service tests pass.

## Task 3: HTTP API

**Files:**
- Create: `src/main/java/com/memorysystem/api/MemoryHttpServer.java`
- Create: `src/main/java/com/memorysystem/util/HttpUtil.java`
- Create: `src/main/java/com/memorysystem/App.java`
- Modify: `src/test/java/com/memorysystem/TestRunner.java`

- [ ] **Step 1: Write failing HTTP tests**

Start the server on port `0` and cover:

- `POST /projects/{project_id}/sources`
- `POST /projects/{project_id}/memory/extract`
- `GET /projects/{project_id}/timeline`
- `POST /projects/{project_id}/query`
- `GET /memory/events/{event_id}/sources`

- [ ] **Step 2: Run test to verify it fails**

Run the verification command. Expected: compilation fails because HTTP classes do not exist.

- [ ] **Step 3: Implement HTTP server**

Route exact endpoint patterns, return JSON with the API contract fields, use HTTP 404 for unknown routes, 400 for invalid payloads, and UTF-8 responses.

- [ ] **Step 4: Run test to verify it passes**

Run the verification command. Expected: all HTTP tests pass.

## Task 4: Documentation And Final Verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document commands and payload examples**

Document compile/test/run commands and one example API workflow.

- [ ] **Step 2: Run full verification**

Run the verification command. Expected: `All tests passed.`

