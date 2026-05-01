package com.memorysystem.model;

import java.util.List;

public record ProjectMemoryRunResult(
        String projectId,
        int messagesRead,
        int decisionsExtracted,
        int matchesTriggered,
        CommandResult read,
        List<CardResult> cards
) {
    public record CommandResult(
            int exitCode,
            String stdout,
            String stderr,
            List<String> command
    ) {
    }

    public record CardResult(
            String decisionId,
            String triggerMessageId,
            String idempotencyKey,
            String markdown,
            boolean sent,
            int exitCode,
            String stdout,
            String stderr,
            List<String> command
    ) {
    }
}
