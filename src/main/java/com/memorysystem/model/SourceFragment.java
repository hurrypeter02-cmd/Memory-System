package com.memorysystem.model;

import java.util.Map;

public record SourceFragment(
        String fragmentId,
        String sourceId,
        String text,
        Map<String, Object> location
) {
}
