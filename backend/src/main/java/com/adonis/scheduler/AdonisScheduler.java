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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 * 4. Configurable enable/disable
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
    private final long misfireThresholdMs;

    @Autowired
    public AdonisScheduler(
            WorkflowRepository workflowRepository,
            WorkflowExecutionService executionService,
            ScheduledOccurrenceRepository scheduledOccurrenceRepository,
            MongoTemplate mongoTemplate,
            @Value("${adonis.scheduler.misfire-threshold-ms:300000}") long misfireThresholdMs) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.executionService = Objects.requireNonNull(executionService, "WorkflowExecutionService must not be null");
        this.scheduledOccurrenceRepository = Objects.requireNonNull(scheduledOccurrenceRepository, "ScheduledOccurrenceRepository must not be null");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "MongoTemplate must not be null");
        this.misfireThresholdMs = Math.max(1000, misfireThresholdMs);
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
     * 2. Checks due time and misfire policy (DO_NOT_CATCH_UP)
     * 3. Enforces durable duplicate occurrence protection
     * 4. Enqueues execution via WorkflowExecutionService
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
                config.setNextFireTime(nextZoned.toInstant());
                workflowRepository.save(workflow);
                log.info("Initialized schedule for workflow {}: nextFireTime={}", workflow.getId(), config.getNextFireTime());
            }
            return false;
        }

        // 2. Not due yet
        if (nextDue.isAfter(now)) {
            return false;
        }

        // 3. Misfire policy check: DO_NOT_CATCH_UP
        // If nextDue is older than misfire threshold, skip without firing and schedule next occurrence from now
        if (Duration.between(nextDue, now).toMillis() > misfireThresholdMs) {
            log.warn("Misfire detected for workflow {}: scheduled occurrence {} was missed due to downtime (gap {}ms). Policy=DO_NOT_CATCH_UP. Skipping to next occurrence.",
                    workflow.getId(), nextDue, Duration.between(nextDue, now).toMillis());
            ZonedDateTime nextZoned = cron.next(nowZoned);
            config.setNextFireTime(nextZoned != null ? nextZoned.toInstant() : null);
            workflowRepository.save(workflow);
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
            if (nextZoned != null) {
                config.setNextFireTime(nextZoned.toInstant());
                workflowRepository.save(workflow);
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
        config.setNextFireTime(nextZoned != null ? nextZoned.toInstant() : null);
        workflowRepository.save(workflow);

        log.info("Scheduled workflow queued: executionId={}, workflowId={}, occurrenceKey={}",
                response.executionId(), workflow.getId(), occurrenceKey);
        return true;
    }
}
