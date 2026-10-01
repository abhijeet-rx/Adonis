package com.adonis.integration.scheduler;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.*;
import com.adonis.queue.QueuedJobMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Scheduler Integration Tests (Real MongoDB + Redis Pipeline)")
class SchedulerIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Schedule initialization: scheduler computes and persists nextFireTime in MongoDB for active workflow")
    void scheduler_ScheduleInitialization_ComputesAndPersistsNextFireTime() {
        // Daily at noon UTC: "0 0 12 * * ?"
        Workflow workflow = TestDataFactory.createScheduledWorkflow("user-sched", "Init Schedule Flow", "0 0 12 * * ?", "UTC");
        workflow = workflowRepository.save(workflow);
        assertNull(workflow.getTriggerConfig().getNextFireTime());

        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        scheduler.setStartupTime(now.minusSeconds(60));

        // Run scheduler evaluation
        int queued = scheduler.checkAndRunSchedules(now);
        assertEquals(0, queued, "First run only initializes nextFireTime; should not queue yet");

        // Verify MongoDB document has nextFireTime set to 2026-10-01T12:00:00Z
        Workflow refreshed = workflowRepository.findById(workflow.getId()).orElseThrow();
        Instant nextFire = refreshed.getTriggerConfig().getNextFireTime();
        assertNotNull(nextFire, "nextFireTime must be persisted to MongoDB");
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), nextFire);
    }

    @Test
    @DisplayName("Scheduled pipeline: schedule -> ScheduledOccurrence -> WorkflowExecution -> Redis -> Worker -> SUCCESS")
    void scheduler_FullScheduledOccurrencePipeline_ExecutesToSuccess() {
        mockHttpServer.setDefaultHttpResponse(200, "{\"scheduled\":\"ok\"}");

        // Workflow due at 2026-10-01T12:00:00Z
        Instant dueTime = Instant.parse("2026-10-01T12:00:00Z");
        Instant now = dueTime.plusSeconds(5); // 5 seconds past due time

        Workflow workflow = TestDataFactory.createScheduledWorkflow("user-sched", "Due Schedule Flow", "0 0 12 * * ?", "UTC");
        workflow.getTriggerConfig().setNextFireTime(dueTime);
        workflow = workflowRepository.save(workflow);

        // Application startup was prior to due time (no misfire)
        scheduler.setStartupTime(dueTime.minus(10, ChronoUnit.MINUTES));

        // 1. Evaluate schedule loop at due time
        int queuedCount = scheduler.checkAndRunSchedules(now);
        assertEquals(1, queuedCount, "Due workflow must be claimed and queued");

        // 2. Verify ScheduledOccurrence record in MongoDB
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey(workflow.getId(), dueTime);
        ScheduledOccurrence occurrence = scheduledOccurrenceRepository.findById(occurrenceKey).orElseThrow();
        assertEquals(ScheduledOccurrenceStatus.ENQUEUED, occurrence.getStatus());
        assertNotNull(occurrence.getExecutionId());

        // 3. Verify nextFireTime advanced in MongoDB
        Workflow advancedWf = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertTrue(advancedWf.getTriggerConfig().getNextFireTime().isAfter(dueTime));

        // 4. Verify queued execution in MongoDB
        WorkflowExecution queuedExec = executionRepository.findById(occurrence.getExecutionId()).orElseThrow();
        assertEquals(ExecutionStatus.QUEUED, queuedExec.getStatus());
        assertEquals("SCHEDULE", queuedExec.getTriggerType());
        assertEquals(occurrenceKey, queuedExec.getScheduledOccurrence());

        // 5. Worker consumes from Redis and executes
        Optional<QueuedJobMessage> msgOpt = queue.poll(Duration.ofSeconds(2));
        assertTrue(msgOpt.isPresent());
        assertEquals(occurrence.getExecutionId(), msgOpt.get().job().executionId());

        boolean processed = worker.processJob(msgOpt.get());
        assertTrue(processed);

        // 6. Verify final execution state in MongoDB is SUCCESS
        WorkflowExecution completed = executionRepository.findById(occurrence.getExecutionId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
    }
}
