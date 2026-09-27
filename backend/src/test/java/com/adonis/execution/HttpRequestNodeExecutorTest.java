package com.adonis.execution;

import com.adonis.model.WorkflowNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestNodeExecutorTest {

    private static HttpServer localServer;
    private static int serverPort;
    private static String baseUrl;

    private final HttpRequestNodeExecutor executor = new HttpRequestNodeExecutor();
    private final ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());

    @BeforeAll
    static void startLocalServer() throws IOException {
        localServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = localServer.getAddress().getPort();
        baseUrl = "http://localhost:" + serverPort;

        // Endpoint: /test-get
        localServer.createContext("/test-get", exchange -> {
            byte[] responseBytes = "{\"status\":\"ok\",\"message\":\"hello world\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        // Endpoint: /test-post
        localServer.createContext("/test-post", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "{\"received\":" + requestBody + "}";
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        // Endpoint: /test-404
        localServer.createContext("/test-404", exchange -> {
            byte[] responseBytes = "Not Found Error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        localServer.setExecutor(null);
        localServer.start();
    }

    @AfterAll
    static void stopLocalServer() {
        if (localServer != null) {
            localServer.stop(0);
        }
    }

    @Test
    void execute_SuccessfulGetRequest() {
        WorkflowNode node = new WorkflowNode(
                "node-http-get",
                "httpRequest",
                Map.of(
                        "url", baseUrl + "/test-get",
                        "method", "GET"
                )
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertNull(result.error());
        assertNotNull(result.output());
        assertEquals(200, result.output().get("statusCode"));
        assertEquals(true, result.output().get("success"));
        assertTrue(result.output().get("body").toString().contains("hello world"));
    }

    @Test
    void execute_SuccessfulPostRequest_WithBodyAndHeaders() {
        WorkflowNode node = new WorkflowNode(
                "node-http-post",
                "httpRequest",
                Map.of(
                        "url", baseUrl + "/test-post",
                        "method", "POST",
                        "body", "{\"item\":\"Adonis Workflow\"}",
                        "headers", Map.of("X-Custom-Header", "AdonisHeaderValue")
                )
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertNull(result.error());
        assertNotNull(result.output());
        assertEquals(201, result.output().get("statusCode"));
        assertEquals(true, result.output().get("success"));
        assertTrue(result.output().get("body").toString().contains("Adonis Workflow"));
    }

    @Test
    void execute_Http404Status_TreatedAsCompletedResponseWithFalseSuccess() {
        WorkflowNode node = new WorkflowNode(
                "node-http-404",
                "httpRequest",
                Map.of(
                        "url", baseUrl + "/test-404",
                        "method", "GET"
                )
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        // HTTP response status 404 completes without transport error
        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(404, result.output().get("statusCode"));
        assertEquals(false, result.output().get("success"));
        assertTrue(result.output().get("body").toString().contains("Not Found Error"));
    }

    @Test
    void execute_MissingUrl_FailsExecution() {
        WorkflowNode node = new WorkflowNode(
                "node-no-url",
                "httpRequest",
                Map.of("method", "GET")
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Missing required 'url'"));
    }

    @Test
    void execute_MalformedUrl_FailsExecution() {
        WorkflowNode node = new WorkflowNode(
                "node-bad-url",
                "httpRequest",
                Map.of("url", "not a valid uri:::1234", "method", "GET")
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("URL must use http or https scheme") || result.error().contains("Malformed URL"));
    }

    @Test
    void execute_UnsupportedScheme_FailsExecution() {
        WorkflowNode node = new WorkflowNode(
                "node-ftp-url",
                "httpRequest",
                Map.of("url", "ftp://example.com/file", "method", "GET")
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("must use http or https scheme"));
    }

    @Test
    void execute_ConnectionRefused_FailsExecution() {
        // Unused local port that will refuse connections
        WorkflowNode node = new WorkflowNode(
                "node-conn-refused",
                "httpRequest",
                Map.of("url", "http://127.0.0.1:59998/no-server", "method", "GET")
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("HTTP connection failed"));
    }

    @Test
    void execute_UnsupportedMethod_FailsExecution() {
        WorkflowNode node = new WorkflowNode(
                "node-unsupported-method",
                "httpRequest",
                Map.of("url", baseUrl + "/test-get", "method", "HEAD")
        );

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Unsupported HTTP method: HEAD"));
    }
}
