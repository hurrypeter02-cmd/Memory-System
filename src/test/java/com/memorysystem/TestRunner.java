package com.memorysystem;

import com.memorysystem.api.MemoryHttpServer;
import com.memorysystem.integration.feishu.FeishuIntegrationService;
import com.memorysystem.integration.feishu.LarkCliClient;
import com.memorysystem.model.DocumentSource;
import com.memorysystem.model.MemoryEvent;
import com.memorysystem.model.ProjectDecisionMemory;
import com.memorysystem.model.ProjectMemoryRunResult;
import com.memorysystem.model.SourceFragment;
import com.memorysystem.model.TimelineItem;
import com.memorysystem.service.MemoryService;
import com.memorysystem.service.ProjectMemoryService;
import com.memorysystem.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TestRunner {
    public static void main(String[] args) throws Exception {
        testJsonRoundTrip();
        testServiceImportsExtractsAndQueriesTimeline();
        testServiceHandlesUncertainAndInsufficientEvidence();
        testServiceAvoidsWeakQueryMatchesAndCanInlineSources();
        testHttpApiWorkflow();
        testHttpRejectsInvalidRequests();
        testLarkCliClientCapturesOutputAndResolvesPath();
        testFeishuIntegrationSearchAndImport();
        testHttpFeishuIntegrationWorkflow();
        testHttpFeishuIntegrationValidationAndMissingCli();
        testProjectMemoryReadsExtractsMatchesAndPreviews();
        testProjectMemorySendsDecisionCard();
        testProjectMemoryReportsSendFailure();
        testHttpProjectMemoryWorkflowAndValidation();
        testHttpProjectMemoryManualMessageImportWorkflow();
        testHttpProjectMemoryRejectsInvalidNumberParameters();
        System.out.println("All tests passed.");
    }

    private static void testJsonRoundTrip() {
        Object parsed = Json.parse("{\"title\":\"OpenClaw\",\"count\":2,\"items\":[true,null,\"x\"]}");
        Map<?, ?> object = castMap(parsed);
        assertEquals("OpenClaw", object.get("title"), "json title");
        assertEquals(2, ((Number) object.get("count")).intValue(), "json number");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("name", "Memory");
        assertEquals("{\"ok\":true,\"name\":\"Memory\"}",
                Json.stringify(response),
                "json stringify");
    }

    private static void testServiceImportsExtractsAndQueriesTimeline() {
        MemoryService service = new MemoryService();
        String content = """
                2026-04-10T10:30:00+08:00 | decision | active | 确定 MVP 范围 | 第一版聚焦项目演进时间线。
                2026-04-11T09:00:00+08:00 | risk | active | 集成风险 | 飞书接入推迟到后续版本。
                2026-04-12 | delivery | active | 完成样例资料 | 交付模拟企业资料。
                """;

        DocumentSource source = service.importSource("proj_openclaw", Map.of(
                "title", "OpenClaw Memory 系统需求评审纪要",
                "source_type", "meeting_note",
                "content", content
        ));
        assertEquals("proj_openclaw", source.projectId(), "source project");

        MemoryService.ExtractionResult result = service.extract("proj_openclaw");
        assertEquals(3, result.createdEvents(), "created events");
        assertEquals(0, result.uncertainEvents(), "uncertain events");

        List<TimelineItem> decisions = service.timeline("proj_openclaw",
                "2026-04-01", "2026-04-30", "decision,requirement", null, null);
        assertEquals(1, decisions.size(), "decision timeline size");
        assertEquals("decision", decisions.getFirst().eventType(), "timeline type");
        assertEquals("确定 MVP 范围", decisions.getFirst().title(), "timeline title");

        MemoryService.QueryResult answer = service.query("proj_openclaw", "为什么 MVP 范围这样确定？", false);
        assertTrue(answer.answer().contains("第一版聚焦项目演进时间线"), "query answer uses event evidence");
        assertEquals(1, answer.relatedEvents().size(), "query related events");

        List<MemoryService.SourceTrace> traces = service.sourcesForEvent(answer.relatedEvents().getFirst());
        assertEquals(1, traces.size(), "source trace size");
        assertEquals("OpenClaw Memory 系统需求评审纪要", traces.getFirst().source().title(), "trace source title");
        assertEquals(1, traces.getFirst().fragments().size(), "trace fragments size");
    }

    private static void testServiceHandlesUncertainAndInsufficientEvidence() {
        MemoryService service = new MemoryService();
        service.importSource("proj_openclaw", Map.of(
                "title", "风险记录",
                "source_type", "manual_note",
                "content", "未记录日期 | strange | unknown | 时间缺失 | 需要人工确认。"
        ));
        MemoryService.ExtractionResult result = service.extract("proj_openclaw");
        assertEquals(1, result.createdEvents(), "created uncertain event");
        assertEquals(1, result.uncertainEvents(), "uncertain count");

        List<TimelineItem> timeline = service.timeline("proj_openclaw", null, null, null, null, "uncertain");
        assertEquals(1, timeline.size(), "uncertain timeline size");
        assertEquals("uncertain", timeline.getFirst().status(), "uncertain status");

        MemoryService.QueryResult answer = service.query("proj_openclaw", "这个项目是否已经上线？", false);
        assertEquals("资料中未找到充分依据", answer.answer(), "insufficient evidence answer");
    }

    private static void testServiceAvoidsWeakQueryMatchesAndCanInlineSources() {
        MemoryService service = new MemoryService();
        service.importSource("proj_openclaw", Map.of(
                "title", "需求评审",
                "source_type", "meeting_note",
                "content", "2026-04-10T10:30:00+08:00 | decision | active | 确定 MVP 范围 | 第一版聚焦项目演进时间线。"
        ));
        service.extract("proj_openclaw");

        MemoryService.QueryResult weakAnswer = service.query("proj_openclaw", "项目预算是多少？", false);
        assertEquals("资料中未找到充分依据", weakAnswer.answer(), "weak query should not match only common words");

        MemoryService.QueryResult answerWithSources = service.query("proj_openclaw", "MVP 范围", true);
        assertEquals(1, answerWithSources.sources().size(), "inline sources are included when requested");
        assertEquals(1, answerWithSources.sources().getFirst().fragments().size(), "inline source fragments");
    }

    private static void testHttpApiWorkflow() throws Exception {
        MemoryService service = new MemoryService();
        MemoryHttpServer server = MemoryHttpServer.start(0, service);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            String importBody = Json.stringify(Map.of(
                    "title", "OpenClaw Memory 系统需求评审纪要",
                    "source_type", "meeting_note",
                    "content", "2026-04-10T10:30:00+08:00 | decision | active | 确定 MVP 范围 | 第一版聚焦项目演进时间线。"
            ));
            Map<?, ?> importResponse = postJson(client, base + "/projects/proj_openclaw/sources", importBody);
            assertEquals("proj_openclaw", importResponse.get("project_id"), "http import project");
            assertEquals("parsed", importResponse.get("status"), "http import status");

            Map<?, ?> extractResponse = postJson(client, base + "/projects/proj_openclaw/memory/extract", "{}");
            assertEquals(1, ((Number) extractResponse.get("created_events")).intValue(), "http extract count");

            Map<?, ?> timelineResponse = getJson(client, base + "/projects/proj_openclaw/timeline?types=decision");
            List<?> items = castList(timelineResponse.get("items"));
            assertEquals(1, items.size(), "http timeline count");
            String eventId = (String) castMap(items.getFirst()).get("event_id");

            Map<?, ?> queryResponse = postJson(client, base + "/projects/proj_openclaw/query",
                    "{\"question\":\"MVP 范围\",\"include_sources\":true}");
            assertTrue(((String) queryResponse.get("answer")).contains("第一版聚焦项目演进时间线"), "http query answer");
            assertEquals(1, castList(queryResponse.get("sources")).size(), "http query inline sources");

            Map<?, ?> lightweightQueryResponse = postJson(client, base + "/projects/proj_openclaw/query",
                    "{\"question\":\"MVP 范围\",\"include_sources\":false}");
            assertTrue(!lightweightQueryResponse.containsKey("sources"), "http query omits sources by default");

            Map<?, ?> sourcesResponse = getJson(client, base + "/memory/events/" + eventId + "/sources");
            assertEquals(eventId, sourcesResponse.get("event_id"), "http sources event id");
            assertEquals(1, castList(sourcesResponse.get("sources")).size(), "http sources count");
        } finally {
            server.stop();
        }
    }

    private static void testHttpRejectsInvalidRequests() throws Exception {
        MemoryHttpServer server = MemoryHttpServer.start(0, new MemoryService());
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> missingContent = postRaw(client,
                    base + "/projects/proj_openclaw/sources",
                    "{\"title\":\"需求评审\",\"source_type\":\"meeting_note\"}");
            assertEquals(400, missingContent.statusCode(), "missing content returns 400");

            HttpResponse<String> missingTitle = postRaw(client,
                    base + "/projects/proj_openclaw/sources",
                    "{\"source_type\":\"meeting_note\",\"content\":\"2026-04-10 | event | active | 会议 | 讨论范围\"}");
            assertEquals(400, missingTitle.statusCode(), "missing title returns 400");

            HttpResponse<String> missingSourceType = postRaw(client,
                    base + "/projects/proj_openclaw/sources",
                    "{\"title\":\"需求评审\",\"content\":\"2026-04-10 | event | active | 会议 | 讨论范围\"}");
            assertEquals(400, missingSourceType.statusCode(), "missing source_type returns 400");

            HttpResponse<String> invalidDate = getRaw(client,
                    base + "/projects/proj_openclaw/timeline?from=not-a-date");
            assertEquals(400, invalidDate.statusCode(), "invalid timeline date returns 400");

            HttpResponse<String> invalidToDate = getRaw(client,
                    base + "/projects/proj_openclaw/timeline?to=not-a-date");
            assertEquals(400, invalidToDate.statusCode(), "invalid timeline to date returns 400");

            HttpResponse<String> unknownRoute = getRaw(client, base + "/unknown");
            assertEquals(404, unknownRoute.statusCode(), "unknown route returns 404");
        } finally {
            server.stop();
        }
    }

    private static void testLarkCliClientCapturesOutputAndResolvesPath() throws Exception {
        Path fakeCli = createFakeCli(7);
        LarkCliClient client = new LarkCliClient(fakeCli);
        LarkCliClient.Result result = client.run(List.of("auth", "status"));

        assertEquals(7, result.exitCode(), "fake CLI exit code");
        assertTrue(result.stdout().contains("stdout:auth status"), "fake CLI stdout");
        assertTrue(result.stderr().contains("stderr:auth status"), "fake CLI stderr");

        Path override = Path.of("custom-lark-cli.exe");
        assertEquals(override, LarkCliClient.resolveCliPath(
                Map.of("MEMORY_LARK_CLI_PATH", override.toString()),
                Path.of("ignored")),
                "env override path");

        Path base = Path.of("memory-root");
        Path defaultPath = LarkCliClient.resolveCliPath(Map.of(), base);
        assertEquals(base.resolve(Path.of("cli-main", "cli-main", "dist",
                "lark-cli-memory-windows", "lark-cli.exe")), defaultPath, "default CLI path");
    }

    private static void testFeishuIntegrationSearchAndImport() throws Exception {
        MemoryService memory = new MemoryService();
        FeishuIntegrationService feishu = new FeishuIntegrationService(memory, new LarkCliClient(createFakeCli(0)));

        FeishuIntegrationService.CliResponse status = feishu.status();
        assertEquals(0, status.exitCode(), "feishu status exit code");
        assertTrue(status.configured(), "feishu status configured");

        FeishuIntegrationService.CliResponse search = feishu.search(Map.of("query", "memory"));
        assertEquals(0, search.exitCode(), "feishu search exit code");
        assertTrue(search.stdout().contains("drive +search --query memory --dry-run --as user"),
                "feishu search defaults to drive dry-run");

        DocumentSource source = feishu.importSearchResult("proj_feishu", Map.of(
                "title", "飞书资料导入",
                "content", "2026-05-01 | event | active | 飞书资料接入 | 已通过 CLI 搜索结果导入 Memory。",
                "metadata", Map.of("domain", "drive")
        ));
        assertEquals("feishu_search", source.sourceType(), "default source type");

        memory.extract("proj_feishu");
        List<TimelineItem> timeline = memory.timeline("proj_feishu", null, null, "event", null, null);
        assertEquals(1, timeline.size(), "feishu import timeline size");
        assertEquals("飞书资料接入", timeline.getFirst().title(), "feishu import timeline title");
    }

    private static void testHttpFeishuIntegrationWorkflow() throws Exception {
        MemoryService memory = new MemoryService();
        FeishuIntegrationService feishu = new FeishuIntegrationService(memory, new LarkCliClient(createFakeCli(0)));
        MemoryHttpServer server = MemoryHttpServer.start(0, memory, feishu);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            Map<?, ?> status = getJson(client, base + "/integrations/feishu/status");
            assertEquals(0, ((Number) status.get("exit_code")).intValue(), "http feishu status exit code");
            assertEquals(Boolean.TRUE, status.get("configured"), "http feishu status configured");

            Map<?, ?> search = postJson(client, base + "/projects/proj_feishu/integrations/feishu/search",
                    "{\"domain\":\"drive\",\"query\":\"memory\",\"dry_run\":true}");
            assertEquals("proj_feishu", search.get("project_id"), "http feishu search project");
            assertTrue(((String) search.get("stdout")).contains("drive +search --query memory --dry-run --as user"),
                    "http feishu search stdout");

            Map<?, ?> imported = postJson(client, base + "/projects/proj_feishu/integrations/feishu/import",
                    "{\"title\":\"飞书资料导入\",\"source_type\":\"feishu_search\",\"content\":\"2026-05-01 | event | active | 飞书资料接入 | 已通过 CLI 搜索结果导入 Memory。\"}");
            assertEquals("proj_feishu", imported.get("project_id"), "http feishu import project");
            assertEquals("parsed", imported.get("status"), "http feishu import status");

            postJson(client, base + "/projects/proj_feishu/memory/extract", "{}");
            Map<?, ?> timelineResponse = getJson(client, base + "/projects/proj_feishu/timeline?types=event");
            List<?> items = castList(timelineResponse.get("items"));
            assertEquals(1, items.size(), "http feishu timeline count");
            assertEquals("飞书资料接入", castMap(items.getFirst()).get("title"), "http feishu timeline title");
        } finally {
            server.stop();
        }
    }

    private static void testHttpFeishuIntegrationValidationAndMissingCli() throws Exception {
        MemoryService memory = new MemoryService();
        FeishuIntegrationService feishu = new FeishuIntegrationService(memory,
                new LarkCliClient(Path.of("missing-lark-cli.exe")));
        MemoryHttpServer server = MemoryHttpServer.start(0, memory, feishu);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> invalidDomain = postRaw(client,
                    base + "/projects/proj_feishu/integrations/feishu/search",
                    "{\"domain\":\"wiki\",\"query\":\"memory\"}");
            assertEquals(400, invalidDomain.statusCode(), "invalid feishu domain returns 400");

            HttpResponse<String> missingQuery = postRaw(client,
                    base + "/projects/proj_feishu/integrations/feishu/search",
                    "{\"domain\":\"drive\"}");
            assertEquals(400, missingQuery.statusCode(), "missing feishu query returns 400");

            HttpResponse<String> missingContent = postRaw(client,
                    base + "/projects/proj_feishu/integrations/feishu/import",
                    "{\"title\":\"飞书资料导入\"}");
            assertEquals(400, missingContent.statusCode(), "missing feishu import content returns 400");

            Map<?, ?> status = getJson(client, base + "/integrations/feishu/status");
            assertEquals(-1, ((Number) status.get("exit_code")).intValue(), "missing CLI exit code");
            assertEquals(Boolean.FALSE, status.get("configured"), "missing CLI configured");
            assertTrue(((String) status.get("stderr")).contains("CLI not found"), "missing CLI stderr");
        } finally {
            server.stop();
        }
    }

    private static void testProjectMemoryReadsExtractsMatchesAndPreviews() throws Exception {
        FeishuIntegrationService feishu = new FeishuIntegrationService(
                new MemoryService(),
                new LarkCliClient(createProjectMemoryFakeCli(0, 0)));
        ProjectMemoryService projectMemory = new ProjectMemoryService(feishu);

        ProjectMemoryRunResult result = projectMemory.runFromFeishu("proj_pm", Map.of(
                "chat_id", "oc_demo",
                "start", "2026-05-01T00:00:00+08:00",
                "end", "2026-05-01T23:59:59+08:00",
                "page_size", 50,
                "send", false,
                "max_cards", 3
        ));

        assertEquals(2, result.messagesRead(), "project memory messages read");
        assertEquals(1, result.decisionsExtracted(), "project memory decisions extracted");
        assertEquals(1, result.matchesTriggered(), "project memory matches triggered");
        assertEquals(1, result.cards().size(), "project memory preview cards");
        assertEquals(Boolean.FALSE, result.cards().getFirst().sent(), "send=false previews only");
        assertTrue(result.cards().getFirst().command().isEmpty(), "send=false does not call send command");

        List<ProjectDecisionMemory> decisions = projectMemory.decisions("proj_pm");
        assertEquals(1, decisions.size(), "project memory decision list");
        ProjectDecisionMemory decision = decisions.getFirst();
        assertEquals("msg_decision", decision.sourceMessageId(), "decision source message id");
        assertEquals("oc_demo", decision.sourceChatId(), "decision source chat id");
        assertEquals("\u6280\u672f\u65b9\u6848", decision.subject(), "decision subject");
        assertEquals("\u4f7f\u7528\u65b9\u6848 B", decision.decision(), "decision text");
        assertEquals("\u96c6\u6210\u6210\u672c\u66f4\u4f4e\uff0c\u80fd\u5728 MVP \u5185\u4ea4\u4ed8", decision.reason(), "decision reason");
        assertEquals("\u65b9\u6848 A \u66f4\u6210\u719f", decision.objection(), "decision objection");
        assertEquals("\u7b2c\u4e00\u7248\u91c7\u7528\u65b9\u6848 B\uff0c\u540e\u7eed\u4fdd\u7559\u65b9\u6848 A \u4f5c\u4e3a\u5907\u9009", decision.conclusion(), "decision conclusion");
        assertEquals("MVP", decision.stage(), "decision stage");
        assertEquals("2026-05-01", decision.occurredAt(), "decision occurred at");

        String markdown = result.cards().getFirst().markdown();
        assertTrue(markdown.contains("\u5386\u53f2\u51b3\u7b56\u5361\u7247"), "card title");
        assertTrue(markdown.contains("\u4e3b\u9898: \u6280\u672f\u65b9\u6848"), "card subject");
        assertTrue(markdown.contains("\u51b3\u7b56: \u4f7f\u7528\u65b9\u6848 B"), "card decision");
        assertTrue(markdown.contains("\u7406\u7531: \u96c6\u6210\u6210\u672c\u66f4\u4f4e"), "card reason");
        assertTrue(markdown.contains("\u53cd\u5bf9\u610f\u89c1: \u65b9\u6848 A \u66f4\u6210\u719f"), "card objection");
        assertTrue(markdown.contains("\u7ed3\u8bba: \u7b2c\u4e00\u7248\u91c7\u7528\u65b9\u6848 B"), "card conclusion");
        assertTrue(markdown.contains("\u9636\u6bb5: MVP"), "card stage");
        assertTrue(markdown.contains("\u65f6\u95f4: 2026-05-01"), "card time");
        assertTrue(markdown.contains("\u6765\u6e90\u6d88\u606f: msg_decision"), "card source message");

        List<ProjectMemoryRunResult.CardResult> matches = projectMemory.match("proj_pm",
                "\u6211\u4eec\u8fd8\u8981\u4e0d\u8981\u56de\u5230\u65b9\u6848 A\uff1f", 3);
        assertEquals(1, matches.size(), "manual match count");
        assertEquals(decision.decisionId(), matches.getFirst().decisionId(), "manual match decision id");
    }

    private static void testProjectMemorySendsDecisionCard() throws Exception {
        FeishuIntegrationService feishu = new FeishuIntegrationService(
                new MemoryService(),
                new LarkCliClient(createProjectMemoryFakeCli(0, 0)));
        ProjectMemoryService projectMemory = new ProjectMemoryService(feishu);

        ProjectMemoryRunResult result = projectMemory.runFromFeishu("proj_pm", Map.of(
                "chat_id", "oc_demo",
                "send", true,
                "max_cards", 1
        ));

        assertEquals(1, result.cards().size(), "sent card count");
        ProjectMemoryRunResult.CardResult card = result.cards().getFirst();
        assertEquals(Boolean.TRUE, card.sent(), "send=true sends card");
        assertEquals(0, card.exitCode(), "send card exit code");
        assertTrue(card.command().contains("im"), "send command includes im");
        assertTrue(card.command().contains("+messages-send"), "send command includes messages-send");
        assertTrue(card.command().contains("--chat-id"), "send command includes chat id flag");
        assertTrue(card.command().contains("oc_demo"), "send command includes chat id");
        assertTrue(card.command().contains("--markdown"), "send command includes markdown flag");
        assertTrue(card.command().contains("--idempotency-key"), "send command includes idempotency key flag");
        assertTrue(card.command().contains("--as"), "send command includes as flag");
        assertTrue(card.command().contains("bot"), "send command uses bot identity");
        assertTrue(card.idempotencyKey().contains("msg_followup"), "idempotency key includes trigger message");
    }

    private static void testProjectMemoryReportsSendFailure() throws Exception {
        FeishuIntegrationService feishu = new FeishuIntegrationService(
                new MemoryService(),
                new LarkCliClient(createProjectMemoryFakeCli(0, 9)));
        ProjectMemoryService projectMemory = new ProjectMemoryService(feishu);

        ProjectMemoryRunResult result = projectMemory.runFromFeishu("proj_pm", Map.of(
                "chat_id", "oc_demo",
                "send", true,
                "max_cards", 1
        ));

        assertEquals(1, result.decisionsExtracted(), "send failure still extracts decision");
        assertEquals(1, result.cards().size(), "send failure card count");
        ProjectMemoryRunResult.CardResult card = result.cards().getFirst();
        assertEquals(Boolean.FALSE, card.sent(), "send failure marks card unsent");
        assertEquals(9, card.exitCode(), "send failure exit code");
        assertTrue(card.command().contains("+messages-send"), "send failure keeps command for diagnosis");
    }

    private static void testHttpProjectMemoryWorkflowAndValidation() throws Exception {
        MemoryService memory = new MemoryService();
        FeishuIntegrationService feishu = new FeishuIntegrationService(memory,
                new LarkCliClient(createProjectMemoryFakeCli(0, 0)));
        MemoryHttpServer server = MemoryHttpServer.start(0, memory, feishu);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            Map<?, ?> run = postJson(client, base + "/projects/proj_pm/project-memory/feishu/run",
                    Json.stringify(Map.of("chat_id", "oc_demo", "send", true, "max_cards", 2)));
            assertEquals("proj_pm", run.get("project_id"), "http project memory project");
            assertEquals(2, ((Number) run.get("messages_read")).intValue(), "http project memory messages");
            assertEquals(1, ((Number) run.get("decisions_extracted")).intValue(), "http project memory decisions");
            assertEquals(1, ((Number) run.get("matches_triggered")).intValue(), "http project memory matches");
            List<?> cards = castList(run.get("cards"));
            assertEquals(1, cards.size(), "http project memory cards");
            assertEquals(Boolean.TRUE, castMap(cards.getFirst()).get("sent"), "http project memory sent");

            Map<?, ?> decisions = getJson(client, base + "/projects/proj_pm/project-memory/decisions");
            assertEquals(1, castList(decisions.get("decisions")).size(), "http project memory decision list");

            Map<?, ?> match = postJson(client, base + "/projects/proj_pm/project-memory/match",
                    Json.stringify(Map.of("text", "\u6211\u4eec\u8fd8\u8981\u4e0d\u8981\u56de\u5230\u65b9\u6848 A\uff1f", "max_cards", 3)));
            assertEquals(1, castList(match.get("cards")).size(), "http project memory match cards");

            HttpResponse<String> missingChatId = postRaw(client,
                    base + "/projects/proj_pm/project-memory/feishu/run",
                    "{\"send\":false}");
            assertEquals(400, missingChatId.statusCode(), "missing chat_id returns 400");
        } finally {
            server.stop();
        }

        FeishuIntegrationService missingFeishu = new FeishuIntegrationService(new MemoryService(),
                new LarkCliClient(Path.of("missing-lark-cli.exe")));
        MemoryHttpServer missingServer = MemoryHttpServer.start(0, new MemoryService(), missingFeishu);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + missingServer.port();
            Map<?, ?> run = postJson(client, base + "/projects/proj_pm/project-memory/feishu/run",
                    Json.stringify(Map.of("chat_id", "oc_demo")));
            Map<?, ?> read = castMap(run.get("read"));
            assertEquals(-1, ((Number) read.get("exit_code")).intValue(), "missing CLI read exit code");
            assertTrue(((String) read.get("stderr")).contains("CLI not found"), "missing CLI read stderr");
        } finally {
            missingServer.stop();
        }
    }

    private static void testHttpProjectMemoryManualMessageImportWorkflow() throws Exception {
        MemoryService memory = new MemoryService();
        FeishuIntegrationService feishu = new FeishuIntegrationService(memory,
                new LarkCliClient(Path.of("missing-lark-cli.exe")));
        MemoryHttpServer server = MemoryHttpServer.start(0, memory, feishu);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", "manual_chat");
            body.put("send", false);
            body.put("max_cards", 3);
            body.put("messages", List.of(
                    Map.of(
                            "message_id", "manual_decision",
                            "create_time", "2026-05-01T09:00:00+08:00",
                            "sender", "u_1",
                            "text", "\u51b3\u7b56: \u4f7f\u7528\u65b9\u6848 B\n"
                                    + "\u4e3b\u9898: \u6280\u672f\u65b9\u6848\n"
                                    + "\u7406\u7531: \u96c6\u6210\u6210\u672c\u66f4\u4f4e\uff0c\u80fd\u5728 MVP \u5185\u4ea4\u4ed8\n"
                                    + "\u53cd\u5bf9: \u65b9\u6848 A \u66f4\u6210\u719f\n"
                                    + "\u7ed3\u8bba: \u7b2c\u4e00\u7248\u91c7\u7528\u65b9\u6848 B\uff0c\u540e\u7eed\u4fdd\u7559\u65b9\u6848 A \u4f5c\u4e3a\u5907\u9009\n"
                                    + "\u9636\u6bb5: MVP\n"
                                    + "\u65f6\u95f4: 2026-05-01"
                    ),
                    Map.of(
                            "message_id", "manual_followup",
                            "create_time", "2026-05-01T10:00:00+08:00",
                            "sender", "u_2",
                            "text", "\u6211\u4eec\u8fd8\u8981\u4e0d\u8981\u56de\u5230\u65b9\u6848 A\uff1f"
                    )
            ));

            Map<?, ?> imported = postJson(client,
                    base + "/projects/proj_pm/project-memory/messages/import",
                    Json.stringify(body));
            assertEquals("proj_pm", imported.get("project_id"), "manual project memory project");
            assertEquals(2, ((Number) imported.get("messages_read")).intValue(), "manual project memory messages");
            assertEquals(1, ((Number) imported.get("decisions_extracted")).intValue(), "manual project memory decisions");
            assertEquals(1, ((Number) imported.get("matches_triggered")).intValue(), "manual project memory matches");
            assertEquals(1, castList(imported.get("cards")).size(), "manual project memory cards");
            assertTrue(castList(castMap(imported.get("read")).get("command")).isEmpty(), "manual import does not call CLI");

            Map<?, ?> decisions = getJson(client, base + "/projects/proj_pm/project-memory/decisions");
            assertEquals(1, castList(decisions.get("decisions")).size(), "manual decisions persisted");

            HttpResponse<String> missingMessages = postRaw(client,
                    base + "/projects/proj_pm/project-memory/messages/import",
                    "{\"chat_id\":\"manual_chat\"}");
            assertEquals(400, missingMessages.statusCode(), "missing manual messages returns 400");
        } finally {
            server.stop();
        }
    }

    private static void testHttpProjectMemoryRejectsInvalidNumberParameters() throws Exception {
        MemoryService memory = new MemoryService();
        FeishuIntegrationService feishu = new FeishuIntegrationService(memory,
                new LarkCliClient(createProjectMemoryFakeCli(0, 0)));
        MemoryHttpServer server = MemoryHttpServer.start(0, memory, feishu);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> invalidPageSize = postRaw(client,
                    base + "/projects/proj_pm/project-memory/feishu/run",
                    "{\"chat_id\":\"oc_demo\",\"page_size\":\"abc\"}");
            assertEquals(400, invalidPageSize.statusCode(), "invalid page_size returns 400");
            assertTrue(invalidPageSize.body().contains("page_size"), "invalid page_size response names field");

            HttpResponse<String> invalidMaxCards = postRaw(client,
                    base + "/projects/proj_pm/project-memory/match",
                    "{\"text\":\"方案 A\",\"max_cards\":\"abc\"}");
            assertEquals(400, invalidMaxCards.statusCode(), "invalid max_cards returns 400");
            assertTrue(invalidMaxCards.body().contains("max_cards"), "invalid max_cards response names field");
        } finally {
            server.stop();
        }
    }

    private static Map<?, ?> postJson(HttpClient client, String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST " + url + " status");
        return castMap(Json.parse(response.body()));
    }

    private static Map<?, ?> getJson(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "GET " + url + " status");
        return castMap(Json.parse(response.body()));
    }

    private static HttpResponse<String> postRaw(HttpClient client, String url, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getRaw(HttpClient client, String url)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path createProjectMemoryFakeCli(int readExitCode, int sendExitCode) throws IOException {
        Path dir = Files.createTempDirectory("memory-project-lark-cli");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path cli = dir.resolve(windows ? "lark-cli.cmd" : "lark-cli");
        String messagesJson = "{\"items\":["
                + "{\"message_id\":\"msg_decision\",\"chat_id\":\"oc_demo\",\"create_time\":\"2026-05-01T09:00:00+08:00\","
                + "\"sender\":{\"id\":\"u_1\"},\"content\":{\"text\":\""
                + "\\u51b3\\u7b56: \\u4f7f\\u7528\\u65b9\\u6848 B\\n"
                + "\\u4e3b\\u9898: \\u6280\\u672f\\u65b9\\u6848\\n"
                + "\\u7406\\u7531: \\u96c6\\u6210\\u6210\\u672c\\u66f4\\u4f4e\\uff0c\\u80fd\\u5728 MVP \\u5185\\u4ea4\\u4ed8\\n"
                + "\\u53cd\\u5bf9: \\u65b9\\u6848 A \\u66f4\\u6210\\u719f\\n"
                + "\\u7ed3\\u8bba: \\u7b2c\\u4e00\\u7248\\u91c7\\u7528\\u65b9\\u6848 B\\uff0c\\u540e\\u7eed\\u4fdd\\u7559\\u65b9\\u6848 A \\u4f5c\\u4e3a\\u5907\\u9009\\n"
                + "\\u9636\\u6bb5: MVP\\n"
                + "\\u65f6\\u95f4: 2026-05-01\"}},"
                + "{\"message_id\":\"msg_followup\",\"chat_id\":\"oc_demo\",\"create_time\":\"2026-05-01T10:00:00+08:00\","
                + "\"sender\":{\"id\":\"u_2\"},\"content\":{\"text\":\""
                + "\\u6211\\u4eec\\u8fd8\\u8981\\u4e0d\\u8981\\u56de\\u5230\\u65b9\\u6848 A\\uff1f\"}}]}";
        String script;
        if (windows) {
            script = "@echo off\r\n"
                    + "if \"%1\"==\"im\" if \"%2\"==\"+chat-messages-list\" (\r\n"
                    + "  echo " + messagesJson + "\r\n"
                    + "  exit /b " + readExitCode + "\r\n"
                    + ")\r\n"
                    + "if \"%1\"==\"im\" if \"%2\"==\"+messages-send\" (\r\n"
                    + "  echo {\"sent\":true}\r\n"
                    + "  exit /b " + sendExitCode + "\r\n"
                    + ")\r\n"
                    + "echo stdout:%*\r\n"
                    + "exit /b 0\r\n";
        } else {
            script = "#!/usr/bin/env sh\n"
                    + "if [ \"$1\" = \"im\" ] && [ \"$2\" = \"+chat-messages-list\" ]; then\n"
                    + "  printf '%s\\n' '" + messagesJson + "'\n"
                    + "  exit " + readExitCode + "\n"
                    + "fi\n"
                    + "if [ \"$1\" = \"im\" ] && [ \"$2\" = \"+messages-send\" ]; then\n"
                    + "  printf '%s\\n' '{\"sent\":true}'\n"
                    + "  exit " + sendExitCode + "\n"
                    + "fi\n"
                    + "echo \"stdout:$*\"\n"
                    + "exit 0\n";
        }
        Files.writeString(cli, script, StandardCharsets.UTF_8);
        cli.toFile().setExecutable(true);
        return cli;
    }

    private static Path createFakeCli(int exitCode) throws IOException {
        Path dir = Files.createTempDirectory("memory-fake-lark-cli");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path cli = dir.resolve(windows ? "lark-cli.cmd" : "lark-cli");
        String script;
        if (windows) {
            script = "@echo off\r\n"
                    + "echo stdout:%*\r\n"
                    + "echo stderr:%* 1>&2\r\n"
                    + "exit /b " + exitCode + "\r\n";
        } else {
            script = "#!/usr/bin/env sh\n"
                    + "echo \"stdout:$*\"\n"
                    + "echo \"stderr:$*\" 1>&2\n"
                    + "exit " + exitCode + "\n";
        }
        Files.writeString(cli, script, StandardCharsets.UTF_8);
        cli.toFile().setExecutable(true);
        return cli;
    }

    private static Map<?, ?> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new AssertionError("Expected map but got " + value);
    }

    private static List<?> castList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new AssertionError("Expected list but got " + value);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
