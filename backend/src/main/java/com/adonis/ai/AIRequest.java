package com.adonis.ai;

import java.util.Collections;
import java.util.Map;

/**
 * Normalized provider-neutral request model for AI completions.
 */
public record AIRequest(
        String provider,
        String model,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Integer maxTokens,
        String jsonSchema,
        Map<String, Object> metadata
) {
    public AIRequest {
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String provider;
        private String model;
        private String systemPrompt;
        private String userPrompt;
        private Double temperature;
        private Integer maxTokens;
        private String jsonSchema;
        private Map<String, Object> metadata;

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder jsonSchema(String jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public AIRequest build() {
            return new AIRequest(
                    provider,
                    model,
                    systemPrompt,
                    userPrompt,
                    temperature,
                    maxTokens,
                    jsonSchema,
                    metadata
            );
        }
    }
}
