package com.adonis.config;

import com.adonis.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebConfigCorsTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @DisplayName("Development profile CORS behavior")
    class DevCorsTest {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private UserRepository userRepository;

        @Test
        @DisplayName("Allows Vite localhost:5173 origin in development")
        void allowsLocalhost5173InDev() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }

        @Test
        @DisplayName("Allows Vite 127.0.0.1:5173 origin in development")
        void allows127001InDev() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "http://127.0.0.1:5173")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
        }

        @Test
        @DisplayName("Allows localhost:3000 origin in development")
        void allowsLocalhost3000InDev() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "spring.profiles.active=prod",
            "spring.data.mongodb.uri=mongodb://localhost:27017/adonis-test",
            "spring.data.mongodb.auto-index-creation=false",
            "spring.data.redis.host=localhost",
            "adonis.worker.enabled=false",
            "adonis.scheduler.enabled=false",
            "adonis.jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
            "adonis.cors.allowed-origins="
    })
    @DisplayName("Production profile CORS behavior without custom origins")
    class ProdDefaultCorsTest {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private UserRepository userRepository;

        @Test
        @DisplayName("Rejects localhost:5173 origin in production when no custom origins are set")
        void rejectsLocalhost5173InProd() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Rejects 127.0.0.1:5173 origin in production")
        void rejects127001InProd() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "http://127.0.0.1:5173")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "spring.profiles.active=prod",
            "spring.data.mongodb.uri=mongodb://localhost:27017/adonis-test",
            "spring.data.mongodb.auto-index-creation=false",
            "spring.data.redis.host=localhost",
            "adonis.worker.enabled=false",
            "adonis.scheduler.enabled=false",
            "adonis.jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
            "adonis.cors.allowed-origins=https://app.adonis.io,https://admin.adonis.io"
    })
    @DisplayName("Production profile CORS behavior with explicitly configured origins")
    class ProdConfiguredCorsTest {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private UserRepository userRepository;

        @Test
        @DisplayName("Allows explicitly configured production origin")
        void allowsConfiguredOriginInProd() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "https://app.adonis.io")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "https://app.adonis.io"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }

        @Test
        @DisplayName("Allows second explicitly configured production origin")
        void allowsSecondConfiguredOriginInProd() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "https://admin.adonis.io")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.adonis.io"));
        }

        @Test
        @DisplayName("Still rejects localhost development origins even when custom production origins are set")
        void stillRejectsLocalhostInProdWithCustomOrigins() throws Exception {
            mockMvc.perform(options("/api/health")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }
    }
}
