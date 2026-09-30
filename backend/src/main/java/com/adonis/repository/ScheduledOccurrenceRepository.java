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

    long deleteByWorkflowId(String workflowId);
}
