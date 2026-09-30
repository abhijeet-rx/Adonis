package com.adonis.ai.provider;

import com.adonis.ai.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAIProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProvider.class);
    private static final String PROVIDER_NAME = "openai";

    private final AIProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenAIProvider(AIProperties properties) {
        this(properties, null, new ObjectMapper());
    }

    public OpenAIProvider(AIProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new AIProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.properties.getTimeout().getConnectMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public AIResponse generate(AIRequest request) throws AIProviderException {
        String apiKey = properties.getOpenai().getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AIProviderException(
                    "OpenAI API key is not configured (set OPENAI_API_KEY environment variable)",
                    PROVIDER_NAME,
                    request.model(),
                    401,
                    false
            );
        }

        String model = request.model();
        if (model == null || model.trim().isEmpty()) {
            throw new AIProviderException(
                    "Model name must not be blank for OpenAI request",
                    PROVIDER_NAME,
                    "",
                    400,
                    false
            );
        }

        String baseUrl = properties.getOpenai().getBaseUrl();
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        Map<String, Object> payload = buildPayload(request);
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new AIProviderException("Failed to serialize OpenAI request: " + e.getMessage(),
                    PROVIDER_NAME, model, 400, false, e);
        }

        Duration readTimeout = Duration.ofMillis(properties.getTimeout().getReadMs());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode >= 200 && statusCode < 300) {
                return parseSuccessResponse(responseBody, model);
            }

            // Error response
            String errorDetail = extractErrorMessage(responseBody, statusCode);
            boolean retryable = isRetryableStatusCode(statusCode);
            throw new AIProviderException(
                    "OpenAI API error [HTTP " + statusCode + "]: " + errorDetail,
                    PROVIDER_NAME,
                    model,
                    statusCode,
                    retryable
            );
        } catch (HttpTimeoutException e) {
            throw new AIProviderException(
                    "OpenAI request timed out after " + readTimeout.toSeconds() + "s: " + e.getMessage(),
                    PROVIDER_NAME,
                    model,
                    408,
                    true,
                    e
            );
        } catch (IOException e) {
            throw new AIProviderException(
                    "OpenAI connection failure: " + e.getMessage(),
                    PROVIDER_NAME,
                    model,
                    503,
                    true,
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIProviderException(
                    "OpenAI request interrupted: " + e.getMessage(),
                    PROVIDER_NAME,
                    model,
                    500,
                    false,
                    e
            );
        }
    }

    private Map<String, Object> buildPayload(AIRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model().trim());

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().trim().isEmpty()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt().trim()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt() != null ? request.userPrompt() : ""));
        payload.put("messages", messages);

        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            payload.put("max_tokens", request.maxTokens());
        }

        // Structured output enforcement
        if (request.jsonSchema() != null && !request.jsonSchema().trim().isEmpty()) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        return payload;
    }

    private AIResponse parseSuccessResponse(String responseBody, String requestedModel) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String text = "";
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null && message.has("content") && !message.get("content").isNull()) {
                    text = message.get("content").asText();
                }
            }

            String effectiveModel = root.has("model") ? root.get("model").asText() : requestedModel;

            AIUsage usage = AIUsage.empty();
            JsonNode usageNode = root.get("usage");
            if (usageNode != null && usageNode.isObject()) {
                int promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt(0) : 0;
                int completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt(0) : 0;
                int totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt(0) : promptTokens + completionTokens;
                usage = AIUsage.of(promptTokens, completionTokens, totalTokens);
            }

            return AIResponse.of(text, PROVIDER_NAME, effectiveModel, usage);
        } catch (Exception e) {
            throw new AIProviderException(
                    "Failed to parse OpenAI success response: " + e.getMessage(),
                    PROVIDER_NAME,
                    requestedModel,
                    500,
                    false,
                    e
            );
        }
    }

    private String extractErrorMessage(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.isBlank()) {
            return "HTTP " + statusCode;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("error") && root.get("error").isObject()) {
                JsonNode err = root.get("error");
                if (err.has("message") && !err.get("message").isNull()) {
                    return err.get("message").asText();
                }
            }
        } catch (Exception ignored) {
        }
        return responseBody.length() > 300 ? responseBody.substring(0, 300) + "..." : responseBody;
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }
}
