package com.adonis.ai;

import java.util.Collections;
import java.util.Map;

/**
 * Normalized provider-neutral response model from AI completions.
 */
public record AIResponse(
        String text,
        String provider,
        String model,
        AIUsage usage,
        Map<String, Object> metadata
) {
    public AIResponse {
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
        if (usage == null) {
            usage = AIUsage.empty();
        }
    }

    public static AIResponse of(String text, String provider, String model, AIUsage usage) {
        return new AIResponse(text, provider, model, usage, Collections.emptyMap());
    }

    public static AIResponse of(String text, String provider, String model, AIUsage usage, Map<String, Object> metadata) {
        return new AIResponse(text, provider, model, usage, metadata);
    }
}
