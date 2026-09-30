package com.adonis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "adonis.ai")
public class AIProperties {

    private OpenAiProperties openai = new OpenAiProperties();
    private GeminiProperties gemini = new GeminiProperties();
    private TimeoutProperties timeout = new TimeoutProperties();

    public OpenAiProperties getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAiProperties openai) {
        this.openai = openai != null ? openai : new OpenAiProperties();
    }

    public GeminiProperties getGemini() {
        return gemini;
    }

    public void setGemini(GeminiProperties gemini) {
        this.gemini = gemini != null ? gemini : new GeminiProperties();
    }

    public TimeoutProperties getTimeout() {
        return timeout;
    }

    public void setTimeout(TimeoutProperties timeout) {
        this.timeout = timeout != null ? timeout : new TimeoutProperties();
    }

    public static class OpenAiProperties {
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey != null ? apiKey : "";
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.openai.com/v1";
        }
    }

    public static class GeminiProperties {
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey != null ? apiKey : "";
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://generativelanguage.googleapis.com/v1beta";
        }
    }

    public static class TimeoutProperties {
        private long connectMs = 10000L;
        private long readMs = 60000L;

        public long getConnectMs() {
            return connectMs;
        }

        public void setConnectMs(long connectMs) {
            this.connectMs = Math.max(500L, connectMs);
        }

        public long getReadMs() {
            return readMs;
        }

        public void setReadMs(long readMs) {
            this.readMs = Math.max(500L, readMs);
        }
    }
}
