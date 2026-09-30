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
public class GeminiProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final String PROVIDER_NAME = "gemini";

    private final AIProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiProvider(AIProperties properties) {
        this(properties, null, new ObjectMapper());
    }

    public GeminiProvider(AIProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
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
        String apiKey = properties.getGemini().getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AIProviderException(
                    "Google Gemini API key is not configured (set GEMINI_API_KEY environment variable)",
                    PROVIDER_NAME,
                    request.model(),
                    401,
                    false
            );
        }

        String model = request.model();
        if (model == null || model.trim().isEmpty()) {
            throw new AIProviderException(
                    "Model name must not be blank for Gemini request",
                    PROVIDER_NAME,
                    "",
                    400,
                    false
            );
        }

        // Clean model name (e.g. if user specified "gemini-1.5-flash" or "models/gemini-1.5-flash")
        String cleanModel = model.trim();
        if (cleanModel.startsWith("models/")) {
            cleanModel = cleanModel.substring("models/".length());
        }

        String baseUrl = properties.getGemini().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = baseUrl + "/models/" + cleanModel + ":generateContent";

        Map<String, Object> payload = buildPayload(request);
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new AIProviderException("Failed to serialize Gemini request: " + e.getMessage(),
                    PROVIDER_NAME, cleanModel, 400, false, e);
        }

        Duration readTimeout = Duration.ofMillis(properties.getTimeout().getReadMs());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode >= 200 && statusCode < 300) {
                return parseSuccessResponse(responseBody, cleanModel);
            }

            // Error response
            String errorDetail = extractErrorMessage(responseBody, statusCode);
            boolean retryable = isRetryableStatusCode(statusCode, errorDetail);
            throw new AIProviderException(
                    "Gemini API error [HTTP " + statusCode + "]: " + errorDetail,
                    PROVIDER_NAME,
                    cleanModel,
                    statusCode,
                    retryable
            );
        } catch (HttpTimeoutException e) {
            throw new AIProviderException(
                    "Gemini request timed out after " + readTimeout.toSeconds() + "s: " + e.getMessage(),
                    PROVIDER_NAME,
                    cleanModel,
                    408,
                    true,
                    e
            );
        } catch (IOException e) {
            throw new AIProviderException(
                    "Gemini connection failure: " + e.getMessage(),
                    PROVIDER_NAME,
                    cleanModel,
                    503,
                    true,
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIProviderException(
                    "Gemini request interrupted: " + e.getMessage(),
                    PROVIDER_NAME,
                    cleanModel,
                    500,
                    false,
                    e
            );
        }
    }

    private Map<String, Object> buildPayload(AIRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // Contents (User prompt)
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> userContent = new LinkedHashMap<>();
        userContent.put("role", "user");
        userContent.put("parts", List.of(Map.of("text", request.userPrompt() != null ? request.userPrompt() : "")));
        contents.add(userContent);
        payload.put("contents", contents);

        // System Instruction
        if (request.systemPrompt() != null && !request.systemPrompt().trim().isEmpty()) {
            Map<String, Object> systemInstruction = new LinkedHashMap<>();
            systemInstruction.put("parts", List.of(Map.of("text", request.systemPrompt().trim())));
            payload.put("system_instruction", systemInstruction);
        }

        // Generation Config
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (request.temperature() != null) {
            generationConfig.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            generationConfig.put("maxOutputTokens", request.maxTokens());
        }
        if (request.jsonSchema() != null && !request.jsonSchema().trim().isEmpty()) {
            generationConfig.put("responseMimeType", "application/json");
        }

        if (!generationConfig.isEmpty()) {
            payload.put("generationConfig", generationConfig);
        }

        return payload;
    }

    private AIResponse parseSuccessResponse(String responseBody, String requestedModel) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String text = "";

            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.get("content");
                if (content != null && content.has("parts") && content.get("parts").isArray()) {
                    JsonNode parts = content.get("parts");
                    if (!parts.isEmpty()) {
                        JsonNode firstPart = parts.get(0);
                        if (firstPart.has("text") && !firstPart.get("text").isNull()) {
                            text = firstPart.get("text").asText();
                        }
                    }
                }
            }

            AIUsage usage = AIUsage.empty();
            JsonNode usageMetadata = root.get("usageMetadata");
            if (usageMetadata != null && usageMetadata.isObject()) {
                int promptTokens = usageMetadata.has("promptTokenCount") ? usageMetadata.get("promptTokenCount").asInt(0) : 0;
                int completionTokens = usageMetadata.has("candidatesTokenCount") ? usageMetadata.get("candidatesTokenCount").asInt(0) : 0;
                int totalTokens = usageMetadata.has("totalTokenCount") ? usageMetadata.get("totalTokenCount").asInt(0) : promptTokens + completionTokens;
                usage = AIUsage.of(promptTokens, completionTokens, totalTokens);
            }

            String effectiveModel = root.has("modelVersion") ? root.get("modelVersion").asText() : requestedModel;
            return AIResponse.of(text, PROVIDER_NAME, effectiveModel, usage);
        } catch (Exception e) {
            throw new AIProviderException(
                    "Failed to parse Gemini success response: " + e.getMessage(),
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

    private boolean isRetryableStatusCode(int statusCode, String errorDetail) {
        if (statusCode == 408 || statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return true;
        }
        if (errorDetail != null) {
            String lower = errorDetail.toLowerCase();
            return lower.contains("resource_exhausted") || lower.contains("quota exceeded") || lower.contains("rate limit");
        }
        return false;
    }
}
