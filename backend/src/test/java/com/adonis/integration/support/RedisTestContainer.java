package com.adonis.integration.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared singleton Redis Testcontainer for integration tests.
 * Uses official Redis 7-alpine image matching production docker-compose.
 */
public final class RedisTestContainer {

    public static final String REDIS_IMAGE = "redis:7-alpine";
    public static final int REDIS_PORT = 6379;
    private static GenericContainer<?> container;

    private RedisTestContainer() {
    }

    public static synchronized GenericContainer<?> getInstance() {
        if (container == null) {
            container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT);
            container.start();
        }
        return container;
    }

    public static synchronized String getHost() {
        return getInstance().getHost();
    }

    public static synchronized int getPort() {
        return getInstance().getMappedPort(REDIS_PORT);
    }

    public static synchronized boolean isRunning() {
        return container != null && container.isRunning();
    }
}
