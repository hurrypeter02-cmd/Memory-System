package com.memorysystem.api;

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
import com.memorysystem.util.HttpUtil;
import com.memorysystem.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryHttpServer {
    private final HttpServer server;

    private MemoryHttpServer(HttpServer server) {
        this.server = server;
    }

    public static MemoryHttpServer start(int port, MemoryService service) throws IOException {
        return start(port, service, new FeishuIntegrationService(service, new LarkCliClient()));
    }

    public static MemoryHttpServer start(int port, MemoryService service, FeishuIntegrationService feishu) throws IOException {
        return start(port, service, feishu, new ProjectMemoryService(feishu));
    }

    public static MemoryHttpServer start(
            int port,
            MemoryService service,
            FeishuIntegrationService feishu,
            ProjectMemoryService projectMemory
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        MemoryHttpServer wrapper = new MemoryHttpServer(httpServer);
        httpServer.createContext("/", exchange -> wrapper.handle(exchange, service, feishu, projectMemory));
        httpServer.start();
        return wrapper;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(
            HttpExchange exchange,
            MemoryService service,
            FeishuIntegrationService feishu,
            ProjectMemoryService projectMemory
    ) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            List<String> parts = pathParts(exchange.getRequestURI().getPath());
            if (parts.size() == 5
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "project-memory".equals(parts.get(2))
                    && "feishu".equals(parts.get(3))
                    && "run".equals(parts.get(4))) {
                projectMemoryRun(exchange, projectMemory, parts.get(1));
                return;
            }
            if (parts.size() == 5
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "project-memory".equals(parts.get(2))
                    && "messages".equals(parts.get(3))
                    && "import".equals(parts.get(4))) {
                projectMemoryMessagesImport(exchange, projectMemory, parts.get(1));
                return;
            }
            if (parts.size() == 4
                    && "GET".equals(method)
                    && "projects".equals(parts.get(0))
                    && "project-memory".equals(parts.get(2))
                    && "decisions".equals(parts.get(3))) {
                projectMemoryDecisions(exchange, projectMemory, parts.get(1));
                return;
            }
            if (parts.size() == 4
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "project-memory".equals(parts.get(2))
                    && "match".equals(parts.get(3))) {
                projectMemoryMatch(exchange, projectMemory, parts.get(1));
                return;
            }
            if (parts.size() == 3
                    && "GET".equals(method)
                    && "integrations".equals(parts.get(0))
                    && "feishu".equals(parts.get(1))
                    && "status".equals(parts.get(2))) {
                feishuStatus(exchange, feishu);
                return;
            }
            if (parts.size() == 5
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "integrations".equals(parts.get(2))
                    && "feishu".equals(parts.get(3))
                    && "search".equals(parts.get(4))) {
                feishuSearch(exchange, feishu, parts.get(1));
                return;
            }
            if (parts.size() == 5
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "integrations".equals(parts.get(2))
                    && "feishu".equals(parts.get(3))
                    && "import".equals(parts.get(4))) {
                feishuImport(exchange, feishu, parts.get(1));
                return;
            }
            if (parts.size() == 3
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "sources".equals(parts.get(2))) {
                importSource(exchange, service, parts.get(1));
                return;
            }
            if (parts.size() == 4
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "memory".equals(parts.get(2))
                    && "extract".equals(parts.get(3))) {
                extract(exchange, service, parts.get(1));
                return;
            }
            if (parts.size() == 3
                    && "GET".equals(method)
                    && "projects".equals(parts.get(0))
                    && "timeline".equals(parts.get(2))) {
                timeline(exchange, service, parts.get(1));
                return;
            }
            if (parts.size() == 3
                    && "POST".equals(method)
                    && "projects".equals(parts.get(0))
                    && "query".equals(parts.get(2))) {
                query(exchange, service, parts.get(1));
                return;
            }
            if (parts.size() == 4
                    && "GET".equals(method)
                    && "memory".equals(parts.get(0))
                    && "events".equals(parts.get(1))
                    && "sources".equals(parts.get(3))) {
                sources(exchange, service, parts.get(2));
                return;
            }
            HttpUtil.sendJson(exchange, 404, Map.of("error", "not_found"));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            HttpUtil.sendJson(exchange, 500, Map.of("error", "internal_error"));
        }
    }

    private void feishuStatus(HttpExchange exchange, FeishuIntegrationService feishu) throws IOException {
        HttpUtil.sendJson(exchange, 200, cliResponseMap(feishu.status()));
    }

    private void feishuSearch(HttpExchange exchange, FeishuIntegrationService feishu, String projectId) throws IOException {
        Map<String, Object> response = cliResponseMap(feishu.search(requestObject(exchange)));
        response.put("project_id", projectId);
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void feishuImport(HttpExchange exchange, FeishuIntegrationService feishu, String projectId) throws IOException {
        DocumentSource source = feishu.importSearchResult(projectId, requestObject(exchange));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source_id", source.sourceId());
        response.put("project_id", source.projectId());
        response.put("status", "parsed");
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void projectMemoryRun(
            HttpExchange exchange,
            ProjectMemoryService projectMemory,
            String projectId
    ) throws IOException {
        ProjectMemoryRunResult result = projectMemory.runFromFeishu(projectId, requestObject(exchange));
        HttpUtil.sendJson(exchange, 200, projectMemoryRunMap(result));
    }

    private void projectMemoryMessagesImport(
            HttpExchange exchange,
            ProjectMemoryService projectMemory,
            String projectId
    ) throws IOException {
        ProjectMemoryRunResult result = projectMemory.importMessages(projectId, requestObject(exchange));
        HttpUtil.sendJson(exchange, 200, projectMemoryRunMap(result));
    }

    private void projectMemoryDecisions(
            HttpExchange exchange,
            ProjectMemoryService projectMemory,
            String projectId
    ) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("project_id", projectId);
        response.put("decisions", projectMemory.decisions(projectId).stream().map(this::decisionMap).toList());
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void projectMemoryMatch(
            HttpExchange exchange,
            ProjectMemoryService projectMemory,
            String projectId
    ) throws IOException {
        Map<String, Object> body = requestObject(exchange);
        String text = String.valueOf(body.getOrDefault("text", ""));
        int maxCards = intValue(body.get("max_cards"), 3, "max_cards");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("project_id", projectId);
        response.put("cards", projectMemory.match(projectId, text, maxCards).stream().map(this::cardMap).toList());
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void importSource(HttpExchange exchange, MemoryService service, String projectId) throws IOException {
        Map<String, Object> body = requestObject(exchange);
        DocumentSource source = service.importSource(projectId, body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source_id", source.sourceId());
        response.put("project_id", source.projectId());
        response.put("status", "parsed");
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void extract(HttpExchange exchange, MemoryService service, String projectId) throws IOException {
        MemoryService.ExtractionResult result = service.extract(projectId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("project_id", result.projectId());
        response.put("created_events", result.createdEvents());
        response.put("uncertain_events", result.uncertainEvents());
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void timeline(HttpExchange exchange, MemoryService service, String projectId) throws IOException {
        Map<String, String> query = HttpUtil.query(exchange.getRequestURI().getRawQuery());
        List<TimelineItem> items = service.timeline(
                projectId,
                query.get("from"),
                query.get("to"),
                query.get("types"),
                query.get("subject"),
                query.get("status")
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("project_id", projectId);
        response.put("items", items.stream().map(this::timelineMap).toList());
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void query(HttpExchange exchange, MemoryService service, String projectId) throws IOException {
        Map<String, Object> body = requestObject(exchange);
        String question = String.valueOf(body.getOrDefault("question", ""));
        boolean includeSources = Boolean.TRUE.equals(body.get("include_sources"));
        MemoryService.QueryResult result = service.query(projectId, question, includeSources);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", result.answer());
        response.put("related_events", result.relatedEvents());
        response.put("timeline_hint", result.timelineHint());
        if (includeSources) {
            response.put("sources", result.sources().stream().map(this::sourceTraceMap).toList());
        }
        HttpUtil.sendJson(exchange, 200, response);
    }

    private void sources(HttpExchange exchange, MemoryService service, String eventId) throws IOException {
        List<MemoryService.SourceTrace> traces = service.sourcesForEvent(eventId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("event_id", eventId);
        response.put("sources", traces.stream().map(this::sourceTraceMap).toList());
        HttpUtil.sendJson(exchange, 200, response);
    }

    private Map<String, Object> timelineMap(TimelineItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event_id", item.eventId());
        map.put("occurred_at", item.occurredAt());
        map.put("event_type", item.eventType());
        map.put("title", item.title());
        map.put("summary", item.summary());
        map.put("status", item.status());
        return map;
    }

    private Map<String, Object> sourceTraceMap(MemoryService.SourceTrace trace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source_id", trace.source().sourceId());
        map.put("title", trace.source().title());
        map.put("fragments", trace.fragments().stream().map(this::fragmentMap).toList());
        return map;
    }

    private Map<String, Object> fragmentMap(SourceFragment fragment) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fragment_id", fragment.fragmentId());
        map.put("text", fragment.text());
        map.put("location", fragment.location());
        return map;
    }

    private Map<String, Object> cliResponseMap(FeishuIntegrationService.CliResponse response) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("exit_code", response.exitCode());
        map.put("stdout", response.stdout());
        map.put("stderr", response.stderr());
        map.put("configured", response.configured());
        map.put("command", response.command());
        return map;
    }

    private Map<String, Object> projectMemoryRunMap(ProjectMemoryRunResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("project_id", result.projectId());
        map.put("messages_read", result.messagesRead());
        map.put("decisions_extracted", result.decisionsExtracted());
        map.put("matches_triggered", result.matchesTriggered());
        map.put("read", commandMap(result.read()));
        map.put("cards", result.cards().stream().map(this::cardMap).toList());
        return map;
    }

    private Map<String, Object> decisionMap(ProjectDecisionMemory decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decision_id", decision.decisionId());
        map.put("project_id", decision.projectId());
        map.put("subject", decision.subject());
        map.put("decision", decision.decision());
        map.put("reason", decision.reason());
        map.put("objection", decision.objection());
        map.put("conclusion", decision.conclusion());
        map.put("stage", decision.stage());
        map.put("occurred_at", decision.occurredAt());
        map.put("source_message_id", decision.sourceMessageId());
        map.put("source_chat_id", decision.sourceChatId());
        map.put("keywords", decision.keywords());
        return map;
    }

    private Map<String, Object> cardMap(ProjectMemoryRunResult.CardResult card) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decision_id", card.decisionId());
        map.put("trigger_message_id", card.triggerMessageId());
        map.put("idempotency_key", card.idempotencyKey());
        map.put("markdown", card.markdown());
        map.put("sent", card.sent());
        map.put("exit_code", card.exitCode());
        map.put("stdout", card.stdout());
        map.put("stderr", card.stderr());
        map.put("command", card.command());
        return map;
    }

    private Map<String, Object> commandMap(ProjectMemoryRunResult.CommandResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("exit_code", result.exitCode());
        map.put("stdout", result.stdout());
        map.put("stderr", result.stderr());
        map.put("command", result.command());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestObject(HttpExchange exchange) throws IOException {
        Object parsed = Json.parse(HttpUtil.readBody(exchange));
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw new IllegalArgumentException("JSON object body is required");
    }

    private List<String> pathParts(String path) {
        List<String> result = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                result.add(HttpUtil.decode(part));
            }
        }
        return result;
    }

    private int intValue(Object value, int fallback, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be an integer", e);
        }
    }
}
