package com.memorysystem.model;

public record TimelineItem(
        String eventId,
        String occurredAt,
        String eventType,
        String title,
        String summary,
        String status
) {
}
