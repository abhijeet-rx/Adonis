package com.adonis.execution;

import com.adonis.model.WorkflowNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpRequestNodeExecutor implements NodeExecutor {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient httpClient;

    public HttpRequestNodeExecutor() {
        this(null);
    }

    @Autowired(required = false)
    public HttpRequestNodeExecutor(HttpClient httpClient) {
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean supports(String nodeType) {
        if (nodeType == null) return false;
        String normalized = nodeType.trim().toLowerCase();
        return "httprequest".equals(normalized) || "http-request".equals(normalized);
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
        Instant startedAt = Instant.now();
        Map<String, Object> nodeData = node.getData() != null ? node.getData() : Map.of();

        Object rawUrl = nodeData.get("url");
        if (rawUrl == null || rawUrl.toString().trim().isEmpty()) {
            return NodeExecutionResult.failure(
                    node.getId(),
                    node.getType(),
                    startedAt,
                    Instant.now(),
                    input != null ? input : Map.of(),
                    "Missing required 'url' configuration for HTTP request node"
            );
        }

        String url = rawUrl.toString().trim();
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
                return NodeExecutionResult.failure(
                        node.getId(),
                        node.getType(),
                        startedAt,
                        Instant.now(),
                        input != null ? input : Map.of(),
                        "URL must use http or https scheme: " + url
                );
            }
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(
                    node.getId(),
                    node.getType(),
                    startedAt,
                    Instant.now(),
                    input != null ? input : Map.of(),
                    "Malformed URL: " + e.getMessage()
            );
        }

        String method = nodeData.getOrDefault("method", "GET").toString().trim().toUpperCase();

        String bodyContent = null;
        if (nodeData.containsKey("body") && nodeData.get("body") != null) {
            bodyContent = nodeData.get("body").toString();
        }

        HttpRequest.BodyPublisher bodyPublisher;
        switch (method) {
            case "GET" -> bodyPublisher = HttpRequest.BodyPublishers.noBody();
            case "DELETE" -> bodyPublisher = HttpRequest.BodyPublishers.noBody();
            case "POST", "PUT", "PATCH" -> bodyPublisher = bodyContent != null
                    ? HttpRequest.BodyPublishers.ofString(bodyContent)
                    : HttpRequest.BodyPublishers.noBody();
            default -> {
                return NodeExecutionResult.failure(
                        node.getId(),
                        node.getType(),
                        startedAt,
                        Instant.now(),
                        input != null ? input : Map.of(),
                        "Unsupported HTTP method: " + method
                );
            }
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(DEFAULT_TIMEOUT)
                .method(method, bodyPublisher);

        // Headers
        if (bodyContent != null && !bodyContent.isEmpty() && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
            requestBuilder.header("Content-Type", "application/json");
        }

        Object headersObj = nodeData.get("headers");
        if (headersObj instanceof Map<?, ?> headersMap) {
            for (Map.Entry<?, ?> entry : headersMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    requestBuilder.header(entry.getKey().toString(), entry.getValue().toString());
                }
            }
        }

        Map<String, Object> recordedInput = new LinkedHashMap<>();
        recordedInput.put("url", url);
        recordedInput.put("method", method);
        if (headersObj instanceof Map<?, ?>) {
            recordedInput.put("headers", headersObj);
        }
        if (bodyContent != null) {
            recordedInput.put("body", bodyContent);
        }
        if (input != null && !input.isEmpty()) {
            recordedInput.put("upstream", input);
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Instant completedAt = Instant.now();

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("statusCode", response.statusCode());
            output.put("success", response.statusCode() >= 200 && response.statusCode() < 400);

            Map<String, String> headerMap = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    headerMap.put(k, v.getFirst());
                }
            });
            output.put("headers", headerMap);
            output.put("body", response.body() != null ? response.body() : "");

            return NodeExecutionResult.success(node.getId(), node.getType(), startedAt, completedAt, recordedInput, output);
        } catch (HttpTimeoutException e) {
            return NodeExecutionResult.failure(
                    node.getId(),
                    node.getType(),
                    startedAt,
                    Instant.now(),
                    recordedInput,
                    "HTTP request timed out after " + DEFAULT_TIMEOUT.toSeconds() + "s: " + e.getMessage()
            );
        } catch (IOException e) {
            return NodeExecutionResult.failure(
                    node.getId(),
                    node.getType(),
                    startedAt,
                    Instant.now(),
                    recordedInput,
                    "HTTP connection failed: " + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NodeExecutionResult.failure(
                    node.getId(),
                    node.getType(),
                    startedAt,
                    Instant.now(),
                    recordedInput,
                    "HTTP request interrupted: " + e.getMessage()
            );
        } catch (Exception e) {
            return NodeExecutionResult.failure(
                    node.getId(),
                    node.getType(),
                    startedAt,
                    Instant.now(),
                    recordedInput,
                    "HTTP request unexpected error: " + e.getMessage()
            );
        }
    }
}
