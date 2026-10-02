package com.adonis.repository;

import com.adonis.model.User;
import com.adonis.test.ModernMemoryBackend;
import de.bwaldvogel.mongo.MongoServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
class UserRepositoryTest {

    private static MongoServer server;
    private static InetSocketAddress serverAddress;

    @BeforeAll
    static void setUpServer() {
        server = new MongoServer(new ModernMemoryBackend());
        serverAddress = server.bind();
    }

    @AfterAll
    static void tearDownServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void setMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://" + serverAddress.getHostName() + ":" + serverAddress.getPort() + "/adonis-test");
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mongoMappingContext;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        IndexOperations indexOps = mongoTemplate.indexOps(User.class);
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mongoMappingContext);
        resolver.resolveIndexFor(User.class).forEach(indexOps::ensureIndex);
    }

    @Test
    void shouldSaveAndRetrieveUserById() {
        User user = User.create("Abhijeet Singh", "abhijeet@example.com", "hashed_password");
        User saved = userRepository.save(user);

        assertNotNull(saved.getId());
        assertEquals("Abhijeet Singh", saved.getName());
        assertEquals("abhijeet@example.com", saved.getEmail());
        assertEquals("hashed_password", saved.getPasswordHash());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Optional<User> found = userRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("abhijeet@example.com", found.get().getEmail());
    }

    @Test
    void shouldRetrieveUserByNormalizedLowercaseEmail() {
        User user = User.create("Abhijeet Singh", "USER@EXAMPLE.COM", "hashed_password");
        userRepository.save(user);

        // Saved email is normalized to lowercase by User.create/setEmail
        assertEquals("user@example.com", user.getEmail());

        Optional<User> found = userRepository.findByEmail("user@example.com");
        assertTrue(found.isPresent());
        assertEquals("Abhijeet Singh", found.get().getName());

        assertTrue(userRepository.existsByEmail("user@example.com"));
        assertFalse(userRepository.existsByEmail("other@example.com"));
    }

    @Test
    void shouldEnforceUniqueEmailConstraintAndRejectDuplicates() {
        User user1 = User.create("User One", "duplicate@example.com", "hash1");
        userRepository.save(user1);

        User user2 = User.create("User Two", "duplicate@example.com", "hash2");

        // Saving a second user with the same email must be rejected by the unique constraint
        assertThrows(DuplicateKeyException.class, () -> {
            userRepository.save(user2);
        });
    }
}
