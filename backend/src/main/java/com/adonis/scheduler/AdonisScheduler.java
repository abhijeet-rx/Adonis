package com.adonis.scheduler;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.execution.WorkflowTriggerValidator;
import com.adonis.model.ScheduledOccurrence;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.WorkflowRepository;
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

/**
 * Centralized, scalable scheduler evaluating active cron workflows.
 * Submits due executions through the existing Redis asynchronous pipeline.
 *
 * Guarantees:
 * 1. Idempotent scheduling with durable duplicate protection across multiple backend instances
 * 2. Strict misfire policy: DO_NOT_CATCH_UP (no stale replay after downtime)
 * 3. Failure isolation: errors in one workflow never halt the scheduler
 * 4. Safe concurrent updates: uses targeted MongoDB field updates rather than replacing entire workflow
 * 5. Configurable enable/disable
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
        return queuedCount;
    }

    /**
     * Processes a single scheduled workflow:
     * 1. Validates cron and timezone safely
     * 2. Checks due time and strict misfire policy (DO_NOT_CATCH_UP across downtime)
     * 3. Enforces durable duplicate occurrence protection via MongoDB unique index
     * 4. Enqueues execution via WorkflowExecutionService into Redis Streams
     * 5. Performs targeted field update in MongoDB to avoid race conditions with user edits
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

        // 4. Durable duplicate protection: atomic insert of ScheduledOccurrence
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey(workflow.getId(), nextDue);
        ScheduledOccurrence occurrence = new ScheduledOccurrence(occurrenceKey, workflow.getId(), nextDue, null, now);

        try {
            mongoTemplate.insert(occurrence);
        } catch (DuplicateKeyException ex) {
            // Another scheduler instance claimed this occurrence!
            log.info("Duplicate scheduled occurrence ignored: workflowId={}, occurrenceKey={}", workflow.getId(), occurrenceKey);
            ZonedDateTime nextZoned = cron.next(nowZoned);
            Instant calculatedNext = nextZoned != null ? nextZoned.toInstant() : null;
            config.setNextFireTime(calculatedNext);
            if (calculatedNext != null) {
                updateNextFireTime(workflow.getId(), calculatedNext);
            }
            return false;
        }

        // 5. Winning scheduler instance creates QUEUED execution and enqueues job
        ExecuteWorkflowResponse response;
        try {
            response = executionService.enqueueScheduledExecution(workflow, occurrenceKey, nextDue);
        } catch (Exception ex) {
            log.error("Failed to enqueue scheduled execution for workflow {}: {}", workflow.getId(), ex.getMessage(), ex);
            return false;
        }

        // 6. Record executionId on occurrence and advance workflow nextFireTime
        occurrence.setExecutionId(response.executionId());
        scheduledOccurrenceRepository.save(occurrence);

        config.setLastScheduledFireTime(nextDue);
        ZonedDateTime nextZoned = cron.next(nowZoned);
        Instant calculatedNext = nextZoned != null ? nextZoned.toInstant() : null;
        config.setNextFireTime(calculatedNext);
        updateFireTimes(workflow.getId(), calculatedNext, nextDue);

        log.info("Scheduled workflow queued: executionId={}, workflowId={}, occurrenceKey={}",
                response.executionId(), workflow.getId(), occurrenceKey);
        return true;
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
