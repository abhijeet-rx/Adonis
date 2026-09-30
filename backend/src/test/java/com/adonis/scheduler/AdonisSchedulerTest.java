package com.adonis.scheduler;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.model.*;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
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
                mongoTemplate
        );
        // Default startup time before test schedule points
        scheduler.setStartupTime(Instant.parse("2026-10-01T10:00:00Z"));
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
        // Fix #11: Targeted update instead of full save
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Workflow.class));
        verify(workflowRepository, never()).save(any());
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

        Instant now = Instant.parse("2026-10-01T10:05:02Z");

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
        // Fix #11: Scheduler does NOT overwrite entire workflow
        verify(workflowRepository, never()).save(any());
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Workflow.class));
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

    // ==========================================
    // Phase 8.1 FIX #5 — Strict DO_NOT_CATCH_UP Downtime Tests
    // ==========================================

    @Test
    void processWorkflowSchedule_MisfirePolicy_DoNotCatchUp_BackendDown1Minute() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant missedScheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(missedScheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // Backend was down and booted up 1 minute after scheduled time
        Instant startupTime = Instant.parse("2026-10-01T10:06:00Z");
        scheduler.setStartupTime(startupTime);

        Instant now = Instant.parse("2026-10-01T10:06:05Z");
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        // DO_NOT_CATCH_UP: Must NOT fire missed occurrence
        assertFalse(result);
        verifyNoInteractions(executionService);
        verify(mongoTemplate, never()).insert(any(ScheduledOccurrence.class));
        // Advances to next occurrence after now (10:10:00)
        assertEquals(Instant.parse("2026-10-01T10:10:00Z"), config.getNextFireTime());
    }

    @Test
    void processWorkflowSchedule_MisfirePolicy_DoNotCatchUp_BackendDown5Minutes() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant missedScheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(missedScheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // Backend booted up 5 minutes after scheduled time
        scheduler.setStartupTime(Instant.parse("2026-10-01T10:10:00Z"));

        Instant now = Instant.parse("2026-10-01T10:10:02Z");
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        verifyNoInteractions(executionService);
        // Advances to next occurrence after 10:10:02 (10:15:00)
        assertEquals(Instant.parse("2026-10-01T10:15:00Z"), config.getNextFireTime());
    }

    @Test
    void processWorkflowSchedule_MisfirePolicy_DoNotCatchUp_BackendDownSeveralHours() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 * * * *", "UTC", null, null, false);
        Instant missedScheduledTime = Instant.parse("2026-10-01T10:00:00Z");
        config.setNextFireTime(missedScheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // 4 hours downtime
        scheduler.setStartupTime(Instant.parse("2026-10-01T14:00:00Z"));

        Instant now = Instant.parse("2026-10-01T14:05:00Z");
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        verifyNoInteractions(executionService);
        // Advances to next occurrence after 14:05:00 (15:00:00)
        assertEquals(Instant.parse("2026-10-01T15:00:00Z"), config.getNextFireTime());
    }

    @Test
    void processWorkflowSchedule_MisfirePolicy_DoNotCatchUp_BackendDownSeveralDays() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 9 * * *", "UTC", null, null, false);
        Instant missedScheduledTime = Instant.parse("2026-10-01T09:00:00Z");
        config.setNextFireTime(missedScheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // 3 days downtime
        scheduler.setStartupTime(Instant.parse("2026-10-04T12:00:00Z"));

        Instant now = Instant.parse("2026-10-04T12:01:00Z");
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        verifyNoInteractions(executionService);
        // Advances to next valid 09:00 occurrence after now (2026-10-05T09:00:00Z)
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), config.getNextFireTime());
    }

    @Test
    void processWorkflowSchedule_RestartImmediatelyBeforeScheduledTime_FiresWhenDue() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 10 * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:00:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // Restarted at 09:59:50 (10s before scheduled time)
        scheduler.setStartupTime(Instant.parse("2026-10-01T09:59:50Z"));

        Instant now = Instant.parse("2026-10-01T10:00:02Z");
        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-1", "wf-1"));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        // Fired because startup was BEFORE scheduled time!
        assertTrue(result);
        verify(executionService).enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime));
    }

    @Test
    void processWorkflowSchedule_RestartImmediatelyAfterScheduledTime_SkipsOccurrence() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 10 * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:00:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // Restarted at 10:00:05 (5s after scheduled time passed)
        scheduler.setStartupTime(Instant.parse("2026-10-01T10:00:05Z"));

        Instant now = Instant.parse("2026-10-01T10:00:10Z");
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        // Skipped because occurrence passed during downtime before startup
        assertFalse(result);
        verifyNoInteractions(executionService);
    }

    // ==========================================
    // Phase 8.1 FIX #11 — Scheduler Save Race Concurrency Protection
    // ==========================================

    @Test
    void processWorkflowSchedule_UsesTargetedMongoUpdates_NeverOverwritesUserEdits() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-race-1", "user-1", "User Defined Name", "User Defined Desc",
                WorkflowStatus.ACTIVE, List.of(new WorkflowNode("node-1", "test", java.util.Map.of())),
                List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:02Z");
        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-race", "wf-race-1"));

        scheduler.processWorkflowSchedule(workflow, now);

        // Full workflow save must NEVER be called by scheduler
        verify(workflowRepository, never()).save(any(Workflow.class));

        // Targeted MongoDB update must be used
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq(Workflow.class));

        assertTrue(queryCaptor.getValue().getQueryObject().containsKey("_id"));
        assertEquals("wf-race-1", queryCaptor.getValue().getQueryObject().get("_id"));

        // Verify that only triggerConfig fields and updatedAt are targeted, not name/description/nodes
        var updateObj = updateCaptor.getValue().getUpdateObject();
        var setObj = (org.bson.Document) updateObj.get("$set");
        assertNotNull(setObj);
        assertTrue(setObj.containsKey("triggerConfig.nextFireTime"));
        assertTrue(setObj.containsKey("triggerConfig.lastScheduledFireTime"));
        assertTrue(setObj.containsKey("updatedAt"));
        assertFalse(setObj.containsKey("name"), "Scheduler must never update workflow name");
        assertFalse(setObj.containsKey("nodes"), "Scheduler must never update workflow nodes");
        assertFalse(setObj.containsKey("edges"), "Scheduler must never update workflow edges");
        assertFalse(setObj.containsKey("description"), "Scheduler must never update workflow description");
    }

    @Test
    void processWorkflowSchedule_TimezoneSupport_EvaluatesCorrectly() {
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
        WorkflowTriggerConfig config1 = new WorkflowTriggerConfig("invalid-cron", "UTC", null, null, false);
        Workflow wf1 = new Workflow("wf-bad", "user-1", "Bad WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config1, Instant.now(), Instant.now());

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

        assertEquals(1, queuedCount);
        verify(executionService).enqueueScheduledExecution(eq(wf2), anyString(), eq(scheduledTime));
    }
}
