package com.adonis.repository;

import com.adonis.model.Workflow;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends MongoRepository<Workflow, String> {

    List<Workflow> findByUserId(String userId);

    Optional<Workflow> findByIdAndUserId(String id, String userId);

    void deleteByIdAndUserId(String id, String userId);

    boolean existsByIdAndUserId(String id, String userId);
}
