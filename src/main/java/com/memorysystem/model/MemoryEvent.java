package com.memorysystem.model;

import java.util.List;

public record MemoryEvent(
        String eventId,
        String projectId,
        String eventType,
        String subject,
        String summary,
        String occurredAt,
        String timePrecision,
        String status,
        double confidence,
        List<String> sourceRefs,
        List<EventRelation> relations
) {
}
