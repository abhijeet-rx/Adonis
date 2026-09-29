package com.adonis.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RetryConfigTest {

    @Test
    void defaultConfig_HasSafeDefaults() {
        RetryConfig config = RetryConfig.defaultConfig();
        assertFalse(config.enabled());
        assertEquals(0, config.maxRetries());
        assertEquals(1000L, config.initialBackoffMs());
        assertEquals(2.0, config.backoffMultiplier());
        assertEquals(30000L, config.maxBackoffMs());
    }

    @Test
    void enabledFactory_SetsExpectedValues() {
        RetryConfig config = RetryConfig.enabled(3, 500L, 1.5, 10000L);
        assertTrue(config.enabled());
        assertEquals(3, config.maxRetries());
        assertEquals(500L, config.initialBackoffMs());
        assertEquals(1.5, config.backoffMultiplier());
        assertEquals(10000L, config.maxBackoffMs());
    }

    @Test
    void validation_ThrowsOnNegativeMaxRetries() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryConfig(true, -1, 1000L, 2.0, 30000L)
        );
    }

    @Test
    void validation_ThrowsOnExcessiveMaxRetries() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryConfig(true, 10000, 1000L, 2.0, 30000L)
        );
    }

    @Test
    void validation_ThrowsOnNegativeBackoff() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryConfig(true, 3, -500L, 2.0, 30000L)
        );
    }

    @Test
    void validation_ThrowsOnZeroOrNegativeMultiplier() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryConfig(true, 3, 1000L, 0.0, 30000L)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new RetryConfig(true, 3, 1000L, -1.5, 30000L)
        );
    }

    @Test
    void validation_ThrowsWhenMaxBackoffLessThanInitialBackoff() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryConfig(true, 3, 5000L, 2.0, 1000L)
        );
    }

    @Test
    void fromNodeData_ParsesNestedRetryObject() {
        Map<String, Object> nodeData = Map.of(
                "retry", Map.of(
                        "enabled", true,
                        "maxRetries", 3,
                        "initialBackoffMs", 2000L,
                        "backoffMultiplier", 3.0,
                        "maxBackoffMs", 20000L
                )
        );

        RetryConfig config = RetryConfig.fromNodeData(nodeData);
        assertTrue(config.enabled());
        assertEquals(3, config.maxRetries());
        assertEquals(2000L, config.initialBackoffMs());
        assertEquals(3.0, config.backoffMultiplier());
        assertEquals(20000L, config.maxBackoffMs());
    }

    @Test
    void fromNodeData_ParsesFlatProperties() {
        Map<String, Object> nodeData = Map.of(
                "retryEnabled", true,
                "maxRetries", 2,
                "retryBackoff", 1500L,
                "retryBackoffMultiplier", 2.5
        );

        RetryConfig config = RetryConfig.fromNodeData(nodeData);
        assertTrue(config.enabled());
        assertEquals(2, config.maxRetries());
        assertEquals(1500L, config.initialBackoffMs());
        assertEquals(2.5, config.backoffMultiplier());
        assertEquals(30000L, config.maxBackoffMs());
    }

    @Test
    void fromNodeData_ClampsExcessiveClientValuesSafely() {
        Map<String, Object> nodeData = Map.of(
                "retry", Map.of(
                        "enabled", true,
                        "maxRetries", 999999,
                        "initialBackoffMs", 999999999L
                )
        );

        RetryConfig config = RetryConfig.fromNodeData(nodeData);
        assertTrue(config.enabled());
        assertEquals(RetryConfig.MAX_ALLOWED_RETRIES, config.maxRetries());
        assertEquals(RetryConfig.MAX_ALLOWED_BACKOFF_MS, config.initialBackoffMs());
    }

    @Test
    void toMap_SerializesCorrectly() {
        RetryConfig config = RetryConfig.enabled(3, 1000L, 2.0, 30000L);
        Map<String, Object> map = config.toMap();

        assertEquals(true, map.get("enabled"));
        assertEquals(3, map.get("maxRetries"));
        assertEquals(1000L, map.get("initialBackoffMs"));
        assertEquals(2.0, map.get("backoffMultiplier"));
        assertEquals(30000L, map.get("maxBackoffMs"));
    }
}
