package com.memorysystem.integration.feishu;

import com.memorysystem.model.DocumentSource;
import com.memorysystem.service.MemoryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FeishuIntegrationService {
    private static final Set<String> SEARCH_DOMAINS = Set.of("drive", "docs");

    private final MemoryService memoryService;
    private final LarkCliClient cliClient;

    public FeishuIntegrationService(MemoryService memoryService, LarkCliClient cliClient) {
        this.memoryService = memoryService;
        this.cliClient = cliClient;
    }

    public CliResponse status() {
        LarkCliClient.Result result = cliClient.run(List.of("auth", "status"));
        return CliResponse.from(result, result.exitCode() == 0);
    }

    public CliResponse search(Map<String, Object> payload) {
        String domain = stringValue(payload.get("domain"), "drive");
        if (!SEARCH_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("domain must be drive or docs");
        }

        String query = requiredString(payload, "query");
        boolean dryRun = booleanValue(payload.get("dry_run"), true);

        List<String> args = new ArrayList<>();
        args.add(domain);
        args.add("+search");
        args.add("--query");
        args.add(query);
        if (dryRun) {
            args.add("--dry-run");
        }
        args.add("--as");
        args.add("user");

        LarkCliClient.Result result = cliClient.run(args);
        return CliResponse.from(result, result.exitCode() == 0);
    }

    public CliResponse readChatMessages(String chatId, String start, String end, int pageSize) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chat_id is required");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("page_size must be positive");
        }

        List<String> args = new ArrayList<>();
        args.add("im");
        args.add("+chat-messages-list");
        args.add("--chat-id");
        args.add(chatId);
        if (start != null && !start.isBlank()) {
            args.add("--start");
            args.add(start);
        }
        if (end != null && !end.isBlank()) {
            args.add("--end");
            args.add(end);
        }
        args.add("--sort");
        args.add("asc");
        args.add("--page-size");
        args.add(String.valueOf(pageSize));
        args.add("--as");
        args.add("user");
        args.add("--format");
        args.add("json");

        LarkCliClient.Result result = cliClient.run(args);
        return CliResponse.from(result, result.exitCode() == 0);
    }

    public CliResponse sendDecisionCard(String chatId, String markdown, String idempotencyKey) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chat_id is required");
        }
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("markdown is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency_key is required");
        }

        List<String> args = new ArrayList<>();
        args.add("im");
        args.add("+messages-send");
        args.add("--chat-id");
        args.add(chatId);
        args.add("--markdown");
        args.add(markdown);
        args.add("--idempotency-key");
        args.add(idempotencyKey);
        args.add("--as");
        args.add("bot");

        LarkCliClient.Result result = cliClient.run(args);
        return CliResponse.from(result, result.exitCode() == 0);
    }

    public DocumentSource importSearchResult(String projectId, Map<String, Object> payload) {
        Map<String, Object> sourcePayload = new LinkedHashMap<>();
        sourcePayload.put("title", requiredString(payload, "title"));
        sourcePayload.put("source_type", stringValue(payload.get("source_type"), "feishu_search"));
        sourcePayload.put("content", requiredString(payload, "content"));
        sourcePayload.put("metadata", mapValue(payload.get("metadata")));
        return memoryService.importSource(projectId, sourcePayload);
    }

    private String requiredString(Map<String, Object> payload, String fieldName) {
        Object value = payload.get(fieldName);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return String.valueOf(value);
    }

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    public record CliResponse(
            int exitCode,
            String stdout,
            String stderr,
            boolean configured,
            List<String> command
    ) {
        private static CliResponse from(LarkCliClient.Result result, boolean configured) {
            return new CliResponse(
                    result.exitCode(),
                    result.stdout(),
                    result.stderr(),
                    configured,
                    result.command()
            );
        }
    }
}
