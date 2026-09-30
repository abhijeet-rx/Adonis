package com.adonis.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class AIProviderService {

    private final List<AIProvider> providers;

    @Autowired
    public AIProviderService(List<AIProvider> providers) {
        this.providers = providers != null ? providers : Collections.emptyList();
    }

    public AIResponse generate(AIRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AIRequest must not be null");
        }
        if (request.provider() == null || request.provider().trim().isEmpty()) {
            throw new IllegalArgumentException("AI request provider must not be blank");
        }

        String providerName = request.provider().trim();
        AIProvider provider = providers.stream()
                .filter(p -> p.supports(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported AI provider: '" + providerName + "'. Supported providers: " + getSupportedProviderNames()
                ));

        return provider.generate(request);
    }

    public boolean isSupported(String providerName) {
        if (providerName == null || providerName.trim().isEmpty()) {
            return false;
        }
        return providers.stream().anyMatch(p -> p.supports(providerName.trim()));
    }

    public List<String> getSupportedProviderNames() {
        return providers.stream()
                .map(AIProvider::getProviderName)
                .toList();
    }
}
