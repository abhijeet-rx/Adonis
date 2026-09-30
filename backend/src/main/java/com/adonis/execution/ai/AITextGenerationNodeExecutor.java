package com.adonis.execution.ai;

import com.adonis.ai.*;
import com.adonis.ai.util.PromptInterpolator;
import com.adonis.exception.WorkflowValidationException;
import com.adonis.execution.ExecutionContext;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.execution.NodeExecutor;
import com.adonis.model.WorkflowNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class AITextGenerationNodeExecutor implements NodeExecutor {

    public static final String NODE_TYPE = "ai_text_generation";
    private final AIProviderService aiProviderService;

    @Autowired
    public AITextGenerationNodeExecutor(AIProviderService aiProviderService) {
        this.aiProviderService = aiProviderService;
    }

    @Override
    public boolean supports(String nodeType) {
        if (nodeType == null) return false;
        String normalized = nodeType.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return "ai_text_generation".equals(normalized) || "aitextgeneration".equals(normalized);
    }

    @Override
    public void validate(WorkflowNode node) {
        Map<String, Object> data = node.getData() != null ? node.getData() : Map.of();

        // 1. Never allow API keys in workflow data
        if (data.containsKey("apiKey") || data.containsKey("api_key") || data.containsKey("token")) {
            throw new WorkflowValidationException(
                    "Node '" + node.getId() + "' [" + node.getType() + "] must not contain API keys in configuration. Use environment variables instead."
            );
        }

        // 2. Validate provider
        Object providerObj = data.get("provider");
        if (providerObj == null || providerObj.toString().trim().isEmpty()) {
            throw new WorkflowValidationException(
                    "Missing required 'provider' configuration for AI node '" + node.getId() + "'"
            );
        }
        String provider = providerObj.toString().trim();
        if (aiProviderService != null && !aiProviderService.isSupported(provider)) {
            throw new WorkflowValidationException(
                    "Unsupported AI provider '" + provider + "' for node '" + node.getId() + "'. Supported: " + aiProviderService.getSupportedProviderNames()
            );
        }

        // 3. Validate model
        Object modelObj = data.get("model");
        if (modelObj == null || modelObj.toString().trim().isEmpty()) {
            throw new WorkflowValidationException(
                    "Missing required 'model' configuration for AI node '" + node.getId() + "'"
            );
        }

        // 4. Validate prompt
        Object promptObj = data.get("userPrompt") != null ? data.get("userPrompt") : data.get("prompt");
        if (promptObj == null || promptObj.toString().trim().isEmpty()) {
            throw new WorkflowValidationException(
                    "Missing required 'userPrompt' or 'prompt' configuration for AI node '" + node.getId() + "'"
            );
        }

        // 5. Validate temperature if present
        if (data.containsKey("temperature") && data.get("temperature") != null) {
            try {
                double temp = Double.parseDouble(data.get("temperature").toString().trim());
                if (temp < 0.0 || temp > 2.0) {
                    throw new WorkflowValidationException(
                            "Invalid temperature " + temp + " for AI node '" + node.getId() + "'. Must be between 0.0 and 2.0"
                    );
                }
            } catch (NumberFormatException e) {
                throw new WorkflowValidationException(
                        "Invalid temperature value for AI node '" + node.getId() + "': " + e.getMessage()
                );
            }
        }

        // 6. Validate maxTokens if present
        if (data.containsKey("maxTokens") && data.get("maxTokens") != null) {
            try {
                int maxTokens = Integer.parseInt(data.get("maxTokens").toString().trim());
                if (maxTokens <= 0) {
                    throw new WorkflowValidationException(
                            "Invalid maxTokens " + maxTokens + " for AI node '" + node.getId() + "'. Must be greater than 0"
                    );
                }
            } catch (NumberFormatException e) {
                throw new WorkflowValidationException(
                        "Invalid maxTokens value for AI node '" + node.getId() + "': " + e.getMessage()
                );
            }
        }
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
        Instant startedAt = Instant.now();
        Map<String, Object> nodeData = node.getData() != null ? node.getData() : Map.of();

        String provider = nodeData.getOrDefault("provider", "").toString().trim();
        String model = nodeData.getOrDefault("model", "").toString().trim();

        String rawSystemPrompt = nodeData.containsKey("systemPrompt") && nodeData.get("systemPrompt") != null
                ? nodeData.get("systemPrompt").toString()
                : null;
        String rawUserPrompt = nodeData.containsKey("userPrompt") && nodeData.get("userPrompt") != null
                ? nodeData.get("userPrompt").toString()
                : nodeData.getOrDefault("prompt", "").toString();

        Double temperature = null;
        if (nodeData.containsKey("temperature") && nodeData.get("temperature") != null) {
            try {
                temperature = Double.parseDouble(nodeData.get("temperature").toString().trim());
            } catch (Exception ignored) {
            }
        }

        Integer maxTokens = null;
        if (nodeData.containsKey("maxTokens") && nodeData.get("maxTokens") != null) {
            try {
                maxTokens = Integer.parseInt(nodeData.get("maxTokens").toString().trim());
            } catch (Exception ignored) {
            }
        }

        // Interpolate prompts
        String interpolatedSystem = rawSystemPrompt != null ? PromptInterpolator.interpolate(rawSystemPrompt, input, context) : null;
        String interpolatedUser = PromptInterpolator.interpolate(rawUserPrompt, input, context);

        Map<String, Object> recordedInput = new LinkedHashMap<>();
        recordedInput.put("provider", provider);
        recordedInput.put("model", model);
        if (interpolatedSystem != null) {
            recordedInput.put("systemPrompt", interpolatedSystem);
        }
        recordedInput.put("userPrompt", interpolatedUser);
        if (temperature != null) {
            recordedInput.put("temperature", temperature);
        }
        if (maxTokens != null) {
            recordedInput.put("maxTokens", maxTokens);
        }
        if (input != null && !input.isEmpty()) {
            recordedInput.put("upstream", input);
        }

        AIRequest aiRequest = AIRequest.builder()
                .provider(provider)
                .model(model)
                .systemPrompt(interpolatedSystem)
                .userPrompt(interpolatedUser)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        try {
            AIResponse response = aiProviderService.generate(aiRequest);
            Instant completedAt = Instant.now();

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("text", response.text());
            output.put("provider", response.provider());
            output.put("model", response.model());

            Map<String, Object> usageMap = new LinkedHashMap<>();
            usageMap.put("promptTokens", response.usage().promptTokens());
            usageMap.put("completionTokens", response.usage().completionTokens());
            usageMap.put("totalTokens", response.usage().totalTokens());
            output.put("usage", usageMap);

            return NodeExecutionResult.success(node.getId(), node.getType(), startedAt, completedAt, recordedInput, output);
        } catch (AIProviderException e) {
            Instant completedAt = Instant.now();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("statusCode", e.getStatusCode());
            output.put("provider", e.getProvider());
            output.put("model", e.getModel());

            String errorMessage = "AI provider '" + e.getProvider() + "' failed: " + e.getMessage();
            return NodeExecutionResult.failure(node.getId(), node.getType(), startedAt, completedAt, recordedInput, output, errorMessage);
        } catch (Exception e) {
            Instant completedAt = Instant.now();
            String errorMessage = "AI text generation execution unexpected error: " + e.getMessage();
            return NodeExecutionResult.failure(node.getId(), node.getType(), startedAt, completedAt, recordedInput, errorMessage);
        }
    }
}
