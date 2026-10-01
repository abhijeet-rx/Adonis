package com.adonis.integration.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight, in-process HTTP mock server utilizing the JDK built-in HttpServer.
 * Mocks local endpoints for HTTP Node tests, OpenAI Provider tests, and Gemini Provider tests
 * without contacting external public APIs.
 */
public class LocalMockHttpServer {

    public record RecordedHttpRequest(
            String method,
            URI uri,
            Map<String, List<String>> headers,
            String body
    ) {
        public String getHeader(String headerName) {
            if (headers == null || headerName == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (headerName.equalsIgnoreCase(entry.getKey())) {
                    List<String> values = entry.getValue();
                    return values != null && !values.isEmpty() ? values.get(0) : null;
                }
            }
            return null;
        }
    }

    public record MockResponse(
            int statusCode,
            String body,
            Map<String, String> headers,
            Duration delay,
            boolean closeConnectionWithoutResponse
    ) {
        public static MockResponse of(int statusCode, String body) {
            return new MockResponse(statusCode, body, Map.of("Content-Type", "application/json"), Duration.ZERO, false);
        }

        public static MockResponse of(int statusCode, String body, Map<String, String> headers) {
            return new MockResponse(statusCode, body, headers != null ? headers : Map.of(), Duration.ZERO, false);
        }

        public static MockResponse delayed(int statusCode, String body, Duration delay) {
            return new MockResponse(statusCode, body, Map.of("Content-Type", "application/json"), delay, false);
        }

        public static MockResponse drop() {
            return new MockResponse(0, "", Map.of(), Duration.ZERO, true);
        }
    }

    private HttpServer server;
    private int port;

    private final Queue<MockResponse> httpResponses = new ConcurrentLinkedQueue<>();
    private final Queue<MockResponse> openAiResponses = new ConcurrentLinkedQueue<>();
    private final Queue<MockResponse> geminiResponses = new ConcurrentLinkedQueue<>();

    private volatile MockResponse defaultHttpResponse = MockResponse.of(200, "{\"status\":\"ok\"}");
    private volatile MockResponse defaultOpenAiResponse = MockResponse.of(200, buildOpenAiSuccessJson("Default OpenAI response", "gpt-4o", 10, 15));
    private volatile MockResponse defaultGeminiResponse = MockResponse.of(200, buildGeminiSuccessJson("Default Gemini response", "gemini-1.5-flash", 10, 15));

    private final List<RecordedHttpRequest> recordedHttpRequests = new CopyOnWriteArrayList<>();
    private final List<RecordedHttpRequest> recordedOpenAiRequests = new CopyOnWriteArrayList<>();
    private final List<RecordedHttpRequest> recordedGeminiRequests = new CopyOnWriteArrayList<>();

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();

