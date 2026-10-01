package com.adonis.integration.support;

import org.testcontainers.DockerClientFactory;

/**
 * Utility to inspect Docker environment availability and CI execution context.
 */
public final class DockerAvailability {

    private static Boolean available;

    private DockerAvailability() {
    }

    public static synchronized boolean isDockerAvailable() {
        if (available == null) {
            try {
                available = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    public static boolean isCi() {
        return "true".equalsIgnoreCase(System.getenv("CI"))
                || "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }
}
