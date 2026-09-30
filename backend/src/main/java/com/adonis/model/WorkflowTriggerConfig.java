package com.adonis.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Encapsulates trigger configuration for scheduled and webhook-triggered workflows.
 */
public class WorkflowTriggerConfig {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Schedule configuration
    private String cronExpression;
    private String timezone;
    private Instant lastScheduledFireTime;
    private Instant nextFireTime;

    // Webhook configuration
    private String webhookPath;
    private String secretHash;
    private boolean hasSecret;

    public WorkflowTriggerConfig() {
    }

    public WorkflowTriggerConfig(String cronExpression, String timezone, String webhookPath, String secretHash, boolean hasSecret) {
        this.cronExpression = cronExpression;
        this.timezone = timezone != null && !timezone.isBlank() ? timezone.trim() : "UTC";
        this.webhookPath = webhookPath;
        this.secretHash = secretHash;
        this.hasSecret = hasSecret || (secretHash != null && !secretHash.isBlank());
    }

    /**
     * Generates a cryptographically strong unguessable webhook capability path
     * with 256 bits of entropy (32 random bytes -> 64 hexadecimal characters).
     */
    public static String generateWebhookPath() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().formatHex(randomBytes);
    }

    /**
     * Clears all schedule-specific configuration and execution state.
     */
    public void clearScheduleConfig() {
        this.cronExpression = null;
        this.timezone = null;
        this.nextFireTime = null;
        this.lastScheduledFireTime = null;
    }

    /**
     * Clears all webhook-specific configuration and credentials.
     */
    public void clearWebhookConfig() {
        this.webhookPath = null;
        this.secretHash = null;
        this.hasSecret = false;
    }

    /**
     * Clears both schedule and webhook configuration for manual trigger semantics.
     */
    public void clearAll() {
        clearScheduleConfig();
        clearWebhookConfig();
    }

    /**
     * Resets transient schedule calculation state whenever cron expression or timezone changes.
     */
    public void resetScheduleState() {
        this.nextFireTime = null;
        this.lastScheduledFireTime = null;
    }

    /**
     * Generates a cryptographically strong webhook secret token.
     */
    public static String generateWebhookSecret() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        return "whsec_" + HexFormat.of().formatHex(randomBytes);
    }

    /**
     * Hashes a webhook secret using SHA-256. Never store plaintext secrets.
     */
    public static String hashSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Verifies an incoming secret against the stored secret hash using constant-time comparison.
     */
    public boolean verifySecret(String incomingSecret) {
        if (!hasSecret || secretHash == null || secretHash.isBlank()) {
            return true; // No secret configured; unauthenticated webhook
        }
        if (incomingSecret == null || incomingSecret.isBlank()) {
            return false;
        }
        String incomingHash = hashSecret(incomingSecret);
        if (incomingHash == null) {
            return false;
        }
        byte[] a = incomingHash.getBytes(StandardCharsets.UTF_8);
        byte[] b = secretHash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getTimezone() {
        return timezone != null && !timezone.isBlank() ? timezone : "UTC";
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone != null && !timezone.isBlank() ? timezone.trim() : "UTC";
    }

    public Instant getLastScheduledFireTime() {
        return lastScheduledFireTime;
    }

    public void setLastScheduledFireTime(Instant lastScheduledFireTime) {
        this.lastScheduledFireTime = lastScheduledFireTime;
    }

    public Instant getNextFireTime() {
        return nextFireTime;
    }

    public void setNextFireTime(Instant nextFireTime) {
        this.nextFireTime = nextFireTime;
    }

    public String getWebhookPath() {
        return webhookPath;
    }

    public void setWebhookPath(String webhookPath) {
        this.webhookPath = webhookPath;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
        this.hasSecret = (secretHash != null && !secretHash.isBlank());
    }

    public boolean isHasSecret() {
        return hasSecret;
    }

    public void setHasSecret(boolean hasSecret) {
        this.hasSecret = hasSecret;
    }
}
