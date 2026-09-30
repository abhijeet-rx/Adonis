package com.adonis.ai.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates JSON text produced by AI against a declared JSON Schema.
 */
public class JsonSchemaValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record ValidationResult(boolean valid, List<String> errors, JsonNode parsedJson) {
        public static ValidationResult success(JsonNode node) {
            return new ValidationResult(true, Collections.emptyList(), node);
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors, null);
        }

        public static ValidationResult failure(String singleError) {
            return new ValidationResult(false, List.of(singleError), null);
        }
    }

    /**
     * Validates that the schema string or structure is itself well-formed JSON.
     */
    public static void validateSchemaDefinition(Object rawSchema) throws IllegalArgumentException {
        if (rawSchema == null) {
            throw new IllegalArgumentException("JSON schema must not be null");
        }

        try {
            JsonNode schemaNode;
            if (rawSchema instanceof String schemaStr) {
                if (schemaStr.trim().isEmpty()) {
                    throw new IllegalArgumentException("JSON schema string must not be empty");
                }
                schemaNode = OBJECT_MAPPER.readTree(schemaStr);
            } else if (rawSchema instanceof Map<?, ?> || rawSchema instanceof JsonNode) {
                schemaNode = OBJECT_MAPPER.valueToTree(rawSchema);
            } else {
                throw new IllegalArgumentException("JSON schema must be a JSON string or Map object");
            }

            if (!schemaNode.isObject()) {
                throw new IllegalArgumentException("JSON schema root must be a JSON object");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON schema definition: " + e.getMessage(), e);
        }
    }

    /**
     * Cleans, parses, and validates raw AI text against the given schema.
     */
    public static ValidationResult validate(String rawAiText, Object rawSchema) {
        if (rawAiText == null || rawAiText.trim().isEmpty()) {
            return ValidationResult.failure("AI response is empty; cannot extract structured JSON");
        }

        String cleanedText = stripMarkdownFences(rawAiText.trim());

        JsonNode parsedData;
        try {
            parsedData = OBJECT_MAPPER.readTree(cleanedText);
        } catch (JsonProcessingException e) {
            return ValidationResult.failure("Malformed AI output - failed to parse as JSON: " + e.getOriginalMessage());
        }

        JsonNode schemaNode;
        try {
            if (rawSchema instanceof String schemaStr) {
                schemaNode = OBJECT_MAPPER.readTree(schemaStr);
            } else {
                schemaNode = OBJECT_MAPPER.valueToTree(rawSchema);
            }
        } catch (Exception e) {
            return ValidationResult.failure("Invalid schema configuration: " + e.getMessage());
        }

        List<String> errors = new ArrayList<>();
        validateNode("$", parsedData, schemaNode, errors);

        if (!errors.isEmpty()) {
            return ValidationResult.failure(errors);
        }

        return ValidationResult.success(parsedData);
    }

    /**
     * Recursively validates a JsonNode against a JSON schema node.
     */
    private static void validateNode(String path, JsonNode data, JsonNode schema, List<String> errors) {
        if (schema == null || !schema.isObject()) {
            return;
        }

        // 1. Type validation
        if (schema.has("type")) {
            String expectedType = schema.get("type").asText();
            if (!matchesType(data, expectedType)) {
                errors.add(String.format("Path '%s': expected type '%s', but found '%s'",
                        path, expectedType, getNodeType(data)));
                return; // Type mismatch: further schema checks on this node might not make sense
            }
        }

        // 2. Object validation: required and properties
        if (data.isObject()) {
            if (schema.has("required") && schema.get("required").isArray()) {
                for (JsonNode reqNode : schema.get("required")) {
                    String reqField = reqNode.asText();
                    if (!data.has(reqField) || data.get(reqField).isNull()) {
                        errors.add(String.format("Path '%s': missing required property '%s'", path, reqField));
                    }
                }
            }

            if (schema.has("properties") && schema.get("properties").isObject()) {
                JsonNode properties = schema.get("properties");
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String propName = field.getKey();
                    JsonNode propSchema = field.getValue();
                    if (data.has(propName)) {
                        validateNode(path + "." + propName, data.get(propName), propSchema, errors);
                    }
                }
            }

            // additionalProperties check if false
            if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean(true)) {
                JsonNode properties = schema.get("properties");
                Iterator<String> fieldNames = data.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    if (properties == null || !properties.has(fieldName)) {
                        errors.add(String.format("Path '%s': unexpected property '%s' not allowed by schema", path, fieldName));
                    }
                }
            }
        }

        // 3. Array validation
        if (data.isArray() && schema.has("items")) {
            JsonNode itemsSchema = schema.get("items");
            for (int i = 0; i < data.size(); i++) {
                validateNode(path + "[" + i + "]", data.get(i), itemsSchema, errors);
            }
        }

        // 4. Enum validation
        if (schema.has("enum") && schema.get("enum").isArray()) {
            boolean matchedEnum = false;
            for (JsonNode enumVal : schema.get("enum")) {
                if (enumVal.equals(data)) {
                    matchedEnum = true;
                    break;
                }
            }
            if (!matchedEnum) {
                errors.add(String.format("Path '%s': value '%s' is not in allowed enum values", path, data.asText()));
            }
        }
    }

    private static boolean matchesType(JsonNode node, String expectedType) {
        if (node == null || node.isNull()) {
            return "null".equalsIgnoreCase(expectedType);
        }
        return switch (expectedType.toLowerCase()) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "number" -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    private static String getNodeType(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return "unknown";
    }

    /**
     * Strips standard markdown code fences (```json ... ``` or ``` ... ```) if present.
     */
    public static String stripMarkdownFences(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```JSON")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }

        return trimmed.trim();
    }
}
