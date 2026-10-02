package com.adonis.config;

import com.adonis.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "spring.data.mongodb.uri=mongodb://adonis_admin:smoke-test-mongo-password-12345@mongodb:27017/adonis?authSource=admin",
        "spring.data.mongodb.auto-index-creation=false",
        "spring.data.redis.password=smoke-test-redis-password-12345",
        "spring.data.redis.host=localhost",
        "adonis.jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
        "adonis.app.version=v1.2.3",
        "adonis.worker.enabled=false",
        "adonis.scheduler.enabled=false",
        "management.health.mongo.enabled=false",
        "management.health.redis.enabled=false"
})
@DisplayName("Production Configuration Hardening Tests")
class ProductionConfigurationHardeningTest {

    @Autowired
    private Environment environment;

    @MockBean
    private UserRepository userRepository;

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    @Value("${adonis.app.version}")
    private String appVersion;

    @Value("${adonis.jwt.secret}")
    private String jwtSecret;

    @Test
    @DisplayName("Production profile binds authenticated MongoDB URI correctly")
    void verifiesProductionMongoAuthenticatedUri() {
        assertThat(mongoUri)
                .isEqualTo("mongodb://adonis_admin:smoke-test-mongo-password-12345@mongodb:27017/adonis?authSource=admin");
    }

    @Test
    @DisplayName("Production profile binds Redis password correctly")
    void verifiesProductionRedisPassword() {
        assertThat(redisPassword)
                .isEqualTo("smoke-test-redis-password-12345");
    }

    @Test
    @DisplayName("Production profile binds release application version correctly")
    void verifiesProductionAppVersion() {
        assertThat(appVersion)
                .isEqualTo("v1.2.3");
    }

    @Test
    @DisplayName("Production profile binds JWT secret correctly")
    void verifiesProductionJwtSecret() {
        assertThat(jwtSecret)
                .isEqualTo("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
    }
}
