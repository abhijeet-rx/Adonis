package com.adonis.execution;

import com.adonis.exception.WorkflowValidationException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.model.WorkflowTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTriggerValidatorTest {

    private WorkflowTriggerValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WorkflowTriggerValidator();
    }

    @Test
    void parseAndValidateCron_Valid6FieldExpressions_ParsesSuccessfully() {
        assertNotNull(WorkflowTriggerValidator.parseAndValidateCron("0 */5 * * * *"));
        assertNotNull(WorkflowTriggerValidator.parseAndValidateCron("0 0 * * * *"));
        assertNotNull(WorkflowTriggerValidator.parseAndValidateCron("0 0 9 * * MON-FRI"));
    }

    @Test
    void parseAndValidateCron_Valid5FieldExpressions_NormalizedAndParsed() {
        CronExpression expr1 = WorkflowTriggerValidator.parseAndValidateCron("*/5 * * * *");
        assertNotNull(expr1);

        CronExpression expr2 = WorkflowTriggerValidator.parseAndValidateCron("0 12 * * *");
        assertNotNull(expr2);
    }

    @Test
    void parseAndValidateCron_InvalidExpressions_ThrowsValidationException() {
        assertThrows(WorkflowValidationException.class, () -> WorkflowTriggerValidator.parseAndValidateCron(null));
        assertThrows(WorkflowValidationException.class, () -> WorkflowTriggerValidator.parseAndValidateCron(""));
        assertThrows(WorkflowValidationException.class, () -> WorkflowTriggerValidator.parseAndValidateCron("not-a-cron"));
        assertThrows(WorkflowValidationException.class, () -> WorkflowTriggerValidator.parseAndValidateCron("0 0 0 0 0 0 0 0")); // 8 fields
    }

    @Test
    void parseAndValidateZoneId_ValidTimezones_ReturnsZoneId() {
        assertEquals(ZoneId.of("Asia/Kolkata"), WorkflowTriggerValidator.parseAndValidateZoneId("Asia/Kolkata"));
        assertEquals(ZoneId.of("America/New_York"), WorkflowTriggerValidator.parseAndValidateZoneId("America/New_York"));
        assertEquals(ZoneId.of("Europe/London"), WorkflowTriggerValidator.parseAndValidateZoneId("Europe/London"));
    }

    @Test
    void parseAndValidateZoneId_NullOrBlank_DefaultsToUTC() {
        assertEquals(ZoneId.of("UTC"), WorkflowTriggerValidator.parseAndValidateZoneId(null));
        assertEquals(ZoneId.of("UTC"), WorkflowTriggerValidator.parseAndValidateZoneId(""));
        assertEquals(ZoneId.of("UTC"), WorkflowTriggerValidator.parseAndValidateZoneId("   "));
    }

    @Test
    void parseAndValidateZoneId_InvalidTimezone_ThrowsValidationException() {
        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowTriggerValidator.parseAndValidateZoneId("Asia/Invalid")
        );
        assertTrue(ex.getMessage().contains("Invalid timezone"));
    }

    @Test
    void validateTriggerConfig_ManualWorkflow_RequiresNoConfig() {
        Workflow workflow = Workflow.create("user-1", "Manual WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(), WorkflowTriggerType.MANUAL, null);
        assertDoesNotThrow(() -> validator.validateTriggerConfig(workflow));
    }

    @Test
    void validateTriggerConfig_ScheduleWorkflow_ValidConfig_Succeeds() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "Asia/Kolkata", null, null, false);
        Workflow workflow = Workflow.create("user-1", "Schedule WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config);

        assertDoesNotThrow(() -> validator.validateTriggerConfig(workflow));
    }

    @Test
    void validateTriggerConfig_ScheduleWorkflow_MissingCron_ThrowsValidationException() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(null, "UTC", null, null, false);
        Workflow workflow = Workflow.create("user-1", "Schedule WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config);

        assertThrows(WorkflowValidationException.class, () -> validator.validateTriggerConfig(workflow));
    }

    @Test
    void validateTriggerConfig_ScheduleWorkflow_InvalidCron_ThrowsValidationException() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("bad-cron", "UTC", null, null, false);
        Workflow workflow = Workflow.create("user-1", "Schedule WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config);

        assertThrows(WorkflowValidationException.class, () -> validator.validateTriggerConfig(workflow));
    }

    @Test
    void validateTriggerConfig_WebhookWorkflow_ValidConfig_Succeeds() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(null, null, "wh-path-12345678", null, false);
        Workflow workflow = Workflow.create("user-1", "Webhook WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(), WorkflowTriggerType.WEBHOOK, config);

        assertDoesNotThrow(() -> validator.validateTriggerConfig(workflow));
    }

    @Test
    void validateTriggerConfig_WebhookWorkflow_MissingOrShortPath_ThrowsValidationException() {
        WorkflowTriggerConfig shortConfig = new WorkflowTriggerConfig(null, null, "short", null, false);
        Workflow workflow = Workflow.create("user-1", "Webhook WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(), WorkflowTriggerType.WEBHOOK, shortConfig);

        assertThrows(WorkflowValidationException.class, () -> validator.validateTriggerConfig(workflow));
    }
}
