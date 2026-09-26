package com.adonis.controller;

import com.adonis.dto.AuthResponse;
import com.adonis.dto.LoginRequest;
import com.adonis.dto.RegisterRequest;
import com.adonis.dto.UserResponse;
import com.adonis.exception.EmailAlreadyExistsException;
import com.adonis.repository.UserRepository;
import com.adonis.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void registerShouldReturnCreated() throws Exception {
        Instant now = Instant.now();
        RegisterRequest request = new RegisterRequest("Abhijeet Singh", "abhijeet@example.com", "Password123!");
        UserResponse userResponse = new UserResponse("u1", "Abhijeet Singh", "abhijeet@example.com", now, now);
        AuthResponse authResponse = new AuthResponse("sample.jwt.token", userResponse);

        when(authService.register(any(RegisterRequest.class))).thenReturn(authResponse);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("sample.jwt.token"))
                .andExpect(jsonPath("$.user.id").value("u1"))
                .andExpect(jsonPath("$.user.email").value("abhijeet@example.com"))
                .andExpect(jsonPath("$.user.createdAt").exists())
                .andExpect(jsonPath("$.user.updatedAt").exists())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void registerShouldReturnBadRequestForInvalidInput() throws Exception {
        RegisterRequest invalidRequest = new RegisterRequest("", "not-an-email", "short");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void registerShouldReturnConflictWhenEmailExists() throws Exception {
        RegisterRequest request = new RegisterRequest("Abhijeet Singh", "duplicate@example.com", "Password123!");
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("An account with email duplicate@example.com already exists"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("An account with email duplicate@example.com already exists"));
    }

    @Test
    void loginShouldReturnOk() throws Exception {
        Instant now = Instant.now();
        LoginRequest request = new LoginRequest("abhijeet@example.com", "Password123!");
        UserResponse userResponse = new UserResponse("u1", "Abhijeet Singh", "abhijeet@example.com", now, now);
        AuthResponse authResponse = new AuthResponse("login.jwt.token", userResponse);

        when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("login.jwt.token"))
                .andExpect(jsonPath("$.user.id").value("u1"))
                .andExpect(jsonPath("$.user.createdAt").exists())
                .andExpect(jsonPath("$.user.updatedAt").exists())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void loginShouldReturnUnauthorizedForInvalidCredentials() throws Exception {
        LoginRequest request = new LoginRequest("abhijeet@example.com", "WrongPassword");
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }
}
