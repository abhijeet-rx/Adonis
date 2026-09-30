package com.adonis.ai.util;

import com.adonis.execution.ExecutionContext;
import com.adonis.execution.NodeExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptInterpolatorTest {

    @Test
    void testDirectInputInterpolation() {
        Map<String, Object> input = Map.of(
                "name", "Abhijeet",
                "role", "Full Stack Developer"
        );

        String template = "Write an intro for {{name}} who is applying for a {{role}} position.";
        String result = PromptInterpolator.interpolate(template, input, null);

        assertEquals("Write an intro for Abhijeet who is applying for a Full Stack Developer position.", result);
    }

    @Test
    void testNestedUpstreamNodeOutputInterpolation() {
        ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());

        Map<String, Object> upstreamOutput = Map.of(
                "candidate", Map.of(
                        "name", "Jane Doe",
                        "experience", 5
                ),
                "text", "Experienced software engineer specializing in backend systems."
        );

        NodeExecutionResult upstreamResult = NodeExecutionResult.success(
                "resumeNode",
                "httpRequest",
                Instant.now(),
                Instant.now(),
                Map.of(),
                upstreamOutput
        );
        context.recordNodeResult("resumeNode", upstreamResult);

        String template = "Evaluate candidate {{resumeNode.output.candidate.name}} with text:\n{{resumeNode.output.text}}";
        String result = PromptInterpolator.interpolate(template, Map.of(), context);

        assertTrue(result.contains("Evaluate candidate Jane Doe"));
        assertTrue(result.contains("Experienced software engineer specializing in backend systems."));
    }

    @Test
    void testMissingVariableHandledSafely() {
        String template = "Hello {{missingNode.output.foo}}, welcome to {{unknownProp}}!";
        String result = PromptInterpolator.interpolate(template, Map.of(), null);

        assertEquals("Hello , welcome to !", result);
    }

    @Test
    void testPreservesNormalTextWithoutPlaceholders() {
        String template = "This is a normal prompt with no variables. Brackets like {one} are preserved.";
        String result = PromptInterpolator.interpolate(template, Map.of(), null);

        assertEquals(template, result);
    }

    @Test
    void testRedactsSecretInInterpolation() {
        Map<String, Object> input = Map.of(
                "authHeader", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123def456ghi789",
                "apiKey", "sk-123456789012345678901234"
        );

        String template = "Call endpoint with header {{authHeader}} and key {{apiKey}}";
        String result = PromptInterpolator.interpolate(template, input, null);

        assertFalse(result.contains("sk-123456789012345678901234"));
        assertTrue(result.contains("[REDACTED]"));
    }
}
