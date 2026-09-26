package com.adonis.service;

import com.adonis.dto.AuthResponse;
import com.adonis.dto.LoginRequest;
import com.adonis.dto.RegisterRequest;
import com.adonis.exception.EmailAlreadyExistsException;
import com.adonis.model.User;
import com.adonis.repository.UserRepository;
import com.adonis.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void registerShouldSucceedWhenEmailIsUnique() {
        RegisterRequest request = new RegisterRequest("Abhijeet Singh", "Abhijeet@Example.com", "Password123!");
        when(userRepository.existsByEmail("abhijeet@example.com")).thenReturn(false);

        User savedUser = new User("user-1", "Abhijeet Singh", "abhijeet@example.com", "hashed_pwd", Instant.now(), Instant.now());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateToken("user-1", "abhijeet@example.com", "Abhijeet Singh")).thenReturn("mock.jwt.token");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.token());
        assertEquals("user-1", response.user().id());
        assertEquals("abhijeet@example.com", response.user().email());
        assertEquals("Abhijeet Singh", response.user().name());

        verify(userRepository).save(argThat(u ->
                u.getEmail().equals("abhijeet@example.com") &&
                passwordEncoder.matches("Password123!", u.getPasswordHash())
        ));
    }

    @Test
    void registerShouldThrowWhenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest("Abhijeet", "test@example.com", "password");
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginShouldSucceedWithCorrectPassword() {
        String rawPassword = "CorrectPassword123";
        String encodedHash = passwordEncoder.encode(rawPassword);
        User user = new User("user-1", "Abhijeet", "abhijeet@example.com", encodedHash, Instant.now(), Instant.now());

        when(userRepository.findByEmail("abhijeet@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken("user-1", "abhijeet@example.com", "Abhijeet")).thenReturn("valid.token");

        LoginRequest request = new LoginRequest("ABHIJEET@EXAMPLE.COM", rawPassword);
        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("valid.token", response.token());
        assertEquals("user-1", response.user().id());
    }

    @Test
    void loginShouldFailWithIncorrectPassword() {
        String encodedHash = passwordEncoder.encode("RealPassword");
        User user = new User("user-1", "Abhijeet", "test@example.com", encodedHash, Instant.now(), Instant.now());

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("test@example.com", "WrongPassword");
        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }
}
