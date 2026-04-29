package com.memorysystem.service;

import com.memorysystem.model.DocumentSource;
import com.memorysystem.model.EventRelation;
import com.memorysystem.model.MemoryEvent;
import com.memorysystem.model.SourceFragment;
import com.memorysystem.model.TimelineItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MemoryService {
    private static final Set<String> EVENT_TYPES = Set.of("requirement", "decision", "event", "risk", "delivery");
    private static final Set<String> STATUSES = Set.of("active", "superseded", "cancelled", "uncertain");

    private final Map<String, DocumentSource> sources = new LinkedHashMap<>();
    private final Map<String, List<String>> projectSources = new HashMap<>();
    private final Map<String, SourceFragment> fragments = new LinkedHashMap<>();
    private final Map<String, List<String>> sourceFragments = new HashMap<>();
    private final Map<String, MemoryEvent> events = new LinkedHashMap<>();
    private final Map<String, List<String>> projectEvents = new HashMap<>();
    private final Set<String> extractedSources = new HashSet<>();
    private int sourceSequence = 1;
    private int fragmentSequence = 1;
    private int eventSequence = 1;

    public synchronized DocumentSource importSource(String projectId, Map<String, Object> payload) {
        requireText(projectId, "project_id");
        String sourceId = stringValue(payload.get("source_id"), nextId("doc", sourceSequence++));
        String title = requiredString(payload, "title");
        String sourceType = requiredString(payload, "source_type");
        String createdAt = stringValue(payload.get("created_at"), OffsetDateTime.now().toString());
        String content = requiredString(payload, "content");
        Map<String, Object> metadata = mapValue(payload.get("metadata"));
        DocumentSource source = new DocumentSource(
                sourceId,
                projectId,
                title,
                sourceType,
                createdAt,
                "sha256:" + sha256(content),
                metadata,
                content
        );
        sources.put(sourceId, source);
        projectSources.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(sourceId);
        createFragments(source);
        return source;
    }

    public synchronized ExtractionResult extract(String projectId) {
        List<String> sourceIds = projectSources.getOrDefault(projectId, List.of());
        int created = 0;
        int uncertain = 0;
        for (String sourceId : sourceIds) {
            if (!extractedSources.add(sourceId)) {
                continue;
            }
            for (String fragmentId : sourceFragments.getOrDefault(sourceId, List.of())) {
                SourceFragment fragment = fragments.get(fragmentId);
                MemoryEvent event = parseEvent(projectId, fragment);
                events.put(event.eventId(), event);
                projectEvents.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(event.eventId());
                created++;
                if ("uncertain".equals(event.status())) {
                    uncertain++;
                }
            }
        }
        return new ExtractionResult(projectId, created, uncertain);
    }

    public synchronized List<TimelineItem> timeline(
            String projectId,
            String from,
            String to,
            String types,
            String subject,
            String status
    ) {
        LocalDate fromDate = parseFilterDate(from);
        LocalDate toDate = parseFilterDate(to);
        Set<String> typeFilter = splitFilter(types);
        Set<String> statusFilter = splitFilter(status);
        String subjectFilter = normalizeNullable(subject);

        return projectEvents.getOrDefault(projectId, List.of()).stream()
                .map(events::get)
                .filter(event -> event != null)
                .filter(event -> typeFilter.isEmpty() || typeFilter.contains(event.eventType()))
                .filter(event -> statusFilter.isEmpty() || statusFilter.contains(event.status()))
                .filter(event -> subjectFilter == null || normalize(event.subject()).contains(subjectFilter))
                .filter(event -> inRange(event, fromDate, toDate))
                .sorted(Comparator.comparing((MemoryEvent event) -> sortableDate(event.occurredAt()))
                        .thenComparing(MemoryEvent::eventId))
                .map(event -> new TimelineItem(
                        event.eventId(),
                        event.occurredAt(),
                        event.eventType(),
                        event.subject(),
                        event.summary(),
                        event.status()
                ))
                .toList();
    }

    public synchronized QueryResult query(String projectId, String question, boolean includeSources) {
        Set<String> queryTerms = searchTerms(question);
        int minimumScore = queryTerms.size() <= 1 ? 1 : 2;
        List<ScoredEvent> matches = projectEvents.getOrDefault(projectId, List.of()).stream()
                .map(events::get)
                .filter(event -> event != null)
                .map(event -> new ScoredEvent(event, score(queryTerms, normalize(event.subject() + " " + event.summary()))))
                .filter(scored -> scored.score >= minimumScore)
                .sorted(Comparator.comparingInt(ScoredEvent::score).reversed()
                        .thenComparing(scored -> sortableDate(scored.event.occurredAt()))
                        .thenComparing(scored -> scored.event.eventId()))
                .limit(5)
                .toList();

        if (matches.isEmpty()) {
            return new QueryResult("资料中未找到充分依据", List.of(), Map.of(), List.of());
        }

        List<String> relatedEvents = matches.stream().map(scored -> scored.event.eventId()).toList();
        String answer = "根据资料，" + String.join("；",
                matches.stream().map(scored -> scored.event.summary()).toList());
        Map<String, String> hint = timelineHint(matches.stream().map(scored -> scored.event).toList());
        List<SourceTrace> sources = includeSources
                ? relatedEvents.stream().flatMap(eventId -> sourcesForEvent(eventId).stream()).toList()
                : List.of();
        return new QueryResult(answer, relatedEvents, hint, sources);
    }

    public synchronized List<SourceTrace> sourcesForEvent(String eventId) {
        MemoryEvent event = events.get(eventId);
        if (event == null) {
            return List.of();
        }
        Map<String, List<SourceFragment>> bySource = new LinkedHashMap<>();
        for (String fragmentId : event.sourceRefs()) {
            SourceFragment fragment = fragments.get(fragmentId);
            if (fragment == null) {
                continue;
            }
            bySource.computeIfAbsent(fragment.sourceId(), ignored -> new ArrayList<>()).add(fragment);
        }
        List<SourceTrace> traces = new ArrayList<>();
        for (Map.Entry<String, List<SourceFragment>> entry : bySource.entrySet()) {
            DocumentSource source = sources.get(entry.getKey());
            if (source != null) {
                traces.add(new SourceTrace(source, List.copyOf(entry.getValue())));
            }
        }
        return traces;
    }

    private void createFragments(DocumentSource source) {
        List<String> ids = new ArrayList<>();
        int paragraph = 1;
        for (String line : source.content().split("\\R")) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            String fragmentId = nextId("frag", fragmentSequence++);
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("page", 1);
            location.put("paragraph", paragraph++);
            SourceFragment fragment = new SourceFragment(fragmentId, source.sourceId(), text, location);
            fragments.put(fragmentId, fragment);
            ids.add(fragmentId);
        }
        sourceFragments.put(source.sourceId(), ids);
    }

    private MemoryEvent parseEvent(String projectId, SourceFragment fragment) {
        String[] parts = fragment.text().split("\\|", 5);
        String rawTime = parts.length > 0 ? parts[0].trim() : "";
        String rawType = parts.length > 1 ? parts[1].trim() : "event";
        String rawStatus = parts.length > 2 ? parts[2].trim() : "active";
        String subject = parts.length > 3 ? parts[3].trim() : "未命名事件";
        String summary = parts.length > 4 ? parts[4].trim() : fragment.text();

        ParsedTime parsedTime = parseEventTime(rawTime);
        String eventType = EVENT_TYPES.contains(rawType) ? rawType : "event";
        String status = STATUSES.contains(rawStatus) ? rawStatus : "uncertain";
        if ("unknown".equals(parsedTime.precision)) {
            status = "uncertain";
        }
        double confidence = "uncertain".equals(status) ? 0.4 : 0.86;
        return new MemoryEvent(
                nextId("mem", eventSequence++),
                projectId,
                eventType,
                subject.isBlank() ? "未命名事件" : subject,
                summary.isBlank() ? fragment.text() : summary,
                parsedTime.value,
                parsedTime.precision,
                status,
                confidence,
                List.of(fragment.fragmentId()),
                List.<EventRelation>of()
        );
    }

    private ParsedTime parseEventTime(String value) {
        if (value == null || value.isBlank()) {
            return new ParsedTime(null, "unknown");
        }
        try {
            return new ParsedTime(OffsetDateTime.parse(value).toString(), "exact");
        } catch (DateTimeParseException ignored) {
            try {
                return new ParsedTime(LocalDate.parse(value).toString(), "date");
            } catch (DateTimeParseException ignoredAgain) {
                return new ParsedTime(null, "unknown");
            }
        }
    }

    private boolean inRange(MemoryEvent event, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        LocalDate eventDate = parseEventDate(event.occurredAt());
        if (eventDate == null) {
            return false;
        }
        return (from == null || !eventDate.isBefore(from)) && (to == null || !eventDate.isAfter(to));
    }

    private Map<String, String> timelineHint(List<MemoryEvent> matchedEvents) {
        List<LocalDate> dates = matchedEvents.stream()
                .map(event -> parseEventDate(event.occurredAt()))
                .filter(date -> date != null)
                .sorted()
                .toList();
        if (dates.isEmpty()) {
            return Map.of();
        }
        Map<String, String> hint = new LinkedHashMap<>();
        hint.put("from", dates.getFirst().toString());
        hint.put("to", dates.getLast().toString());
        return hint;
    }

    private int score(Set<String> queryTerms, String eventText) {
        int result = 0;
        for (String term : queryTerms) {
            if (eventText.contains(term)) {
                result++;
            }
        }
        return result;
    }

    private Set<String> searchTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
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
        return terms;
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
        if (token.length() == 2) {
            terms.add(token);
            return;
        }
        if (token.length() > 2) {
            terms.add(token);
            for (int i = 0; i <= token.length() - 2; i++) {
                terms.add(token.substring(i, i + 2));
            }
            for (int i = 0; i <= token.length() - 3; i++) {
                terms.add(token.substring(i, i + 3));
            }
        }
    }

    private LocalDate parseFilterDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date: " + value, e);
        }
    }

    private LocalDate parseEventDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return LocalDate.parse(value);
        }
    }

    private LocalDate sortableDate(String value) {
        LocalDate date = parseEventDateSafely(value);
        return date == null ? LocalDate.MAX : date;
    }

    private LocalDate parseEventDateSafely(String value) {
        try {
            return parseEventDate(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Set<String> splitFilter(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
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

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private String requiredString(Map<String, Object> payload, String fieldName) {
        Object value = payload.get(fieldName);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return String.valueOf(value);
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private String normalizeNullable(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    private String nextId(String prefix, int value) {
        return "%s_%03d".formatted(prefix, value);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append("%02x".formatted(b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ExtractionResult(String projectId, int createdEvents, int uncertainEvents) {
    }

    public record QueryResult(
            String answer,
            List<String> relatedEvents,
            Map<String, String> timelineHint,
            List<SourceTrace> sources
    ) {
    }

    public record SourceTrace(DocumentSource source, List<SourceFragment> fragments) {
    }

    private record ParsedTime(String value, String precision) {
    }

    private record ScoredEvent(MemoryEvent event, int score) {
    }
}