            server.createContext("/mock/http", createHandler(httpResponses, () -> defaultHttpResponse, recordedHttpRequests));
            server.createContext("/openai/chat/completions", createHandler(openAiResponses, () -> defaultOpenAiResponse, recordedOpenAiRequests));
            server.createContext("/gemini/models", createHandler(geminiResponses, () -> defaultGeminiResponse, recordedGeminiRequests));

            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start LocalMockHttpServer", e);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public void reset() {
        httpResponses.clear();
        openAiResponses.clear();
        geminiResponses.clear();
        recordedHttpRequests.clear();
        recordedOpenAiRequests.clear();
        recordedGeminiRequests.clear();
        defaultHttpResponse = MockResponse.of(200, "{\"status\":\"ok\"}");
        defaultOpenAiResponse = MockResponse.of(200, buildOpenAiSuccessJson("Default OpenAI response", "gpt-4o", 10, 15));
        defaultGeminiResponse = MockResponse.of(200, buildGeminiSuccessJson("Default Gemini response", "gemini-1.5-flash", 10, 15));
    }

    public int getPort() {
        return port;
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public String getHttpEndpointUrl() {
        return getBaseUrl() + "/mock/http";
    }

    public String getOpenAiBaseUrl() {
        return getBaseUrl() + "/openai";
    }

    public String getGeminiBaseUrl() {
        return getBaseUrl() + "/gemini";
    }

    public void enqueueHttpResponse(int statusCode, String body) {
        httpResponses.add(MockResponse.of(statusCode, body));
    }

    public void enqueueHttpResponse(MockResponse response) {
        httpResponses.add(response);
    }

    public void setDefaultHttpResponse(int statusCode, String body) {
        this.defaultHttpResponse = MockResponse.of(statusCode, body);
    }

    public void enqueueOpenAiResponse(int statusCode, String body) {
        openAiResponses.add(MockResponse.of(statusCode, body));
    }

    public void enqueueOpenAiResponse(MockResponse response) {
        openAiResponses.add(response);
    }

    public void setDefaultOpenAiResponse(int statusCode, String body) {
        this.defaultOpenAiResponse = MockResponse.of(statusCode, body);
    }

    public void enqueueGeminiResponse(int statusCode, String body) {
        geminiResponses.add(MockResponse.of(statusCode, body));
    }

    public void enqueueGeminiResponse(MockResponse response) {
        geminiResponses.add(response);
    }

    public void setDefaultGeminiResponse(int statusCode, String body) {
        this.defaultGeminiResponse = MockResponse.of(statusCode, body);
    }

    public List<RecordedHttpRequest> getRecordedHttpRequests() {
        return Collections.unmodifiableList(recordedHttpRequests);
    }

    public List<RecordedHttpRequest> getRecordedOpenAiRequests() {
        return Collections.unmodifiableList(recordedOpenAiRequests);
    }

    public List<RecordedHttpRequest> getRecordedGeminiRequests() {
        return Collections.unmodifiableList(recordedGeminiRequests);
    }

    private HttpHandler createHandler(
            Queue<MockResponse> responseQueue,
            java.util.function.Supplier<MockResponse> defaultResponseSupplier,
            List<RecordedHttpRequest> recordedList) {
        return exchange -> {
            try {
                // Record incoming request
                String body = readBody(exchange.getRequestBody());
                RecordedHttpRequest recorded = new RecordedHttpRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI(),
                        new LinkedHashMap<>(exchange.getRequestHeaders()),
                        body
                );
                recordedList.add(recorded);

                // Determine response
                MockResponse response = responseQueue.poll();
                if (response == null) {
                    response = defaultResponseSupplier.get();
                }

                if (response.delay() != null && !response.delay().isZero()) {
                    try {
                        Thread.sleep(response.delay().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (response.closeConnectionWithoutResponse()) {
                    exchange.close();
                    return;
                }

                byte[] responseBytes = response.body() != null
                        ? response.body().getBytes(StandardCharsets.UTF_8)
                        : new byte[0];

                Headers responseHeaders = exchange.getResponseHeaders();
                if (response.headers() != null) {
                    response.headers().forEach(responseHeaders::set);
                }

                int code = response.statusCode();
                if (code == 204 || responseBytes.length == 0) {
                    exchange.sendResponseHeaders(code, -1);
                } else {
                    exchange.sendResponseHeaders(code, responseBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                }
            } finally {
                exchange.close();
            }
        };
    }

    private static String readBody(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public static String buildOpenAiSuccessJson(String content, String model, int promptTokens, int completionTokens) {
        String escaped = escapeJson(content);
        return String.format(
                """
                {
                  "id": "chatcmpl-%s",
                  "object": "chat.completion",
                  "created": 1727784000,
                  "model": "%s",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": %d,
                    "completion_tokens": %d,
                    "total_tokens": %d
                  }
                }
                """,
                UUID.randomUUID(), model, escaped, promptTokens, completionTokens, promptTokens + completionTokens
        );
    }

    public static String buildGeminiSuccessJson(String content, String model, int promptTokens, int completionTokens) {
        String escaped = escapeJson(content);
        return String.format(
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "%s"
                          }
                        ],
                        "role": "model"
                      },
                      "finishReason": "STOP",
                      "index": 0
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": %d,
                    "candidatesTokenCount": %d,
                    "totalTokenCount": %d
                  },
                  "modelVersion": "%s"
                }
                """,
                escaped, promptTokens, completionTokens, promptTokens + completionTokens, model
        );
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
