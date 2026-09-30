package com.adonis.scheduler;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.execution.WorkflowTriggerValidator;
import com.adonis.model.*;
import com.adonis.queue.QueueSubmissionException;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.WorkflowRepository;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Centralized, scalable scheduler evaluating active cron workflows.
 * Submits due executions through the existing Redis asynchronous pipeline.
 *
 * Guarantees:
 * 1. Idempotent scheduling with durable duplicate protection across multiple backend instances
 * 2. Strict misfire policy: DO_NOT_CATCH_UP (no stale replay after downtime)
 * 3. Failure isolation: errors in one workflow never halt the scheduler
 * 4. Stale scheduler decision race prevention: conditional claim on unchanged schedule state
 * 5. Redis queue failure resilience: transient infrastructure outages keep occurrences recoverable
 * 6. Safe concurrent updates: uses targeted MongoDB field updates rather than replacing entire workflow
 * 7. Configurable enable/disable
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "adonis.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AdonisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdonisScheduler.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionService executionService;
    private final ScheduledOccurrenceRepository scheduledOccurrenceRepository;
    private final MongoTemplate mongoTemplate;
    private Instant startupTime;

    @Autowired
    public AdonisScheduler(
            WorkflowRepository workflowRepository,
            WorkflowExecutionService executionService,
            ScheduledOccurrenceRepository scheduledOccurrenceRepository,
            MongoTemplate mongoTemplate) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.executionService = Objects.requireNonNull(executionService, "WorkflowExecutionService must not be null");
        this.scheduledOccurrenceRepository = Objects.requireNonNull(scheduledOccurrenceRepository, "ScheduledOccurrenceRepository must not be null");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "MongoTemplate must not be null");
        this.startupTime = Instant.now();
    }

    /**
     * Backward-compatible constructor for testing or configuration passing misfire threshold.
     */
    public AdonisScheduler(
            WorkflowRepository workflowRepository,
            WorkflowExecutionService executionService,
            ScheduledOccurrenceRepository scheduledOccurrenceRepository,
            MongoTemplate mongoTemplate,
            long ignoredMisfireThresholdMs) {
        this(workflowRepository, executionService, scheduledOccurrenceRepository, mongoTemplate);
    }

    public Instant getStartupTime() {
        return startupTime;
    }

    public void setStartupTime(Instant startupTime) {
        this.startupTime = startupTime != null ? startupTime : Instant.now();
    }

    /**
     * Periodic background evaluation loop.
     */
    @Scheduled(fixedDelayString = "${adonis.scheduler.polling-interval-ms:10000}")
    public void scheduleLoop() {
        checkAndRunSchedules(Instant.now());
    }

    /**
     * Evaluates all active SCHEDULE workflows against current time.
     * Accessible for testing with explicit clock times.
     *
     * @param now reference instant
     * @return number of scheduled executions queued
     */
    public int checkAndRunSchedules(Instant now) {
        List<Workflow> scheduledWorkflows = workflowRepository.findByStatusAndTriggerType(
                WorkflowStatus.ACTIVE,
                WorkflowTriggerType.SCHEDULE
        );

        int queuedCount = 0;
        for (Workflow workflow : scheduledWorkflows) {
            try {
                boolean queued = processWorkflowSchedule(workflow, now);
                if (queued) {
                    queuedCount++;
                }
            } catch (Exception ex) {
                // Failure isolation: One malformed workflow must NOT stop the scheduler
                log.error("Scheduler failure isolation: Error processing schedule for workflow {}: {}",
                        workflow.getId(), ex.getMessage(), ex);
            }
        }

        // Secondary sweep for any remaining FAILED_RETRYABLE occurrences
        queuedCount += recoverOrphanFailedOccurrences(now);

        return queuedCount;
    }

    /**
     * Processes a single scheduled workflow:
     * 1. Validates cron and timezone safely
     * 2. Checks due time and strict misfire policy (DO_NOT_CATCH_UP across downtime)
     * 3. Atomically verifies evaluated schedule state is still current before claiming (Fix Stale Scheduler Race)
     * 4. Enforces durable duplicate occurrence protection via MongoDB unique index with explicit status lifecycle
     * 5. Enqueues execution via WorkflowExecutionService into Redis Streams
     * 6. Advances schedule fire times only on successful queue submission (survives Redis failures)
     */
    public boolean processWorkflowSchedule(Workflow workflow, Instant now) {
        if (workflow == null || workflow.getStatus() != WorkflowStatus.ACTIVE || workflow.getTriggerType() != WorkflowTriggerType.SCHEDULE) {
            return false;
        }

        WorkflowTriggerConfig config = workflow.getTriggerConfig();
        if (config == null || config.getCronExpression() == null || config.getCronExpression().isBlank()) {
            log.warn("Workflow {} has SCHEDULE triggerType but missing cronExpression. Skipping.", workflow.getId());
            return false;
        }

        ZoneId zoneId;
        try {
            zoneId = WorkflowTriggerValidator.parseAndValidateZoneId(config.getTimezone());
        } catch (Exception ex) {
            log.warn("Workflow {} has invalid timezone '{}'. Skipping.", workflow.getId(), config.getTimezone());
            return false;
        }

        CronExpression cron;
        try {
            cron = WorkflowTriggerValidator.parseAndValidateCron(config.getCronExpression());
        } catch (Exception ex) {
            log.warn("Workflow {} has invalid cronExpression '{}'. Skipping.", workflow.getId(), config.getCronExpression());
            return false;
        }

        ZonedDateTime nowZoned = ZonedDateTime.ofInstant(now, zoneId);
        Instant nextDue = config.getNextFireTime();

        // 1. First initialization: compute next valid occurrence from now
        if (nextDue == null) {
            ZonedDateTime nextZoned = cron.next(nowZoned);
            if (nextZoned != null) {
                Instant calculatedNext = nextZoned.toInstant();
                config.setNextFireTime(calculatedNext);
                updateNextFireTime(workflow.getId(), calculatedNext);
                log.info("Initialized schedule for workflow {}: nextFireTime={}", workflow.getId(), calculatedNext);
            }
            return false;
        }

        // 2. Not due yet
        if (nextDue.isAfter(now)) {
            return false;
        }

        // 3. Strict Misfire policy check: DO_NOT_CATCH_UP
        // If nextDue occurred before application startup time, it was missed during downtime.
        // Under DO_NOT_CATCH_UP, we NEVER replay missed downtime occurrences or storm the queue.
        if (nextDue.isBefore(startupTime)) {
            log.warn("Misfire detected for workflow {}: scheduled occurrence {} was missed during downtime (before startup {}). Policy=DO_NOT_CATCH_UP. Skipping to next occurrence after now.",
                    workflow.getId(), nextDue, startupTime);
            ZonedDateTime nextZoned = cron.next(nowZoned);
            Instant calculatedNext = nextZoned != null ? nextZoned.toInstant() : null;
            config.setNextFireTime(calculatedNext);
            updateNextFireTime(workflow.getId(), calculatedNext);
            return false;
        }

        // 4. Phase 8.1.1 FIX: Stale scheduler decision race protection.
        // Atomically verify that the evaluated schedule state (cron, timezone, status, nextFireTime) is still current.
        // If user changed cron, timezone, status, or triggerType between read and claim, modifiedCount will be 0.
        Query scheduleQuery = Query.query(
                Criteria.where("_id").is(workflow.getId())
                        .and("status").is(WorkflowStatus.ACTIVE)
                        .and("triggerType").is(WorkflowTriggerType.SCHEDULE)
                        .and("triggerConfig.cronExpression").is(config.getCronExpression())
                        .and("triggerConfig.timezone").is(config.getTimezone())
                        .and("triggerConfig.nextFireTime").is(nextDue)
        );
        Update claimTouchUpdate = new Update().set("updatedAt", Instant.now());
        UpdateResult claimCheck = mongoTemplate.updateFirst(scheduleQuery, claimTouchUpdate, Workflow.class);
        if (claimCheck != null && claimCheck.getModifiedCount() == 0) {
            log.info("Workflow schedule modified or stale snapshot detected for workflow {}. Abandoning scheduler claim.", workflow.getId());
            return false;
        }

        // 5. Phase 8.1.1 FIX: Atomic occurrence claim with explicit status lifecycle (CLAIMED -> ENQUEUED / FAILED_RETRYABLE)
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey(workflow.getId(), nextDue);
        ScheduledOccurrence occurrence = new ScheduledOccurrence(
                occurrenceKey,
                workflow.getId(),
                nextDue,
                null,
                ScheduledOccurrenceStatus.CLAIMED,
                now
        );

        boolean isNewClaim;
        try {
            mongoTemplate.insert(occurrence);
            isNewClaim = true;
        } catch (DuplicateKeyException ex) {
            isNewClaim = false;
        }

        ZonedDateTime nextZoned = cron.next(nowZoned);
        Instant calculatedNext = nextZoned != null ? nextZoned.toInstant() : null;

        if (!isNewClaim) {
            // Occurrence already exists in MongoDB
            ScheduledOccurrence existing = scheduledOccurrenceRepository.findById(occurrenceKey).orElse(null);
            if (existing == null || existing.getStatus() == ScheduledOccurrenceStatus.ENQUEUED) {
                log.info("Duplicate scheduled occurrence already claimed/enqueued: workflowId={}, occurrenceKey={}", workflow.getId(), occurrenceKey);
                // Advance nextFireTime if the workflow in memory still points to this occurrence
                if (nextDue.equals(config.getNextFireTime()) && calculatedNext != null) {
                    config.setNextFireTime(calculatedNext);
                    config.setLastScheduledFireTime(nextDue);
                    updateFireTimes(workflow.getId(), calculatedNext, nextDue);
                }
                return false;
            }
            if (existing.getStatus() == ScheduledOccurrenceStatus.FAILED_RETRYABLE
                    || existing.getStatus() == ScheduledOccurrenceStatus.CLAIMED) {
                // Phase 8.1.1 FIX: Recover occurrence that previously failed queue submission
                log.info("Recovering failed/claimed scheduled occurrence: workflowId={}, occurrenceKey={}, executionId={}",
                        workflow.getId(), occurrenceKey, existing.getExecutionId());
                return recoverAndEnqueueOccurrence(workflow, existing, nextDue, cron, nowZoned);
            }
            return false;
        }

        // 6. Winning scheduler instance creates QUEUED execution and enqueues job
        ExecuteWorkflowResponse response;
        try {
            response = executionService.enqueueScheduledExecution(workflow, occurrenceKey, nextDue);
        } catch (Exception ex) {
            log.error("Failed to enqueue scheduled execution for workflow {}: {}", workflow.getId(), ex.getMessage(), ex);
            if (isRecoverableQueueFailure(ex)) {
                String executionId = null;
                if (ex instanceof QueueSubmissionException qse) {
                    executionId = qse.getExecutionId();
                }
                occurrence.setExecutionId(executionId);
                occurrence.setStatus(ScheduledOccurrenceStatus.FAILED_RETRYABLE);
                occurrence.setErrorMessage(ex.getMessage());
                scheduledOccurrenceRepository.save(occurrence);
                log.warn("Scheduled occurrence marked FAILED_RETRYABLE for recovery: workflowId={}, occurrenceKey={}, executionId={}",
                        workflow.getId(), occurrenceKey, executionId);
            }
            // Phase 8.1.1 FIX: DO NOT advance schedule fire times when queue submission fails!
            return false;
        }

        // 7. Success branch: record executionId, mark ENQUEUED, and advance workflow fire times
        occurrence.setExecutionId(response.executionId());
        occurrence.setStatus(ScheduledOccurrenceStatus.ENQUEUED);
        scheduledOccurrenceRepository.save(occurrence);

        config.setLastScheduledFireTime(nextDue);
        config.setNextFireTime(calculatedNext);
        updateFireTimes(workflow.getId(), calculatedNext, nextDue);

        log.info("Scheduled workflow queued: executionId={}, workflowId={}, occurrenceKey={}",
                response.executionId(), workflow.getId(), occurrenceKey);
        return true;
    }

    private boolean recoverAndEnqueueOccurrence(
            Workflow workflow,
            ScheduledOccurrence occurrence,
            Instant nextDue,
            CronExpression cron,
            ZonedDateTime nowZoned) {
        String executionId = occurrence.getExecutionId();
        ExecuteWorkflowResponse response;
        try {
            if (executionId != null && !executionId.isBlank()) {
                response = executionService.retryScheduledQueueSubmission(executionId, workflow, occurrence.getId());
            } else {
                response = executionService.enqueueScheduledExecution(workflow, occurrence.getId(), nextDue);
            }
        } catch (Exception ex) {
            log.error("Failed to recover scheduled occurrence {}: {}", occurrence.getId(), ex.getMessage(), ex);
            if (isRecoverableQueueFailure(ex)) {
                String errExecutionId = (ex instanceof QueueSubmissionException qse) ? qse.getExecutionId() : executionId;
                if (errExecutionId != null && occurrence.getExecutionId() == null) {
                    occurrence.setExecutionId(errExecutionId);
                }
                occurrence.setStatus(ScheduledOccurrenceStatus.FAILED_RETRYABLE);
                occurrence.setErrorMessage(ex.getMessage());
                occurrence.incrementRetryCount();
                scheduledOccurrenceRepository.save(occurrence);
            }
            return false;
        }

        occurrence.setExecutionId(response.executionId());
        occurrence.setStatus(ScheduledOccurrenceStatus.ENQUEUED);
        occurrence.incrementRetryCount();
        occurrence.setErrorMessage(null);
        scheduledOccurrenceRepository.save(occurrence);

        // Advance schedule once enqueued
        ZonedDateTime nextZoned = cron.next(nowZoned);
        Instant calculatedNext = nextZoned != null ? nextZoned.toInstant() : null;
        if (workflow.getTriggerConfig() != null) {
            workflow.getTriggerConfig().setLastScheduledFireTime(nextDue);
            workflow.getTriggerConfig().setNextFireTime(calculatedNext);
        }
        updateFireTimes(workflow.getId(), calculatedNext, nextDue);

        log.info("Scheduled occurrence recovered and enqueued: executionId={}, occurrenceKey={}",
                response.executionId(), occurrence.getId());
        return true;
    }

    private int recoverOrphanFailedOccurrences(Instant now) {
        List<ScheduledOccurrence> failedOccurrences = scheduledOccurrenceRepository.findByStatus(ScheduledOccurrenceStatus.FAILED_RETRYABLE);
        if (failedOccurrences.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (ScheduledOccurrence occurrence : failedOccurrences) {
            try {
                Optional<Workflow> wfOpt = workflowRepository.findById(occurrence.getWorkflowId());
                if (wfOpt.isEmpty() || wfOpt.get().getStatus() != WorkflowStatus.ACTIVE
                        || wfOpt.get().getTriggerType() != WorkflowTriggerType.SCHEDULE) {
                    continue;
                }
                Workflow wf = wfOpt.get();
                WorkflowTriggerConfig config = wf.getTriggerConfig();
                if (config == null || config.getCronExpression() == null) {
                    continue;
                }
                ZoneId zoneId = WorkflowTriggerValidator.parseAndValidateZoneId(config.getTimezone());
                CronExpression cron = WorkflowTriggerValidator.parseAndValidateCron(config.getCronExpression());
                ZonedDateTime nowZoned = ZonedDateTime.ofInstant(now, zoneId);

                boolean success = recoverAndEnqueueOccurrence(wf, occurrence, occurrence.getScheduledFireTime(), cron, nowZoned);
                if (success) {
                    recovered++;
                }
            } catch (Exception ex) {
                log.warn("Failed recovery sweep for occurrence {}: {}", occurrence.getId(), ex.getMessage());
            }
        }
        return recovered;
    }

    public static boolean isRecoverableQueueFailure(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof QueueSubmissionException) {
            return true;
        }
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage() != null ? current.getMessage().toLowerCase() : "";
            String name = current.getClass().getName().toLowerCase();
            if (name.contains("redis") || name.contains("connect") || name.contains("socket") || name.contains("timeout")
                    || msg.contains("connection refused") || msg.contains("redis") || msg.contains("timeout")
                    || msg.contains("broken pipe") || msg.contains("queue service is unavailable")
                    || msg.contains("failed to queue")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void updateNextFireTime(String workflowId, Instant nextFireTime) {
        Query query = Query.query(Criteria.where("_id").is(workflowId));
        Update update = new Update();
        if (nextFireTime != null) {
            update.set("triggerConfig.nextFireTime", nextFireTime);
        } else {
            update.unset("triggerConfig.nextFireTime");
        }
        update.set("updatedAt", Instant.now());
        mongoTemplate.updateFirst(query, update, Workflow.class);
    }

    private void updateFireTimes(String workflowId, Instant nextFireTime, Instant lastScheduledFireTime) {
        Query query = Query.query(Criteria.where("_id").is(workflowId));
        Update update = new Update();
        if (nextFireTime != null) {
            update.set("triggerConfig.nextFireTime", nextFireTime);
        } else {
            update.unset("triggerConfig.nextFireTime");
        }
        if (lastScheduledFireTime != null) {
            update.set("triggerConfig.lastScheduledFireTime", lastScheduledFireTime);
        } else {
            update.unset("triggerConfig.lastScheduledFireTime");
        }
        update.set("updatedAt", Instant.now());
        mongoTemplate.updateFirst(query, update, Workflow.class);
    }
}
