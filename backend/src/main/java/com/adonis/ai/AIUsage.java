package com.adonis.ai;

/**
 * Normalized token usage metrics returned by AI providers.
 */
public record AIUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
    public static AIUsage of(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        return new AIUsage(promptTokens, completionTokens, totalTokens);
    }

    public static AIUsage empty() {
        return new AIUsage(0, 0, 0);
    }
}
