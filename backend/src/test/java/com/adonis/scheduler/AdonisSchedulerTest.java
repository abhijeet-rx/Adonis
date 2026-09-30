package com.adonis.scheduler;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.model.*;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdonisSchedulerTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionService executionService;

    @Mock
    private ScheduledOccurrenceRepository scheduledOccurrenceRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    private AdonisScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AdonisScheduler(
                workflowRepository,
                executionService,
                scheduledOccurrenceRepository,
                mongoTemplate,
                300000L // 5 minutes misfire threshold
        );
    }

    @Test
    void processWorkflowSchedule_InactiveWorkflow_ReturnsFalse() {
        Workflow workflow = Workflow.create("user-1", "Draft WF", null, WorkflowStatus.DRAFT, List.of(), List.of());
        boolean result = scheduler.processWorkflowSchedule(workflow, Instant.now());
        assertFalse(result);
        verifyNoInteractions(executionService);
    }

    @Test
    void processWorkflowSchedule_ManualTriggerType_ReturnsFalse() {
        Workflow workflow = Workflow.create("user-1", "Manual WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.MANUAL, new WorkflowTriggerConfig());
        boolean result = scheduler.processWorkflowSchedule(workflow, Instant.now());
        assertFalse(result);
        verifyNoInteractions(executionService);
    }

    @Test
    void processWorkflowSchedule_FirstInitialization_ComputesNextFireTimeWithoutFiring() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:01:00Z");
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        assertNotNull(config.getNextFireTime());
        // Next fire time for 10:01:00 with "0 */5 * * * *" is 10:05:00
        assertEquals(Instant.parse("2026-10-01T10:05:00Z"), config.getNextFireTime());
        verify(workflowRepository).save(workflow);
        verifyNoInteractions(executionService);
    }

    @Test
    void processWorkflowSchedule_NotYetDue_ReturnsFalse() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        config.setNextFireTime(Instant.parse("2026-10-01T10:05:00Z"));
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:03:00Z"); // before 10:05:00
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        verifyNoInteractions(executionService);
    }

    @Test
    void processWorkflowSchedule_Due_FiresExecutionAndSavesOccurrence() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:02Z"); // 2s after scheduled time (within threshold)

        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-sched-1", "wf-1"));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertTrue(result);
        // Verify atomic ScheduledOccurrence inserted
        verify(mongoTemplate).insert(any(ScheduledOccurrence.class));
        verify(executionService).enqueueScheduledExecution(eq(workflow), eq("wf-1:" + scheduledTime.toEpochMilli()), eq(scheduledTime));
        // Verify occurrence updated with executionId
        verify(scheduledOccurrenceRepository).save(any(ScheduledOccurrence.class));
        // Verify next fire time advanced to 10:10:00
        assertEquals(Instant.parse("2026-10-01T10:10:00Z"), config.getNextFireTime());
        assertEquals(scheduledTime, config.getLastScheduledFireTime());
        verify(workflowRepository).save(workflow);
    }

    @Test
    void processWorkflowSchedule_DuplicateOccurrence_RacedWithAnotherScheduler_SkipsExecution() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:01Z");

        // Another backend instance already inserted the occurrence record!
        doThrow(new DuplicateKeyException("Duplicate occurrence key")).when(mongoTemplate).insert(any(ScheduledOccurrence.class));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        // Execution must NOT be queued by this instance
        verifyNoInteractions(executionService);
        // Next fire time advanced safely
        assertEquals(Instant.parse("2026-10-01T10:10:00Z"), config.getNextFireTime());
    }

    @Test
    void processWorkflowSchedule_MisfirePolicy_DoNotCatchUp_AfterDowntime() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 * * * *", "UTC", null, null, false);
        // Scheduled at 10:00:00, but application was down until 12:30:00 (gap > 5 minutes misfireThreshold)
        Instant oldMissedTime = Instant.parse("2026-10-01T10:00:00Z");
        config.setNextFireTime(oldMissedTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T12:30:00Z"); // 2.5 hours downtime

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        // Under DO_NOT_CATCH_UP: must NOT execute the old 10:00:00 occurrence!
        assertFalse(result);
        verifyNoInteractions(executionService);
        verify(mongoTemplate, never()).insert(any(ScheduledOccurrence.class));

        // Advances to the next valid occurrence from 12:30:00 (which is 13:00:00)
        assertEquals(Instant.parse("2026-10-01T13:00:00Z"), config.getNextFireTime());
        verify(workflowRepository).save(workflow);
    }

    @Test
    void processWorkflowSchedule_TimezoneSupport_EvaluatesCorrectly() {
        // Daily at 09:00 in Asia/Kolkata (UTC +05:30) -> 03:30:00 UTC
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 9 * * *", "Asia/Kolkata", null, null, false);
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T02:00:00Z"); // 07:30 IST
        scheduler.processWorkflowSchedule(workflow, now);

        // Next fire time: 09:00 IST on 2026-10-01 = 03:30:00 UTC
        assertEquals(Instant.parse("2026-10-01T03:30:00Z"), config.getNextFireTime());
    }

    @Test
    void checkAndRunSchedules_FailureIsolation_OneBrokenWorkflowDoesNotStopOthers() {
        // Workflow 1: invalid cron
        WorkflowTriggerConfig config1 = new WorkflowTriggerConfig("invalid-cron", "UTC", null, null, false);
        Workflow wf1 = new Workflow("wf-bad", "user-1", "Bad WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config1, Instant.now(), Instant.now());

        // Workflow 2: valid due workflow
        WorkflowTriggerConfig config2 = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config2.setNextFireTime(scheduledTime);
        Workflow wf2 = new Workflow("wf-good", "user-1", "Good WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config2, Instant.now(), Instant.now());

        when(workflowRepository.findByStatusAndTriggerType(WorkflowStatus.ACTIVE, WorkflowTriggerType.SCHEDULE))
                .thenReturn(List.of(wf1, wf2));

        Instant now = Instant.parse("2026-10-01T10:05:01Z");
        when(executionService.enqueueScheduledExecution(eq(wf2), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-good", "wf-good"));

        int queuedCount = scheduler.checkAndRunSchedules(now);

        // Good workflow was processed and queued despite bad workflow error!
        assertEquals(1, queuedCount);
        verify(executionService).enqueueScheduledExecution(eq(wf2), anyString(), eq(scheduledTime));
    }
}
