package com.memorysystem.model;

public record EventRelation(
        String relationType,
        String targetEventId
) {
}
