package com.adonis.ai;

/**
 * Provider-neutral interface for external AI services.
 * Implementations wrap vendor-specific HTTP protocols (OpenAI, Gemini, etc.).
 */
public interface AIProvider {

    /**
     * Unique identifier for the provider (e.g., "openai", "gemini").
     */
    String getProviderName();

    /**
     * Returns true if this provider supports the given provider identifier.
     */
    default boolean supports(String providerName) {
        if (providerName == null) return false;
        return getProviderName().equalsIgnoreCase(providerName.trim());
    }

    /**
     * Executes the normalized AI completion request and returns a normalized response.
     *
     * @param request provider-neutral request containing model, prompts, parameters
     * @return provider-neutral response containing generated text, usage metrics, metadata
     * @throws AIProviderException on vendor API errors, timeouts, or invalid responses
     */
    AIResponse generate(AIRequest request) throws AIProviderException;
}
