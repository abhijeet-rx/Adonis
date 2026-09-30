package com.adonis.ai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaValidatorTest {

    private final String sampleSchema = """
            {
              "type": "object",
              "required": ["name", "email", "skills", "yearsOfExperience"],
              "properties": {
                "name": {"type": "string"},
                "email": {"type": "string"},
                "skills": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "yearsOfExperience": {"type": "number"}
              }
            }
            """;

    @Test
    void testValidJsonMatchesSchema() {
        String validJson = """
                {
                  "name": "Abhijeet",
                  "email": "abhi@example.com",
                  "skills": ["Java", "TypeScript", "React"],
                  "yearsOfExperience": 6.5
                }
                """;

        JsonSchemaValidator.ValidationResult result = JsonSchemaValidator.validate(validJson, sampleSchema);
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertNotNull(result.parsedJson());
        assertEquals("Abhijeet", result.parsedJson().get("name").asText());
    }

    @Test
    void testMarkdownCodeFencesAreCleaned() {
        String markdownJson = """
                ```json
                {
                  "name": "Jane",
                  "email": "jane@example.com",
                  "skills": ["Spring Boot"],
                  "yearsOfExperience": 4
                }
                ```
                """;

        JsonSchemaValidator.ValidationResult result = JsonSchemaValidator.validate(markdownJson, sampleSchema);
        assertTrue(result.valid());
        assertEquals("Jane", result.parsedJson().get("name").asText());
    }

    @Test
    void testMissingRequiredPropertyFails() {
        String missingEmail = """
                {
                  "name": "Abhijeet",
                  "skills": ["Java"],
                  "yearsOfExperience": 5
                }
                """;

        JsonSchemaValidator.ValidationResult result = JsonSchemaValidator.validate(missingEmail, sampleSchema);
        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing required property 'email'")));
    }

    @Test
    void testTypeMismatchFails() {
        String invalidType = """
                {
                  "name": "Abhijeet",
                  "email": "abhi@example.com",
                  "skills": "NotAnArray",
                  "yearsOfExperience": 5
                }
                """;

        JsonSchemaValidator.ValidationResult result = JsonSchemaValidator.validate(invalidType, sampleSchema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("expected type 'array'")));
    }

    @Test
    void testMalformedJsonFails() {
        String malformed = "{ name: 'Abhijeet', unclosed }";

        JsonSchemaValidator.ValidationResult result = JsonSchemaValidator.validate(malformed, sampleSchema);
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("failed to parse as JSON"));
    }

    @Test
    void testEmptyAiResponseFails() {
        JsonSchemaValidator.ValidationResult result = JsonSchemaValidator.validate("", sampleSchema);
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("empty"));
    }

    @Test
    void testSchemaDefinitionValidation() {
        assertDoesNotThrow(() -> JsonSchemaValidator.validateSchemaDefinition(sampleSchema));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.validateSchemaDefinition(""));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.validateSchemaDefinition("not a json"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.validateSchemaDefinition(null));
    }
}
