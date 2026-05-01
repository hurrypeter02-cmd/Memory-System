package com.memorysystem.api;

import com.memorysystem.model.DocumentSource;
import com.memorysystem.model.MemoryEvent;
import com.memorysystem.model.SourceFragment;
import com.memorysystem.model.TimelineItem;
import com.memorysystem.service.MemoryService;
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
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        MemoryHttpServer wrapper = new MemoryHttpServer(httpServer);
        httpServer.createContext("/", exchange -> wrapper.handle(exchange, service));
        httpServer.start();
        return wrapper;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange, MemoryService service) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            List<String> parts = pathParts(exchange.getRequestURI().getPath());
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
}
