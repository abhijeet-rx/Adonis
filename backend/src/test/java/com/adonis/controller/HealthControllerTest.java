package com.adonis.controller;

import com.adonis.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @Test
    void healthEndpointShouldReturnStatusUp() throws Exception {
        mockMvc.perform(get("/api/health")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("adonis-backend"))
                .andExpect(jsonPath("$.version").value("0.0.1-SNAPSHOT"))
                .andExpect(jsonPath("$.commit").value("unknown"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @org.junit.jupiter.api.Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @org.springframework.test.context.TestPropertySource(properties = {
            "adonis.app.version=v1.2.3",
            "adonis.build.commit=abcdef123456"
    })
    class CustomVersionTest {
        @Autowired
        private MockMvc mockMvc;

        @Test
        void healthEndpointShouldReturnConfiguredVersionAndCommit() throws Exception {
            mockMvc.perform(get("/api/health")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value("v1.2.3"))
                    .andExpect(jsonPath("$.commit").value("abcdef123456"));
        }
    }
}
