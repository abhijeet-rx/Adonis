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

class GeminiProviderTest {

    private HttpClient httpClient;
    private AIProperties properties;
    private GeminiProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        properties = new AIProperties();
        properties.getGemini().setApiKey("test-gemini-key");
        properties.getGemini().setBaseUrl("https://generativelanguage.googleapis.com/v1beta");
        properties.getTimeout().setConnectMs(5000L);
        properties.getTimeout().setReadMs(10000L);

        objectMapper = new ObjectMapper();
        provider = new GeminiProvider(properties, httpClient, objectMapper);
    }

    @Test
    void testSupportsAndProviderName() {
        assertEquals("gemini", provider.getProviderName());
        assertTrue(provider.supports("gemini"));
        assertTrue(provider.supports("GEMINI"));
        assertFalse(provider.supports("openai"));
    }

    @Test
    void testMissingApiKeyThrowsNonRetryableException() {
        properties.getGemini().setApiKey("");
        AIRequest request = AIRequest.builder()
                .provider("gemini")
                .model("gemini-1.5-flash")
                .userPrompt("Hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertFalse(ex.isRetryable());
        assertEquals(401, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("GEMINI_API_KEY"));
    }

    @Test
    void testMissingModelThrowsNonRetryableException() {
        AIRequest request = AIRequest.builder()
                .provider("gemini")
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
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Greetings from Gemini!"
                          }
                        ],
                        "role": "model"
                      },
                      "finishReason": "STOP"
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 15,
                    "candidatesTokenCount": 10,
                    "totalTokenCount": 25
                  },
                  "modelVersion": "gemini-1.5-flash-001"
                }
                """;

        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        AIRequest request = AIRequest.builder()
                .provider("gemini")
                .model("gemini-1.5-flash")
                .systemPrompt("Be brief")
                .userPrompt("Say greetings")
                .temperature(0.5)
                .maxTokens(50)
                .build();

        AIResponse response = provider.generate(request);

        assertNotNull(response);
        assertEquals("Greetings from Gemini!", response.text());
        assertEquals("gemini", response.provider());
        assertEquals("gemini-1.5-flash-001", response.model());
        assertEquals(15, response.usage().promptTokens());
        assertEquals(10, response.usage().completionTokens());
        assertEquals(25, response.usage().totalTokens());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testResourceExhausted429IsRetryable() throws Exception {
        String errorJson = """
                {
                  "error": {
                    "code": 429,
                    "message": "Resource has been exhausted (e.g. check quota).",
                    "status": "RESOURCE_EXHAUSTED"
                  }
                }
                """;

        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(429);
        when(httpResponse.body()).thenReturn(errorJson);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        AIRequest request = AIRequest.builder()
                .provider("gemini")
                .model("gemini-1.5-flash")
                .userPrompt("Hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("RESOURCE_EXHAUSTED") || ex.getMessage().contains("exhausted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTimeoutIsRetryable() throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("Connection timed out"));

        AIRequest request = AIRequest.builder()
                .provider("gemini")
                .model("gemini-1.5-flash")
                .userPrompt("Hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(408, ex.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConnectionFailureIsRetryable() throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("Network unreachable"));

        AIRequest request = AIRequest.builder()
                .provider("gemini")
                .model("gemini-1.5-flash")
                .userPrompt("Hello")
                .build();

        AIProviderException ex = assertThrows(AIProviderException.class, () -> provider.generate(request));
        assertTrue(ex.isRetryable());
        assertEquals(503, ex.getStatusCode());
    }
}
