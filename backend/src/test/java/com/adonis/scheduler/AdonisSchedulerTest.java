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

import com.mongodb.client.result.UpdateResult;

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

        // Phase 8.1.2: conditionalInitNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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
        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(Workflow.class));
    }

    @Test
    void processWorkflowSchedule_DuplicateOccurrence_RacedWithAnotherScheduler_SkipsExecution() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:01Z");

        // Phase 8.1.2: claim check needs matchedCount > 0 to proceed past claim
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: conditionalAdvanceNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: conditionalAdvanceNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: conditionalAdvanceNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: conditionalAdvanceNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: conditionalAdvanceNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-race", "wf-race-1"));

        scheduler.processWorkflowSchedule(workflow, now);

        // Full workflow save must NEVER be called by scheduler
        verify(workflowRepository, never()).save(any(Workflow.class));

        // Targeted MongoDB update must be used
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2)).updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq(Workflow.class));

        // First update is atomic schedule verification
        Query verifyQuery = queryCaptor.getAllValues().get(0);
        assertTrue(verifyQuery.getQueryObject().containsKey("triggerConfig.cronExpression"));
        assertEquals("0 */5 * * * *", verifyQuery.getQueryObject().get("triggerConfig.cronExpression"));
        assertEquals(scheduledTime, verifyQuery.getQueryObject().get("triggerConfig.nextFireTime"));

        // Second update is fire times advancement
        Query fireTimesQuery = queryCaptor.getAllValues().get(1);
        assertTrue(fireTimesQuery.getQueryObject().containsKey("_id"));
        assertEquals("wf-race-1", fireTimesQuery.getQueryObject().get("_id"));

        var updateObj = updateCaptor.getAllValues().get(1).getUpdateObject();
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

        // Phase 8.1.2: conditionalInitNextFireTime requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

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

        // Phase 8.1.2: claim check requires matchedCount > 0 for the good workflow
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        when(executionService.enqueueScheduledExecution(eq(wf2), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-good", "wf-good"));

        int queuedCount = scheduler.checkAndRunSchedules(now);

        assertEquals(1, queuedCount);
        verify(executionService).enqueueScheduledExecution(eq(wf2), anyString(), eq(scheduledTime));
    }

    // ==========================================
    // Phase 8.1.1 Hardening Tests
    // ==========================================

    @Test
    void processWorkflowSchedule_StaleSchedulerDecisionRace_ScheduleModifiedByUser_AbortsClaim() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-stale-1", "user-1", "Stale Test WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:01Z");

        // Phase 8.1.2 FIX #1: Simulate user changed cron / nextFireTime in MongoDB — matchedCount is 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        boolean processed = scheduler.processWorkflowSchedule(workflow, now);

        // Claim must fail, iteration must be abandoned (matchedCount == 0)
        assertFalse(processed, "Must abandon iteration when schedule condition check finds matchedCount = 0");
        verify(executionService, never()).enqueueScheduledExecution(any(), any(), any());
        verify(mongoTemplate, never()).insert(any(ScheduledOccurrence.class));
        verify(scheduledOccurrenceRepository, never()).save(any());

        // Schedule was NOT advanced
        assertEquals(scheduledTime, config.getNextFireTime());

        // Next scheduler tick: user changed cron to 18:00 UTC ("0 0 18 * * *")
        WorkflowTriggerConfig updatedConfig = new WorkflowTriggerConfig("0 0 18 * * *", "UTC", null, null, false);
        Workflow reloadedWorkflow = new Workflow("wf-stale-1", "user-1", "Stale Test WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, updatedConfig, Instant.now(), Instant.now());

        // Phase 8.1.2: conditionalInitNextFireTime needs matchedCount > 0 for new schedule
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // First initialization of updated workflow computes next fire time using new cron
        scheduler.processWorkflowSchedule(reloadedWorkflow, now);
        assertEquals(Instant.parse("2026-10-01T18:00:00Z"), updatedConfig.getNextFireTime());
    }

    @Test
    void processWorkflowSchedule_ConcurrentSchedulers_ExactlyOneClaimsOccurrence() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-conc-1", "user-1", "Concurrent WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:01Z");

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // Scheduler A wins the conditional claim and occurrence insert
        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-conc-1", "wf-conc-1"));

        boolean resultA = scheduler.processWorkflowSchedule(workflow, now);
        assertTrue(resultA);
        verify(executionService, times(1)).enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime));

        // Scheduler B runs concurrently on the same workflow/time with its own loaded snapshot:
        WorkflowTriggerConfig configB = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        configB.setNextFireTime(scheduledTime);
        Workflow workflowB = new Workflow("wf-conc-1", "user-1", "Concurrent WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, configB, Instant.now(), Instant.now());

        // MongoDB rejects occurrence insertion due to compound unique index
        doThrow(new DuplicateKeyException("Duplicate occurrence key"))
                .when(mongoTemplate).insert(any(ScheduledOccurrence.class));

        // Existing occurrence is already ENQUEUED
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey("wf-conc-1", scheduledTime);
        ScheduledOccurrence enqueuedOccurrence = new ScheduledOccurrence(
                occurrenceKey, "wf-conc-1", scheduledTime, "exec-conc-1", ScheduledOccurrenceStatus.ENQUEUED, now
        );
        when(scheduledOccurrenceRepository.findById(occurrenceKey)).thenReturn(java.util.Optional.of(enqueuedOccurrence));

        boolean resultB = scheduler.processWorkflowSchedule(workflowB, now);
        assertFalse(resultB, "Second scheduler must not process or claim already enqueued occurrence");

        // Execution service enqueueScheduledExecution must STILL have only been called once!
        verify(executionService, times(1)).enqueueScheduledExecution(any(), any(), any());
    }

    @Test
    void processWorkflowSchedule_RedisEnqueueFails_OccurrenceMarkedFailedRetryableAndNextFireNotAdvanced() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-redis-fail", "user-1", "Redis Fail WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:01Z");

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // Simulate transient Redis failure during queue submission
        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenThrow(new com.adonis.queue.QueueSubmissionException("exec-fail-1", "Redis connection refused: transient error"));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result, "Must return false when enqueue fails");

        // Verify occurrence is saved as FAILED_RETRYABLE with executionId
        ArgumentCaptor<ScheduledOccurrence> occurrenceCaptor = ArgumentCaptor.forClass(ScheduledOccurrence.class);
        verify(scheduledOccurrenceRepository).save(occurrenceCaptor.capture());
        ScheduledOccurrence savedOccurrence = occurrenceCaptor.getValue();
        assertEquals(ScheduledOccurrenceStatus.FAILED_RETRYABLE, savedOccurrence.getStatus());
        assertEquals("exec-fail-1", savedOccurrence.getExecutionId());
        assertTrue(savedOccurrence.getErrorMessage().contains("Redis connection refused"));

        // Next fire time must NOT be advanced in workflow config or via fire-time update
        assertEquals(scheduledTime, config.getNextFireTime(), "Workflow config nextFireTime must NOT advance on failure");
        // Verify only 1 updateFirst was executed (the condition check), and NOT the fire-time advance update
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, times(1)).updateFirst(queryCaptor.capture(), any(), eq(Workflow.class));
        assertTrue(queryCaptor.getValue().getQueryObject().containsKey("triggerConfig.cronExpression"),
                "Only schedule condition check query should be executed, no fire-time advance");
    }

    @Test
    void processWorkflowSchedule_SubsequentTick_RecoversFailedRetryableOccurrenceWhenRedisAvailable() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-recover-1", "user-1", "Recover WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:05Z");

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // Occurrence already exists as FAILED_RETRYABLE from previous failed tick
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey("wf-recover-1", scheduledTime);
        ScheduledOccurrence failedOccurrence = new ScheduledOccurrence(
                occurrenceKey, "wf-recover-1", scheduledTime, "exec-orig-1", ScheduledOccurrenceStatus.FAILED_RETRYABLE, now
        );

        doThrow(new DuplicateKeyException("Duplicate occurrence key"))
                .when(mongoTemplate).insert(any(ScheduledOccurrence.class));
        when(scheduledOccurrenceRepository.findById(occurrenceKey)).thenReturn(java.util.Optional.of(failedOccurrence));

        // Redis is now available: retry succeeds
        when(executionService.retryScheduledQueueSubmission("exec-orig-1", workflow, occurrenceKey))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-orig-1", "wf-recover-1"));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertTrue(result, "Recovery must succeed when Redis is available");
        verify(executionService).retryScheduledQueueSubmission("exec-orig-1", workflow, occurrenceKey);
        verify(executionService, never()).enqueueScheduledExecution(any(), any(), any());

        // Occurrence is marked ENQUEUED
        ArgumentCaptor<ScheduledOccurrence> occurrenceCaptor = ArgumentCaptor.forClass(ScheduledOccurrence.class);
        verify(scheduledOccurrenceRepository).save(occurrenceCaptor.capture());
        assertEquals(ScheduledOccurrenceStatus.ENQUEUED, occurrenceCaptor.getValue().getStatus());
        assertEquals("exec-orig-1", occurrenceCaptor.getValue().getExecutionId());

        // Schedule is now advanced
        assertEquals(Instant.parse("2026-10-01T10:10:00Z"), config.getNextFireTime());
        assertEquals(scheduledTime, config.getLastScheduledFireTime());
    }

    @Test
    void processWorkflowSchedule_DuplicateScheduler_RecoversFailedOccurrenceWithoutCreatingSecondExecution() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-dup-recover", "user-1", "Dup Recover WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:10Z");

        // Phase 8.1.2: claim check requires matchedCount > 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        // Scheduler A failed Redis; occurrence is in DB as FAILED_RETRYABLE with executionId="exec-shared-1"
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey("wf-dup-recover", scheduledTime);
        ScheduledOccurrence existingOccurrence = new ScheduledOccurrence(
                occurrenceKey, "wf-dup-recover", scheduledTime, "exec-shared-1", ScheduledOccurrenceStatus.FAILED_RETRYABLE, now
        );

        // Scheduler B encounters duplicate key on insert
        doThrow(new DuplicateKeyException("Duplicate occurrence key"))
                .when(mongoTemplate).insert(any(ScheduledOccurrence.class));
        when(scheduledOccurrenceRepository.findById(occurrenceKey)).thenReturn(java.util.Optional.of(existingOccurrence));

        when(executionService.retryScheduledQueueSubmission("exec-shared-1", workflow, occurrenceKey))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-shared-1", "wf-dup-recover"));

        // Scheduler B recovers the existing occurrence
        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertTrue(result);
        // Scheduler B must NOT create a new execution
        verify(executionService, never()).enqueueScheduledExecution(any(), any(), any());
        // Scheduler B must retry the EXISTING execution ID
        verify(executionService).retryScheduledQueueSubmission("exec-shared-1", workflow, occurrenceKey);

        assertEquals(ScheduledOccurrenceStatus.ENQUEUED, existingOccurrence.getStatus());
    }

    // ==========================================
    // Phase 8.1.2 Hardening Tests — State Consistency
    // ==========================================

    @Test
    void processWorkflowSchedule_MatchedCountVsModifiedCount_IdenticalUpdatedAtDoesNotRejectClaim() {
        // Phase 8.1.2 FIX #1: Verify matchedCount is used instead of modifiedCount.
        // When updatedAt is already set to the same Instant (e.g., rapid successive evaluations),
        // modifiedCount would be 0 (no actual change) but matchedCount would be 1 (document exists).
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant scheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(scheduledTime);

        Workflow workflow = new Workflow("wf-match-1", "user-1", "MatchCount WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:05:01Z");

        // Simulate: document matched but updatedAt was already the same value — modifiedCount=0, matchedCount=1
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(1, 0L, null));

        when(executionService.enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime)))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-match-1", "wf-match-1"));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        // Must succeed because matchedCount=1 (document exists with expected state)
        assertTrue(result, "Claim must succeed when matchedCount=1 even if modifiedCount=0");
        verify(executionService).enqueueScheduledExecution(eq(workflow), anyString(), eq(scheduledTime));
    }

    @Test
    void processWorkflowSchedule_ConditionalInitialization_ConcurrentSchedulerAlreadyInitialized_Skips() {
        // Phase 8.1.2 FIX #2: If another scheduler instance already initialized nextFireTime,
        // the conditional init query should match 0 documents and skip.
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Workflow workflow = new Workflow("wf-init-race", "user-1", "Init Race WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:01:00Z");

        // Simulate: another scheduler already set nextFireTime — matchedCount=0 (nextFireTime is no longer null)
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        // In-memory config should NOT be updated when conditional init fails
        assertNull(config.getNextFireTime(), "In-memory nextFireTime must not be set when conditional init fails");
        verifyNoInteractions(executionService);
    }

    @Test
    void processWorkflowSchedule_ConditionalInitialization_UserChangedCronAfterRead_Skips() {
        // Phase 8.1.2 FIX #2: If user changed cron between scheduler read and init write,
        // the conditional query (matching old cron) should match 0 documents.
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Workflow workflow = new Workflow("wf-init-stale", "user-1", "Init Stale WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        Instant now = Instant.parse("2026-10-01T10:01:00Z");

        // User changed cron to something else — old cron doesn't match
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        assertNull(config.getNextFireTime(), "Must not initialize nextFireTime with stale cron");
        verifyNoInteractions(executionService);
    }

    @Test
    void processWorkflowSchedule_MisfireSkip_UserChangedScheduleDuringDowntime_DoesNotOverwrite() {
        // Phase 8.1.2 FIX #3: If the user changed the schedule while the backend was down,
        // the conditional misfire skip should not overwrite the user's new schedule.
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        Instant oldScheduledTime = Instant.parse("2026-10-01T10:05:00Z");
        config.setNextFireTime(oldScheduledTime);

        Workflow workflow = new Workflow("wf-misfire-stale", "user-1", "Misfire Stale WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        // Simulate: backend restarted after downtime
        scheduler.setStartupTime(Instant.parse("2026-10-01T10:06:00Z"));
        Instant now = Instant.parse("2026-10-01T10:06:05Z");

        // User changed schedule during downtime — conditional advance matches 0 docs
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Workflow.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        boolean result = scheduler.processWorkflowSchedule(workflow, now);

        assertFalse(result);
        // In-memory config must NOT be updated when conditional advance fails
        assertEquals(oldScheduledTime, config.getNextFireTime(),
                "In-memory nextFireTime must not change when conditional advance fails (user modified schedule)");
        verifyNoInteractions(executionService);
    }
}

