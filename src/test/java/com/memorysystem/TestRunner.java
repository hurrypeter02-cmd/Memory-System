package com.memorysystem;

import com.memorysystem.api.MemoryHttpServer;
import com.memorysystem.model.DocumentSource;
import com.memorysystem.model.MemoryEvent;
import com.memorysystem.model.SourceFragment;
import com.memorysystem.model.TimelineItem;
import com.memorysystem.service.MemoryService;
import com.memorysystem.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
