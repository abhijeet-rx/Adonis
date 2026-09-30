package com.adonis.controller;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.repository.WorkflowRepository;
import com.adonis.util.SecretRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final long maxBodySizeBytes;

    @Autowired
    public WebhookController(
            WorkflowRepository workflowRepository,
            WorkflowExecutionService executionService,
            ObjectMapper objectMapper,
            @Value("${adonis.webhook.max-body-size-bytes:1048576}") long maxBodySizeBytes) {
        this.workflowRepository = workflowRepository;
        this.executionService = executionService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.maxBodySizeBytes = Math.max(1024, maxBodySizeBytes);
    }

    @PostMapping("/{webhookPath}")
    public ResponseEntity<ExecuteWorkflowResponse> handleWebhook(
            @PathVariable String webhookPath,
            HttpServletRequest request) {

        // 1. Resolve workflow by webhookPath
        if (webhookPath == null || webhookPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook not found");
        }

        Workflow workflow = workflowRepository.findByTriggerConfigWebhookPath(webhookPath.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook not found"));

        // 2. Verify workflow is active
        if (workflow.getStatus() != WorkflowStatus.ACTIVE) {
            log.warn("Invalid webhook rejected: workflow {} is not active (status={})", workflow.getId(), workflow.getStatus());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow is not active");
        }

        WorkflowTriggerConfig config = workflow.getTriggerConfig();

        // 3. Verify webhook secret if configured (constant-time verification)
        if (config != null && config.isHasSecret()) {
            String incomingSecret = request.getHeader("X-Webhook-Secret");
            if (incomingSecret == null || incomingSecret.isBlank()) {
                incomingSecret = request.getHeader("X-Adonis-Secret");
            }

            if (incomingSecret == null || !config.verifySecret(incomingSecret)) {
                log.warn("Invalid webhook rejected: secret verification failed for workflowId={}", workflow.getId());
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing webhook secret");
            }
        }

        // 4. Enforce request size limit
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodySizeBytes) {
            log.warn("Webhook rejected: payload length {} exceeds limit {} for workflowId={}",
                    contentLength, maxBodySizeBytes, workflow.getId());
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds maximum allowed size");
        }

        byte[] rawBytes = readBoundedBody(request, maxBodySizeBytes);

        // 5. Parse and sanitize payload
        Object parsedBody = parseBody(rawBytes, request.getContentType());
        Map<String, String> sanitizedHeaders = extractAndSanitizeHeaders(request);
        Map<String, Object> sanitizedQueryParams = extractAndSanitizeQueryParams(request);

        Map<String, Object> triggerPayload = new LinkedHashMap<>();
        triggerPayload.put("type", "WEBHOOK");
        triggerPayload.put("method", request.getMethod());
        triggerPayload.put("path", request.getRequestURI());
        triggerPayload.put("headers", sanitizedHeaders);
        triggerPayload.put("query", sanitizedQueryParams);
        triggerPayload.put("body", parsedBody);

        // 6. Handle optional Idempotency-Key header
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = request.getHeader("X-Idempotency-Key");
        }

        // 7. Enqueue execution through existing Redis pipeline
        ExecuteWorkflowResponse response = executionService.enqueueWebhookExecution(
                workflow,
                idempotencyKey,
                triggerPayload
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private byte[] readBoundedBody(HttpServletRequest request, long maxBytes) {
        try (InputStream in = request.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            long totalRead = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                totalRead += n;
                if (totalRead > maxBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds maximum allowed size");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read request body: " + ex.getMessage());
        }
    }

    private Object parseBody(byte[] rawBytes, String contentType) {
        if (rawBytes == null || rawBytes.length == 0) {
            return Map.of();
        }

        String bodyString = new String(rawBytes, StandardCharsets.UTF_8).trim();
        if (bodyString.isEmpty()) {
            return Map.of();
        }

        boolean isJson = contentType != null && contentType.toLowerCase().contains("application/json");

        if (isJson || (bodyString.startsWith("{") && bodyString.endsWith("}")) || (bodyString.startsWith("[") && bodyString.endsWith("]"))) {
            try {
                if (bodyString.startsWith("{")) {
                    Map<String, Object> map = objectMapper.readValue(bodyString, new TypeReference<Map<String, Object>>() {});
                    return SecretRedactor.redactMap(map);
                } else if (bodyString.startsWith("[")) {
                    List<Object> list = objectMapper.readValue(bodyString, new TypeReference<List<Object>>() {});
                    return SecretRedactor.redactCollection(list);
                }
            } catch (JsonProcessingException jpe) {
                if (isJson) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed JSON payload");
                }
            }
        }

        return SecretRedactor.redactString(bodyString);
    }

    private Map<String, String> extractAndSanitizeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("x-webhook-secret") || lower.equals("x-adonis-secret") || lower.equals("authorization")
                        || lower.equals("cookie") || lower.equals("set-cookie") || lower.equals("proxy-authorization")) {
                    continue; // Omit entirely from payload for security
                }
                String value = request.getHeader(name);
                headers.put(name, SecretRedactor.redactString(value));
            }
        }
        return headers;
    }

    private Map<String, Object> extractAndSanitizeQueryParams(HttpServletRequest request) {
        Map<String, Object> queryParams = new LinkedHashMap<>();
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap != null) {
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                String key = entry.getKey();
                String[] values = entry.getValue();
                if (key == null) continue;
                if (SecretRedactor.isSensitiveKey(key)) {
                    queryParams.put(key, SecretRedactor.REDACTED_VALUE);
                } else if (values != null && values.length > 0) {
                    if (values.length == 1) {
                        queryParams.put(key, SecretRedactor.redactString(values[0]));
                    } else {
                        List<String> list = Arrays.stream(values).map(SecretRedactor::redactString).toList();
                        queryParams.put(key, list);
                    }
                }
            }
        }
        return queryParams;
    }
}
