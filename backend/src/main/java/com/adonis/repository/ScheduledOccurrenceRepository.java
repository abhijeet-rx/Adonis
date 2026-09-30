package com.adonis.repository;

import com.adonis.model.ScheduledOccurrence;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ScheduledOccurrenceRepository extends MongoRepository<ScheduledOccurrence, String> {

    Optional<ScheduledOccurrence> findByWorkflowIdAndScheduledFireTime(String workflowId, Instant scheduledFireTime);

    boolean existsByWorkflowIdAndScheduledFireTime(String workflowId, Instant scheduledFireTime);

    java.util.List<ScheduledOccurrence> findByWorkflowIdAndStatus(String workflowId, com.adonis.model.ScheduledOccurrenceStatus status);

    java.util.List<ScheduledOccurrence> findByStatus(com.adonis.model.ScheduledOccurrenceStatus status);

    long deleteByWorkflowId(String workflowId);
}
