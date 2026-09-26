package com.adonis.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String TEST_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        long expirationMs = 3600000; // 1 hour
        jwtService = new JwtService(TEST_SECRET, expirationMs);
    }

    @Test
    void shouldAcceptValidTokenAndExtractClaims() {
        String userId = "user-123";
        String email = "test@example.com";
        String name = "Test User";

        String token = jwtService.generateToken(userId, email, name);

        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        assertEquals(userId, jwtService.extractUserId(token));
        assertEquals(email, jwtService.extractEmail(token));
        assertEquals(name, jwtService.extractName(token));
    }

    @Test
    void shouldRejectExpiredToken() throws InterruptedException {
        // Service with 5 ms expiration window
        JwtService shortLivedJwtService = new JwtService(TEST_SECRET, 5);
        String token = shortLivedJwtService.generateToken("user-123", "test@example.com", "Test User");

        // Wait for token to expire
        Thread.sleep(15);

        assertFalse(shortLivedJwtService.isTokenValid(token), "Expired token must be rejected");
    }

    @Test
    void shouldRejectTokenWithInvalidSignature() {
        // Token signed with an untrusted secret
        String forgedSecret = "9999888877776666555544443333222211110000aaaabbbbccccddddeeeeffff";
        JwtService forgedJwtService = new JwtService(forgedSecret, 3600000);
        String forgedToken = forgedJwtService.generateToken("user-123", "test@example.com", "Test User");

        assertFalse(jwtService.isTokenValid(forgedToken), "Token with invalid signature must be rejected");
    }

    @Test
    void shouldRejectMalformedTokens() {
        assertFalse(jwtService.isTokenValid("not.a.valid.jwt"), "Malformed token must be rejected");
        assertFalse(jwtService.isTokenValid("Bearer header.payload.signature"), "Malformed token must be rejected");
        assertFalse(jwtService.isTokenValid(null), "Null token must be rejected");
        assertFalse(jwtService.isTokenValid(""), "Empty token must be rejected");
        assertFalse(jwtService.isTokenValid("   "), "Whitespace token must be rejected");
    }
}
