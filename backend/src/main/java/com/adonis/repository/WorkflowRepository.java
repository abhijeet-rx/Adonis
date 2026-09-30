package com.adonis.repository;

import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends MongoRepository<Workflow, String> {

    List<Workflow> findByUserId(String userId);

    Optional<Workflow> findByIdAndUserId(String id, String userId);

    long deleteByIdAndUserId(String id, String userId);

    boolean existsByIdAndUserId(String id, String userId);

    List<Workflow> findByStatusAndTriggerType(WorkflowStatus status, WorkflowTriggerType triggerType);

    Optional<Workflow> findByTriggerConfigWebhookPath(String webhookPath);

    boolean existsByTriggerConfigWebhookPath(String webhookPath);
}
