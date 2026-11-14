package com.hitesh.quiz;

import java.util.List;

/**
 * Full question model including correctIndex and sourceUrl.
 * Loaded from questions.json and stored on the server.
 */
public record Question(
        int id,
        String text,
        List<String> options,
        int correctIndex,
        String sourceUrl
) {
    /**
     * Safe DTO sent to players while question is active.
     * STRICT RULE 1: Never contains correctIndex.
     */
    public record PlayerQuestionDto(
            int id,
            int questionNumber,
            int totalQuestions,
            String text,
            List<String> options,
            long serverNow,
            long endsAt
    ) {}

    /**
     * Creates a safe player-facing DTO with timing information.
     */
    public PlayerQuestionDto toPlayerDto(int questionNumber, int totalQuestions, long serverNow, long endsAt) {
        return new PlayerQuestionDto(
                id,
                questionNumber,
                totalQuestions,
                text,
                options,
                serverNow,
                endsAt
        );
    }
}
