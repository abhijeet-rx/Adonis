package com.adonis.controller;

import com.adonis.dto.UserResponse;
import com.adonis.repository.UserRepository;
import com.adonis.security.JwtService;
import com.adonis.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserService userService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void getCurrentUserShouldFailWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCurrentUserShouldSucceedWithValidBearerToken() throws Exception {
        String token = jwtService.generateToken("user-999", "abhijeet@example.com", "Abhijeet Singh");
        UserResponse userResponse = new UserResponse("user-999", "Abhijeet Singh", "abhijeet@example.com", Instant.now());

        when(userService.getUserProfile("user-999")).thenReturn(userResponse);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-999"))
                .andExpect(jsonPath("$.email").value("abhijeet@example.com"))
                .andExpect(jsonPath("$.name").value("Abhijeet Singh"));
    }
}
