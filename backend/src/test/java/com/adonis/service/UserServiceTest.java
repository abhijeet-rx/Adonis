package com.adonis.service;

import com.adonis.dto.UserResponse;
import com.adonis.exception.UserNotFoundException;
import com.adonis.model.User;
import com.adonis.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
    }

    @Test
    void getUserProfileShouldReturnUserResponse() {
        Instant now = Instant.now();
        User user = new User("user-1", "Abhijeet Singh", "abhijeet@example.com", "hash", now, now);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserProfile("user-1");

        assertNotNull(response);
        assertEquals("user-1", response.id());
        assertEquals("Abhijeet Singh", response.name());
        assertEquals("abhijeet@example.com", response.email());
        assertEquals(now, response.createdAt());
        assertEquals(now, response.updatedAt());
    }

    @Test
    void getUserProfileShouldThrowWhenUserNotFound() {
        when(userRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.getUserProfile("nonexistent"));
    }
}
