package com.adonis.integration.support;

import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared singleton MongoDB Testcontainer for integration tests.
 * Uses official MongoDB 7.0 image matching production environment.
 */
public final class MongoTestContainer {

    public static final String MONGO_IMAGE = "mongo:7.0";
    private static MongoDBContainer container;

    private MongoTestContainer() {
    }

    public static synchronized MongoDBContainer getInstance() {
        if (container == null) {
            container = new MongoDBContainer(DockerImageName.parse(MONGO_IMAGE));
            container.start();
        }
        return container;
    }

    public static synchronized String getReplicaSetUrl(String databaseName) {
        MongoDBContainer c = getInstance();
        return c.getReplicaSetUrl(databaseName);
    }

    public static synchronized boolean isRunning() {
        return container != null && container.isRunning();
    }
}
