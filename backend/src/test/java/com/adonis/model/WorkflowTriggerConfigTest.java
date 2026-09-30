package com.adonis.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTriggerConfigTest {

    // ==========================================
    // Phase 8.1 FIX #9 — Capability Path Entropy
    // ==========================================

    @Test
    void generateWebhookPath_ShouldBeExactly64HexadecimalCharactersWith256BitsEntropy() {
        String path = WorkflowTriggerConfig.generateWebhookPath();

        assertNotNull(path);
        assertEquals(64, path.length(), "Generated webhook capability path must be exactly 64 characters");
        assertTrue(path.matches("^[0-9a-f]{64}$"), "Path must contain only lowercase hexadecimal characters");
    }

    @Test
    void generateWebhookPath_MultipleGenerations_MustBeUniqueAndRandom() {
        int count = 1000;
        Set<String> generated = new HashSet<>();

        for (int i = 0; i < count; i++) {
            String path = WorkflowTriggerConfig.generateWebhookPath();
            assertEquals(64, path.length());
            assertTrue(generated.add(path), "Duplicate generated webhook path detected at iteration " + i);
        }

        assertEquals(count, generated.size(), "All 1000 generated webhook paths must be strictly unique");
    }

    @Test
    void generateWebhookSecret_ShouldHavePrefixAndHighEntropy() {
        String secret = WorkflowTriggerConfig.generateWebhookSecret();

        assertNotNull(secret);
        assertTrue(secret.startsWith("whsec_"));
        // "whsec_" (6) + 48 hex chars (24 bytes) = 54 chars
        assertEquals(54, secret.length());
        assertTrue(secret.substring(6).matches("^[0-9a-f]{48}$"));
    }

    @Test
    void verifySecret_ConstantTimeComparison() {
        String secret = "whsec_super_secret_token_12345";
        String hash = WorkflowTriggerConfig.hashSecret(secret);

        WorkflowTriggerConfig config = new WorkflowTriggerConfig(null, null, "path-12345678", hash, true);

        assertTrue(config.verifySecret(secret));
        assertFalse(config.verifySecret("wrong_secret"));
        assertFalse(config.verifySecret(null));
        assertFalse(config.verifySecret(""));
    }

    @Test
    void lifecycleHelpers_ClearAndResetCorrectly() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(
                "0 */5 * * * *",
                "Asia/Kolkata",
                "wh-path-12345678",
                "secret-hash-value",
                true
        );
        config.setNextFireTime(Instant.now());
        config.setLastScheduledFireTime(Instant.now());

        // Clear schedule
        config.clearScheduleConfig();
        assertNull(config.getCronExpression());
        assertEquals("UTC", config.getTimezone());
        assertNull(config.getNextFireTime());
        assertNull(config.getLastScheduledFireTime());
        assertEquals("wh-path-12345678", config.getWebhookPath());
        assertTrue(config.isHasSecret());

        // Reset schedule state
        config.setNextFireTime(Instant.now());
        config.setLastScheduledFireTime(Instant.now());
        config.resetScheduleState();
        assertNull(config.getNextFireTime());
        assertNull(config.getLastScheduledFireTime());

        // Clear webhook
        config.clearWebhookConfig();
        assertNull(config.getWebhookPath());
        assertNull(config.getSecretHash());
        assertFalse(config.isHasSecret());

        // Clear all
        config.setCronExpression("0 0 * * * *");
        config.setWebhookPath("some-path");
        config.clearAll();
        assertNull(config.getCronExpression());
        assertNull(config.getWebhookPath());
    }
}
