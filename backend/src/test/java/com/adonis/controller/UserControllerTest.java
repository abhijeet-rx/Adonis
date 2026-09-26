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
    void getCurrentUserShouldFailWithMalformedToken() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid.malformed.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCurrentUserShouldFailWithForgedSignatureToken() throws Exception {
        String forgedSecret = "9999888877776666555544443333222211110000aaaabbbbccccddddeeeeffff";
        JwtService forgedService = new JwtService(forgedSecret, 3600000);
        String forgedToken = forgedService.generateToken("user-999", "abhijeet@example.com", "Abhijeet Singh");

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCurrentUserShouldFailWithExpiredToken() throws Exception {
        // Create token with 5ms lifespan and wait 15ms
        String testSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
        JwtService shortLivedService = new JwtService(testSecret, 5);
        String expiredToken = shortLivedService.generateToken("user-999", "abhijeet@example.com", "Abhijeet Singh");
        Thread.sleep(15);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCurrentUserShouldSucceedWithValidBearerToken() throws Exception {
        Instant now = Instant.now();
        String token = jwtService.generateToken("user-999", "abhijeet@example.com", "Abhijeet Singh");
        UserResponse userResponse = new UserResponse("user-999", "Abhijeet Singh", "abhijeet@example.com", now, now);

        when(userService.getUserProfile("user-999")).thenReturn(userResponse);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-999"))
                .andExpect(jsonPath("$.email").value("abhijeet@example.com"))
                .andExpect(jsonPath("$.name").value("Abhijeet Singh"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
