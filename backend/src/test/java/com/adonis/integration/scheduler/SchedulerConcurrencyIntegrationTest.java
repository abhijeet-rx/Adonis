package com.adonis.integration.scheduler;

import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.ScheduledOccurrence;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.scheduler.AdonisScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Scheduler Concurrency Integration Tests (Real MongoDB)")
class SchedulerConcurrencyIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Scheduler concurrency: two concurrent schedulers evaluating due workflow produce exactly one execution")
    void schedulerConcurrency_TwoSchedulersEvaluateSameWorkflow_SingleExecutionCreated() throws Exception {
        Instant dueTime = Instant.parse("2026-10-01T14:00:00Z");
        Instant now = dueTime.plusSeconds(2);

        Workflow workflow = TestDataFactory.createScheduledWorkflow("user-sched", "Concurrent Due Flow", "0 0 14 * * ?", "UTC");
        workflow.getTriggerConfig().setNextFireTime(dueTime);
        workflow = workflowRepository.save(workflow);

        AdonisScheduler schedulerA = new AdonisScheduler(workflowRepository, executionService, scheduledOccurrenceRepository, mongoTemplate);
        schedulerA.setStartupTime(dueTime.minus(1, ChronoUnit.HOURS));

        AdonisScheduler schedulerB = new AdonisScheduler(workflowRepository, executionService, scheduledOccurrenceRepository, mongoTemplate);
        schedulerB.setStartupTime(dueTime.minus(1, ChronoUnit.HOURS));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger queuedTotal = new AtomicInteger(0);

        Future<Integer> f1 = executor.submit(() -> {
            latch.await();
            return schedulerA.checkAndRunSchedules(now);
        });

        Future<Integer> f2 = executor.submit(() -> {
            latch.await();
            return schedulerB.checkAndRunSchedules(now);
        });

        latch.countDown();
        int r1 = f1.get(5, TimeUnit.SECONDS);
        int r2 = f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one scheduler should have successfully queued the execution
        assertEquals(1, r1 + r2, "Exactly one scheduler instance must claim and queue the due occurrence");

        // Verify only ONE ScheduledOccurrence exists in MongoDB
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey(workflow.getId(), dueTime);
        List<ScheduledOccurrence> occurrences = scheduledOccurrenceRepository.findAll();
        assertEquals(1, occurrences.size());
        assertEquals(occurrenceKey, occurrences.get(0).getId());

        // Verify only ONE WorkflowExecution exists in MongoDB
        List<WorkflowExecution> executions = executionRepository.findAll();
        assertEquals(1, executions.size());
        assertEquals(occurrenceKey, executions.get(0).getScheduledOccurrence());
    }

    @Test
    @DisplayName("Scheduler concurrency: concurrent schedule initialization performs atomic conditional update")
    void schedulerConcurrency_ConcurrentInitialization_OnlyOneInitializes() throws Exception {
        Workflow workflow = TestDataFactory.createScheduledWorkflow("user-sched", "Init Concurrency Flow", "0 0 16 * * ?", "UTC");
        workflow = workflowRepository.save(workflow);
        assertNull(workflow.getTriggerConfig().getNextFireTime());

        Instant now = Instant.parse("2026-10-01T15:00:00Z");

        AdonisScheduler s1 = new AdonisScheduler(workflowRepository, executionService, scheduledOccurrenceRepository, mongoTemplate);
        AdonisScheduler s2 = new AdonisScheduler(workflowRepository, executionService, scheduledOccurrenceRepository, mongoTemplate);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Future<Integer> f1 = executor.submit(() -> {
            latch.await();
            return s1.checkAndRunSchedules(now);
        });
        Future<Integer> f2 = executor.submit(() -> {
            latch.await();
            return s2.checkAndRunSchedules(now);
        });

        latch.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        Workflow refreshed = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertNotNull(refreshed.getTriggerConfig().getNextFireTime());
        assertEquals(Instant.parse("2026-10-01T16:00:00Z"), refreshed.getTriggerConfig().getNextFireTime());
    }
}
