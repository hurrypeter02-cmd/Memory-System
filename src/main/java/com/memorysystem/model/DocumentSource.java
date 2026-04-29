package com.memorysystem.model;

import java.util.Map;

public record DocumentSource(
        String sourceId,
        String projectId,
        String title,
        String sourceType,
        String createdAt,
        String contentHash,
        Map<String, Object> metadata,
        String content
) {
}
