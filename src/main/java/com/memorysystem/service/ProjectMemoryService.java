package com.memorysystem.service;

import com.memorysystem.integration.feishu.FeishuIntegrationService;
import com.memorysystem.model.ProjectDecisionMemory;
import com.memorysystem.model.ProjectMemoryRunResult;
import com.memorysystem.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProjectMemoryService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DEFAULT_MAX_CARDS = 3;

    private final FeishuIntegrationService feishu;
    private final Map<String, List<ProjectDecisionMemory>> decisionsByProject = new HashMap<>();
    private final Set<String> decisionSourceKeys = new LinkedHashSet<>();
    private int decisionSequence = 1;

    public ProjectMemoryService(FeishuIntegrationService feishu) {
        this.feishu = feishu;
    }

    public synchronized ProjectMemoryRunResult runFromFeishu(String projectId, Map<String, Object> payload) {
        requireText(projectId, "project_id");
        String chatId = requiredString(payload, "chat_id");
        String start = stringValue(payload.get("start"), null);
        String end = stringValue(payload.get("end"), null);
        int pageSize = intValue(payload.get("page_size"), DEFAULT_PAGE_SIZE, "page_size");
        boolean send = booleanValue(payload.get("send"), false);
        int maxCards = intValue(payload.get("max_cards"), DEFAULT_MAX_CARDS, "max_cards");

        FeishuIntegrationService.CliResponse read = feishu.readChatMessages(chatId, start, end, pageSize);
        ProjectMemoryRunResult.CommandResult readResult = commandResult(read);
        if (read.exitCode() != 0) {
            return new ProjectMemoryRunResult(projectId, 0, 0, 0, readResult, List.of());
        }

        List<ProjectMessage> messages = parseMessages(read.stdout(), chatId);
        return processMessages(projectId, messages, readResult, send, chatId, maxCards);
    }

    public synchronized ProjectMemoryRunResult importMessages(String projectId, Map<String, Object> payload) {
        requireText(projectId, "project_id");
        boolean send = booleanValue(payload.get("send"), false);
        String chatId = stringValue(payload.get("chat_id"), "");
        if (send && chatId.isBlank()) {
            throw new IllegalArgumentException("chat_id is required when send is true");
        }
        String fallbackChatId = chatId.isBlank() ? "manual" : chatId;
        int maxCards = intValue(payload.get("max_cards"), DEFAULT_MAX_CARDS, "max_cards");
        List<ProjectMessage> messages = parseManualMessages(payload.get("messages"), fallbackChatId);
        ProjectMemoryRunResult.CommandResult readResult = new ProjectMemoryRunResult.CommandResult(
                0,
                "",
                "",
                List.of()
        );
        return processMessages(projectId, messages, readResult, send, fallbackChatId, maxCards);
    }

    private ProjectMemoryRunResult processMessages(
            String projectId,
            List<ProjectMessage> messages,
            ProjectMemoryRunResult.CommandResult readResult,
            boolean send,
            String targetChatId,
            int maxCards
    ) {
        int extracted = 0;
        int triggered = 0;
        List<ProjectMemoryRunResult.CardResult> cards = new ArrayList<>();
        for (ProjectMessage message : messages) {
            ParsedDecision parsed = parseDecision(message);
            if (parsed != null) {
                if (addDecision(projectId, message, parsed) != null) {
                    extracted++;
                }
                continue;
            }

            List<ProjectMemoryRunResult.CardResult> matched = matchMessage(
                    projectId,
                    message,
                    maxCards - cards.size(),
                    send,
                    targetChatId
            );
            if (!matched.isEmpty()) {
                triggered++;
                cards.addAll(matched);
            }
            if (cards.size() >= maxCards) {
                break;
            }
        }

        return new ProjectMemoryRunResult(projectId, messages.size(), extracted, triggered, readResult, List.copyOf(cards));
    }

    public synchronized List<ProjectDecisionMemory> decisions(String projectId) {
        return List.copyOf(decisionsByProject.getOrDefault(projectId, List.of()));
    }

    public synchronized List<ProjectMemoryRunResult.CardResult> match(String projectId, String text, int maxCards) {
        ProjectMessage message = new ProjectMessage("manual", "", "", "", text == null ? "" : text);
        return matchMessage(projectId, message, maxCards <= 0 ? DEFAULT_MAX_CARDS : maxCards, false, "");
    }

    public String markdownCard(ProjectDecisionMemory decision) {
        StringBuilder out = new StringBuilder();
        out.append("**").append("\u5386\u53f2\u51b3\u7b56\u5361\u7247").append("**\n");
        appendLine(out, "\u4e3b\u9898", decision.subject());
        appendLine(out, "\u51b3\u7b56", decision.decision());
        appendLine(out, "\u7406\u7531", decision.reason());
        appendLine(out, "\u53cd\u5bf9\u610f\u89c1", decision.objection());
        appendLine(out, "\u7ed3\u8bba", decision.conclusion());
        appendLine(out, "\u9636\u6bb5", decision.stage());
        appendLine(out, "\u65f6\u95f4", decision.occurredAt());
        appendLine(out, "\u6765\u6e90\u6d88\u606f", decision.sourceMessageId());
        return out.toString().trim();
    }

    private ProjectDecisionMemory addDecision(String projectId, ProjectMessage message, ParsedDecision parsed) {
        String key = projectId + ":" + message.messageId();
        if (!decisionSourceKeys.add(key)) {
            return null;
        }

        ProjectDecisionMemory decision = new ProjectDecisionMemory(
                "dec_%03d".formatted(decisionSequence++),
                projectId,
                parsed.subject(),
                parsed.decision(),
                parsed.reason(),
                parsed.objection(),
                parsed.conclusion(),
                parsed.stage(),
                parsed.occurredAt(),
                message.messageId(),
                message.chatId(),
                keywords(parsed)
        );
        decisionsByProject.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(decision);
        return decision;
    }

    private List<ProjectMemoryRunResult.CardResult> matchMessage(
            String projectId,
            ProjectMessage message,
            int limit,
            boolean send,
            String targetChatId
    ) {
        if (limit <= 0) {
            return List.of();
        }
        String normalized = normalize(message.text());
        if (normalized.isBlank()) {
            return List.of();
        }

        List<ScoredDecision> matches = decisionsByProject.getOrDefault(projectId, List.of()).stream()
                .map(decision -> new ScoredDecision(decision, score(normalized, decision.keywords())))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredDecision::score).reversed()
                        .thenComparing(scored -> scored.decision().decisionId()))
                .limit(limit)
                .toList();

        List<ProjectMemoryRunResult.CardResult> cards = new ArrayList<>();
        for (ScoredDecision scored : matches) {
            ProjectDecisionMemory decision = scored.decision();
            String markdown = markdownCard(decision);
            String idempotencyKey = "memory-%s-%s-%s".formatted(
                    projectId,
                    decision.decisionId(),
                    message.messageId()
            );
            if (send) {
                FeishuIntegrationService.CliResponse response = feishu.sendDecisionCard(
                        targetChatId,
                        markdown,
                        idempotencyKey
                );
                cards.add(new ProjectMemoryRunResult.CardResult(
                        decision.decisionId(),
                        message.messageId(),
                        idempotencyKey,
                        markdown,
                        response.exitCode() == 0,
                        response.exitCode(),
                        response.stdout(),
                        response.stderr(),
                        response.command()
                ));
            } else {
                cards.add(new ProjectMemoryRunResult.CardResult(
                        decision.decisionId(),
                        message.messageId(),
                        idempotencyKey,
                        markdown,
                        false,
                        0,
                        "",
                        "",
                        List.of()
                ));
            }
        }
        return List.copyOf(cards);
    }

    private ParsedDecision parseDecision(ProjectMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String rawLine : message.text().split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int split = labelSeparator(line);
            if (split < 0) {
                continue;
            }
            String label = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if (!label.isEmpty() && !value.isEmpty()) {
                fields.put(label, value);
            }
        }

        String decision = fields.get("\u51b3\u7b56");
        if (decision == null || decision.isBlank()) {
            return null;
        }
        String subject = stringValue(fields.get("\u4e3b\u9898"), abbreviate(decision, 20));
        return new ParsedDecision(
                subject,
                decision,
                stringValue(fields.get("\u7406\u7531"), ""),
                stringValue(fields.get("\u53cd\u5bf9"), ""),
                stringValue(fields.get("\u7ed3\u8bba"), ""),
                stringValue(fields.get("\u9636\u6bb5"), ""),
                stringValue(fields.get("\u65f6\u95f4"), message.createTime())
        );
    }

    private List<ProjectMessage> parseMessages(String stdout, String fallbackChatId) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        Object root;
        try {
            root = Json.parse(stdout);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        List<?> rawItems = findMessageItems(root);
        List<ProjectMessage> messages = new ArrayList<>();
        int sequence = 1;
        for (Object item : rawItems) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> object = stringMap(map);
            String messageId = firstString(object, "message_id", "messageId", "id");
            if (messageId.isBlank()) {
                messageId = "msg_%03d".formatted(sequence);
            }
            String chatId = stringValue(firstString(object, "chat_id", "chatId"), fallbackChatId);
            String createTime = firstString(object, "create_time", "createTime", "created_at", "timestamp");
            String sender = senderValue(object.get("sender"));
            String text = messageText(object);
            messages.add(new ProjectMessage(messageId, chatId, createTime, sender, text));
            sequence++;
        }
        return List.copyOf(messages);
    }

    private List<ProjectMessage> parseManualMessages(Object value, String fallbackChatId) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }
        List<ProjectMessage> messages = new ArrayList<>();
        int sequence = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("messages must contain JSON objects");
            }
            Map<String, Object> object = stringMap(map);
            String messageId = firstString(object, "message_id", "messageId", "id");
            if (messageId.isBlank()) {
                messageId = "manual_%03d".formatted(sequence);
            }
            String chatId = stringValue(firstString(object, "chat_id", "chatId"), fallbackChatId);
            String createTime = firstString(object, "create_time", "createTime", "created_at", "timestamp");
            String sender = senderValue(object.get("sender"));
            String text = messageText(object);
            if (text.isBlank()) {
                throw new IllegalArgumentException("message text is required");
            }
            messages.add(new ProjectMessage(messageId, chatId, createTime, sender, text));
            sequence++;
        }
        return List.copyOf(messages);
    }

    private List<?> findMessageItems(Object root) {
        if (root instanceof List<?> list) {
            return list;
        }
        if (!(root instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> object = stringMap(map);
        Object items = firstValue(object, "items", "messages", "list");
        if (items instanceof List<?> list) {
            return list;
        }
        Object data = object.get("data");
        if (data instanceof List<?> list) {
            return list;
        }
        if (data instanceof Map<?, ?> dataMap) {
            Map<String, Object> dataObject = stringMap(dataMap);
            Object dataItems = firstValue(dataObject, "items", "messages", "list");
            if (dataItems instanceof List<?> list) {
                return list;
            }
        }
        return List.of();
    }

    private String messageText(Map<String, Object> object) {
        String direct = firstString(object, "text", "message");
        if (!direct.isBlank()) {
            return direct;
        }
        Object content = object.get("content");
        if (content instanceof Map<?, ?> map) {
            Map<String, Object> contentMap = stringMap(map);
            return firstString(contentMap, "text", "content", "plain_text");
        }
        if (content instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                try {
                    Object parsed = Json.parse(trimmed);
                    if (parsed instanceof Map<?, ?> map) {
                        return firstString(stringMap(map), "text", "content", "plain_text");
                    }
                } catch (IllegalArgumentException ignored) {
                    return text;
                }
            }
            return text;
        }
        return "";
    }

    private String senderValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sender = stringMap(map);
            return firstString(sender, "id", "user_id", "open_id", "name");
        }
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> keywords(ParsedDecision parsed) {
        Set<String> terms = new LinkedHashSet<>();
        addTerms(parsed.subject(), terms);
        addTerms(parsed.decision(), terms);
        addTerms(parsed.reason(), terms);
        addTerms(parsed.objection(), terms);
        addTerms(parsed.conclusion(), terms);
        addTerms(parsed.stage(), terms);
        return List.copyOf(terms);
    }

    private void addTerms(String value, Set<String> terms) {
        String normalized = normalize(value);
        if (normalized.length() >= 2) {
            terms.add(normalized);
        }
        StringBuilder token = new StringBuilder();
        String lower = value == null ? "" : value.toLowerCase();
        lower.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                token.appendCodePoint(codePoint);
            } else {
                addTokenTerms(token.toString(), terms);
                token.setLength(0);
            }
        });
        addTokenTerms(token.toString(), terms);
    }

    private void addTokenTerms(String token, Set<String> terms) {
        if (token == null || token.isBlank()) {
            return;
        }
        boolean ascii = token.codePoints().allMatch(codePoint -> codePoint < 128);
        if (ascii) {
            if (token.length() >= 2) {
                terms.add(token);
            }
            return;
        }
        if (token.length() >= 2) {
            terms.add(token);
        }
        for (int i = 0; i <= token.length() - 2; i++) {
            terms.add(token.substring(i, i + 2));
        }
        for (int i = 0; i <= token.length() - 3; i++) {
            terms.add(token.substring(i, i + 3));
        }
    }

    private int score(String normalizedMessage, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (keyword.length() >= 2 && normalizedMessage.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private String normalize(String value) {
        StringBuilder out = new StringBuilder();
        String lower = value == null ? "" : value.toLowerCase();
        lower.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private int labelSeparator(String line) {
        int ascii = line.indexOf(':');
        int wide = line.indexOf('\uff1a');
        if (ascii < 0) {
            return wide;
        }
        if (wide < 0) {
            return ascii;
        }
        return Math.min(ascii, wide);
    }

    private String abbreviate(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    private void appendLine(StringBuilder out, String label, String value) {
        out.append("- ").append(label).append(": ");
        out.append(value == null || value.isBlank() ? "\u672a\u586b\u5199" : value);
        out.append('\n');
    }

    private ProjectMemoryRunResult.CommandResult commandResult(FeishuIntegrationService.CliResponse response) {
        return new ProjectMemoryRunResult.CommandResult(
                response.exitCode(),
                response.stdout(),
                response.stderr(),
                response.command()
        );
    }

    private Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private Object firstValue(Map<String, Object> map, String... names) {
        for (String name : names) {
            if (map.containsKey(name)) {
                return map.get(name);
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> map, String... names) {
        Object value = firstValue(map, names);
        return value == null ? "" : String.valueOf(value);
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private record ProjectMessage(String messageId, String chatId, String createTime, String sender, String text) {
    }

    private record ParsedDecision(
            String subject,
            String decision,
            String reason,
            String objection,
            String conclusion,
            String stage,
            String occurredAt
    ) {
    }

    private record ScoredDecision(ProjectDecisionMemory decision, int score) {
    }
}
