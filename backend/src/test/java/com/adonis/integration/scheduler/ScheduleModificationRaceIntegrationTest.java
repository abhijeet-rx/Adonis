package com.adonis.integration.scheduler;

import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Schedule Modification Race Integration Tests (Phase 8.1.2 State Consistency in Real MongoDB)")
class ScheduleModificationRaceIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Schedule modification race: user updates cron/timezone after scheduler reads snapshot; scheduler abandons update and does NOT overwrite user changes")
    void scheduleModificationRace_SchedulerAbandonsStaleUpdate_PreservesUserChanges() {
        // Initial workflow: "0 0 12 * * ?" UTC, next due at 12:00
        Instant oldDue = Instant.parse("2026-10-01T12:00:00Z");
        Workflow workflow = TestDataFactory.createScheduledWorkflow("user-sched", "User Flow", "0 0 12 * * ?", "UTC");
        workflow.getTriggerConfig().setNextFireTime(oldDue);
        workflow = workflowRepository.save(workflow);

        // 1. Scheduler reads the workflow document into memory (stale snapshot)
        Workflow schedulerSnapshot = workflowRepository.findById(workflow.getId()).orElseThrow();

        // 2. User concurrently updates the schedule in MongoDB:
        // Changed to run every hour: "0 0 * * * ?" in "America/New_York", new nextFireTime
        Instant newDue = Instant.parse("2026-10-01T13:00:00Z");
        workflow.getTriggerConfig().setCronExpression("0 0 * * * ?");
        workflow.getTriggerConfig().setTimezone("America/New_York");
        workflow.getTriggerConfig().setNextFireTime(newDue);
        workflowRepository.save(workflow);

        // 3. Scheduler attempts to process using the stale snapshot at due time
        Instant evaluationTime = oldDue.plusSeconds(10);
        scheduler.setStartupTime(oldDue.minusSeconds(600));

        boolean processed = scheduler.processWorkflowSchedule(schedulerSnapshot, evaluationTime);

        // 4. Verify scheduler detected stale snapshot and abandoned claim
        assertFalse(processed, "Scheduler must abandon processing when schedule state changed concurrently");

        // 5. Verify user's updated schedule in MongoDB was NOT overwritten
        Workflow freshFromDb = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertEquals("0 0 * * * ?", freshFromDb.getTriggerConfig().getCronExpression(), "User's cron expression must be preserved");
        assertEquals("America/New_York", freshFromDb.getTriggerConfig().getTimezone(), "User's timezone must be preserved");
        assertEquals(newDue, freshFromDb.getTriggerConfig().getNextFireTime(), "User's nextFireTime must be preserved");

        // 6. Verify zero scheduled occurrences were created for the stale fire time
        assertEquals(0, scheduledOccurrenceRepository.count(), "No occurrences must be created for stale schedule");
    }
}
