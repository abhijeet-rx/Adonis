package com.adonis.ai.provider;

import com.adonis.ai.AIProperties;
import com.adonis.ai.AIProviderException;
import com.adonis.ai.AIRequest;
import com.adonis.ai.AIResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAIProviderTest {

    private HttpClient httpClient;
    private AIProperties properties;
    private OpenAIProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        properties = new AIProperties();
        properties.getOpenai().setApiKey("test-openai-key");
        properties.getOpenai().setBaseUrl("https://api.openai.com/v1");
        properties.getTimeout().setConnectMs(5000L);
        properties.getTimeout().setReadMs(10000L);

        objectMapper = new ObjectMapper();
        provider = new OpenAIProvider(properties, httpClient, objectMapper);
    }

    @Test
    void testSupportsAndProviderName() {
        assertEquals("openai", provider.getProviderName());
        assertTrue(provider.supports("openai"));
        assertTrue(provider.supports("OPENAI"));
        assertFalse(provider.supports("gemini"));
    }

    @Test
    void testMissingApiKeyThrowsNonRetryableException() {
        properties.getOpenai().setApiKey("");
        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .userPrompt("Hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertFalse(ex.isRetryable());
        assertEquals(401, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("OPENAI_API_KEY"));
    }

    @Test
    void testMissingModelThrowsNonRetryableException() {
        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("   ")
                .userPrompt("Hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertFalse(ex.isRetryable());
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSuccessfulGeneration() throws Exception {
        String jsonResponse = """
                {
                  "id": "chatcmpl-123",
                  "object": "chat.completion",
                  "model": "gpt-4o-mini-2024-07-18",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Hello! I am an AI assistant."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20
                  }
                }
                """;

        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .systemPrompt("You are helpful")
                .userPrompt("Say hello")
                .temperature(0.7)
                .maxTokens(100)
                .build();

        AIResponse response = provider.generate(request);

        assertNotNull(response);
        assertEquals("Hello! I am an AI assistant.", response.text());
        assertEquals("openai", response.provider());
        assertEquals("gpt-4o-mini-2024-07-18", response.model());
        assertEquals(12, response.usage().promptTokens());
        assertEquals(8, response.usage().completionTokens());
        assertEquals(20, response.usage().totalTokens());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRateLimit429IsRetryable() throws Exception {
        String errorJson = """
                {
                  "error": {
                    "message": "Rate limit reached for requests",
                    "type": "requests",
                    "param": null,
                    "code": "rate_limit_exceeded"
                  }
                }
                """;

        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(429);
        when(httpResponse.body()).thenReturn(errorJson);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .userPrompt("Say hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Rate limit reached"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testServerError500IsRetryable() throws Exception {
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .userPrompt("Say hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(500, ex.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUnauthorized401IsNotRetryable() throws Exception {
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{\"error\": {\"message\": \"Incorrect API key provided\"}}");
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .userPrompt("Say hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertFalse(ex.isRetryable());
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTimeoutIsRetryable() throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("request timed out"));

        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .userPrompt("Say hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(408, ex.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConnectionFailureIsRetryable() throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("Connection refused"));

        AIRequest request = AIRequest.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .userPrompt("Say hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(503, ex.getStatusCode());
    }
}
