package com.adonis.dto;

import com.adonis.model.User;

import java.time.Instant;

public record UserResponse(
        String id,
        String name,
        String email,
        Instant createdAt
) {
    public static UserResponse fromUser(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt()
        );
    }
}
