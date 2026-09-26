package com.adonis.dto;

public record AuthResponse(
        String token,
        UserResponse user
) {
}
