package com.memorysystem.model;

import java.util.List;

public record ProjectDecisionMemory(
        String decisionId,
        String projectId,
        String subject,
        String decision,
        String reason,
        String objection,
        String conclusion,
        String stage,
        String occurredAt,
        String sourceMessageId,
        String sourceChatId,
        List<String> keywords
) {
}
