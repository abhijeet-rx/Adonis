package com.adonis.ai.util;

import com.adonis.execution.ExecutionContext;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.util.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves template variables in AI prompts (e.g. {{nodeId.output.property}}).
 */
public class PromptInterpolator {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static String interpolate(String template, Map<String, Object> input, ExecutionContext context) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object resolved = resolveVariable(path, input, context);
            String replacement = formatValue(resolved);
            // Redact any sensitive content that might have been interpolated
            String sanitized = SecretRedactor.redactString(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(sanitized));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private static Object resolveVariable(String path, Map<String, Object> input, ExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return "";
        }

        String firstPart = parts[0];

        // 1. Check if firstPart corresponds to a previously executed node ID
        if (context != null) {
            NodeExecutionResult nodeResult = context.getNodeResult(firstPart);
            if (nodeResult != null) {
                if (parts.length == 1) {
                    return nodeResult.output();
                }

                String secondPart = parts[1];
                if ("output".equalsIgnoreCase(secondPart)) {
                    if (parts.length == 2) {
                        return nodeResult.output();
                    }
                    return extractPath(nodeResult.output(), parts, 2);
                } else if ("input".equalsIgnoreCase(secondPart)) {
                    if (parts.length == 2) {
                        return nodeResult.input();
                    }
                    return extractPath(nodeResult.input(), parts, 2);
                } else {
                    // Direct lookup in output
                    return extractPath(nodeResult.output(), parts, 1);
                }
            }
        }

        // 2. Check if path starts with "input"
        if ("input".equalsIgnoreCase(firstPart)) {
            if (parts.length == 1) {
                return input != null ? input : "";
            }
            return extractPath(input, parts, 1);
        }

        // 3. Look up directly in input map
        if (input != null && input.containsKey(firstPart)) {
            if (parts.length == 1) {
                return input.get(firstPart);
            }
            return extractPath(input.get(firstPart), parts, 1);
        }

        // 4. Missing variable -> safely return empty string
        return "";
    }

    private static Object extractPath(Object current, String[] parts, int startIndex) {
        Object pointer = current;
        for (int i = startIndex; i < parts.length; i++) {
            if (pointer == null) {
                return "";
            }
            String key = parts[i];
            if (pointer instanceof Map<?, ?> map) {
                pointer = map.get(key);
            } else if (pointer instanceof String str && (str.trim().startsWith("{") || str.trim().startsWith("["))) {
                try {
                    Map<?, ?> parsed = OBJECT_MAPPER.readValue(str, Map.class);
                    pointer = parsed != null ? parsed.get(key) : null;
                } catch (Exception e) {
                    return "";
                }
            } else {
                return "";
            }
        }
        return pointer != null ? pointer : "";
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
