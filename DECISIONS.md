# Architectural Decision Records (ADRs)

This document records the architectural and technical decisions made during the development of **Adonis**. Only decisions actually implemented during each phase are recorded here.

---

## Phase 0: Project Initialization

### ADR-001: Monorepo Repository Structure
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: The project consists of a modern web frontend and a Java backend service, alongside containerization manifests and automated CI pipelines.
* **Decision**: Adopt a single unified repository (monorepo) with isolated root directories: `/frontend`, `/backend`, `/docker`, and `/.github`.
* **Consequences**:
  - Positive: Simplifies atomic cross-stack versioning, unified CI/CD, and single-source-of-truth issue management.
  - Trade-off: Requires configuring `working-directory` path contexts in GitHub Actions and Docker build contexts.

---

### ADR-002: Backend Runtime — Java 21 LTS and Spring Boot 3.3
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Adonis requires high throughput, long-term runtime stability, strong type safety, and an ecosystem capable of handling complex DAG executions and integrations.
* **Decision**: Select Java 21 (LTS) with Spring Boot 3.3.4.
* **Consequences**:
  - Positive: Access to modern Java language features (records, virtual threads, pattern matching), seamless Spring ecosystem integration (Spring Security, Spring Data, Actuator), and production-grade reliability.
  - Trade-off: Slightly larger container image baseline than lightweight Go or Node alternatives, mitigated through multi-stage alpine JRE runner images.

---

### ADR-003: Frontend Tooling — Vite, React 19, TypeScript, and Tailwind CSS
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: The user interface will eventually host a complex node-based visual workflow editor (React Flow) demanding low render latency and type safety.
* **Decision**: Initialize the frontend using Vite with React 19 and TypeScript, styled with Tailwind CSS.
* **Consequences**:
  - Positive: Sub-second hot-module replacement (HMR), compile-time type verification, and utility-first styling avoiding CSS drift.
  - Trade-off: Requires Node 20+ runtime for local build steps.

---

### ADR-004: Inclusion of Maven Wrapper (`mvnw`)
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Different developer workstations and CI runners may have varying versions of Maven or no globally installed Maven CLI.
* **Decision**: Check in official Maven Wrapper scripts (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) configured to Maven 3.9.16.
* **Consequences**:
  - Positive: Guarantees reproducible, hermetic builds across all local environments and GitHub Actions without requiring pre-installed Maven.

---

### ADR-005: Deferred Database and Queue Containers in Docker Compose
* **Date**: 2026-09-26
* **Status**: Superseded by ADR-007 for MongoDB (Phase 1)
* **Context**: Phase 0 focused solely on project initialization and health diagnostic baseline without implementing persistence or queues.
* **Decision**: Kept initial `docker-compose.yml` focused strictly on `backend` and `frontend` services, documenting MongoDB (Phase 1) and Redis (Phase 7) as placeholders.
* **Consequences**:
  - Positive: Prevented phantom/unused container resource consumption during Phase 0.

---

### ADR-006: Java 21 Records for API Data Transfer Objects (DTOs)
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Need concise, immutable DTO representation for API responses (such as `HealthResponse`, `RegisterRequest`, `UserResponse`) without introducing heavy third-party boilerplate processors.
* **Decision**: Use standard Java 21 `record` declarations for API contracts.
* **Consequences**:
  - Positive: Zero boilerplate, built-in immutability, native serialization support with Jackson, and no external annotation processor dependencies.

---

## Phase 1: Authentication + MongoDB + User Management

### ADR-007: MongoDB & Spring Data MongoDB as Persistence Layer
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Phase 1 requires persistent identity and user management, which serves as the foundation for storing user-defined workflow DAGs and execution documents in subsequent phases.
* **Decision**: Adopt MongoDB 7.0 (`mongo:7.0` container) with `spring-boot-starter-data-mongodb`. No SQL/JPA/PostgreSQL is introduced. Connection URI is environment-driven via `MONGODB_URI` (`mongodb://localhost:27017/adonis` locally and `mongodb://mongodb:27017/adonis` in Docker Compose).
* **Consequences**:
  - Positive: Flexible document model directly aligned with workflow graph structures; seamless Spring Data repository abstraction.
  - Trade-off: Requires index management and careful document schema validation.

---

### ADR-008: Stateless JWT Authentication with Spring Security 6
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Need secure, scalable authentication between the frontend SPA and backend REST API without server-side HTTP session state.
* **Decision**: Implement stateless authentication (`SessionCreationPolicy.STATELESS`) using JJWT (`0.12.6`). `JwtAuthenticationFilter` validates `Bearer <token>` on each protected request (`/api/users/me`), setting a lightweight `UserPrincipal` into Spring Security's `SecurityContext` without executing redundant database queries.
* **Consequences**:
  - Positive: Horizontally scalable, zero server session memory footprint, standard Bearer authorization header protocol.
  - Trade-off: Access tokens cannot be revoked server-side before expiration without token denylists (deferred to future hardening).

---

### ADR-009: BCrypt Password Hashing and Credential Sanitization
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Passwords must never be stored in plaintext, logged, or returned across API boundaries.
* **Decision**: Hash passwords using Spring Security's `BCryptPasswordEncoder` before persistence. Verify passwords during login using `passwordEncoder.matches(...)`. DTOs (`UserResponse`, `AuthResponse`) strictly omit `passwordHash`.
* **Consequences**:
  - Positive: Cryptographically secure, salted, slow-hash resistance against rainbow tables and brute-force attacks.
  - Trade-off: Computational overhead per registration/login, which is acceptable and desired for credential protection.

---

### ADR-010: Database-Level Normalized Unique Email Index
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Two users must never register with the same email, even under concurrent race conditions. Email comparison must be case-insensitive.
* **Decision**: Enforce normalization (trimmed and lowercased) at the entity level (`User.create` / `setEmail`) and define a unique MongoDB index on `email` (`@Indexed(unique = true)`). Handlers catch `DuplicateKeyException` and map to HTTP 409 Conflict.
* **Consequences**:
  - Positive: Guarantees uniqueness at both the database engine level and application level.
  - Trade-off: Requires `auto-index-creation: true` or migration steps to ensure index presence.

---

### ADR-011: Deferral of Redis and Testcontainers
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Redis is needed for asynchronous workers (Phase 7). Testcontainers is targeted for automated integration testing (Phase 10).
* **Decision**: Defer Redis container activation to Phase 7. Defer Testcontainers to Phase 10. For Phase 1 repository-level tests, use pure Java in-memory MongoDB server (`mongo-java-server`) allowing fast, hermetic, Docker-free unit and repository testing without binary downloads.
* **Consequences**:
  - Positive: Keeps Phase 1 lightweight, eliminates extraneous runtime dependencies, and avoids Docker daemon requirements during unit test execution.

---

## Phase 2: Workflow CRUD

### ADR-012: Workflow Domain Model and Document Persistence in MongoDB
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Phase 2 requires persisting workflow definitions (nodes and edges) along with metadata (name, description, status, creation/update timestamps) for authenticated users.
* **Decision**: Represent workflows as MongoDB documents in a dedicated `workflows` collection. Store nodes (`WorkflowNode`: `id`, `type`, `data`) and edges (`WorkflowEdge`: `id`, `source`, `target`) directly as embedded documents within the workflow. Define lifecycle states via `WorkflowStatus` (`DRAFT`, `ACTIVE`).
* **Consequences**:
  - Positive: Natural alignment with JSON graph structures (future React Flow integration) and atomic document updates without complex relational joins.
  - Trade-off: Document size is bounded by MongoDB 16MB document limit, which is more than sufficient for workflow DAGs.

---

### ADR-013: User Ownership Enforcement and Query-Level Isolation
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Multi-tenant isolation is critical: users must only ever see, modify, or delete their own workflows. Workflow ownership must never be spoofable by client input.
* **Decision**: Derive `userId` exclusively from the authenticated JWT token identity (`UserPrincipal.id()`). Enforce query-level filtering in MongoDB queries (`findByUserId`, `findByIdAndUserId`). If a user requests or attempts to mutate a workflow belonging to another user, return HTTP `404 Not Found` rather than `403 Forbidden`.
* **Consequences**:
  - Positive: Guarantees zero cross-user access, avoids leaking the existence of other users' workflows, and eliminates in-memory post-filtering.
  - Trade-off: Clients must be properly authenticated via `Authorization: Bearer <token>` for all `/api/workflows` endpoints.

---

### ADR-014: Strict Deferral of Visual Canvas (React Flow) and Execution Engine
* **Date**: 2026-09-26
* **Status**: Accepted (React Flow visual canvas completed in Phase 3; execution engine deferred to Phase 4)
* **Context**: The full system roadmap includes a visual React Flow canvas, node execution engine, Redis workers, and AI integration. Attempting to build these prematurely risks over-engineering and architectural debt.
* **Decision**: Strictly limit Phase 2 to workflow CRUD data modeling and REST API contracts. Defer React Flow visual canvas to Phase 3, execution engine to Phase 4, execution history to Phase 5, and Redis task workers to Phase 7.
* **Consequences**:
  - Positive: Maintains clean architectural boundaries, keeps the test surface focused and reliable, and enables incremental delivery.

---

## Phase 3: React Flow Visual Workflow Builder

### ADR-015: React Flow Visual Canvas, Custom Nodes, and Graph Persistence
* **Date**: 2026-09-26
* **Status**: Accepted
* **Context**: Users need an interactive, visual canvas to construct, view, move, connect, and configure workflow steps without manual JSON crafting. The visual graph state (node coordinates, connections, custom node parameters) must seamlessly persist into MongoDB using existing Phase 2 workflow APIs.
* **Decision**:
  - Integrate `@xyflow/react` (React Flow v12, React 19 compatible) as the visual workflow graph editor.
  - Extend backend `WorkflowNode` with embedded `position` (`WorkflowNodePosition`: `x`, `y`) and `WorkflowEdge` with `sourceHandle` / `targetHandle` in a backwards-compatible manner.
  - Create dedicated custom node components (`TriggerNode`, `HttpRequestNode`, `GenericNode`) styled to match Adonis dark aesthetics, with connection handles for DAG construction.
  - Provide a `NodePalette` sidebar allowing click-to-add and HTML5 drag-and-drop onto the canvas.
  - Provide a `NodeConfigPanel` sidebar for updating selected node metadata (label, method, url, description, trigger type) in memory without executing actions.
  - Implement a clean bidirectional adapter (`workflowAdapter.ts`) separating React Flow canvas representation from backend MongoDB schema contracts.
  - Persist workflow graphs using the existing `PUT /api/workflows/{id}` endpoint without creating extraneous APIs.
  - Strictly maintain Phase 3 as a visual editor only: no execution engine, job queues, Redis, webhooks, or runner processes are introduced.
* **Consequences**:
  - Positive: Full visual editing experience with zoom, pan, minimap, background grid, and unsaved changes tracking. Clean separation between UI presentation state and persisted workflow documents.
  - Trade-off: Requires maintaining coordinates and handles in the document model. Execution of graph nodes remains planned for Phase 4.

---

## Phase 4: Workflow Execution Engine

### ADR-016: Synchronous In-Process Execution Engine, Kahn's Topological Sort, and Fail-Fast Strategy
* **Date**: 2026-09-27
* **Status**: Accepted
* **Context**: Phase 4 requires executing saved workflows consisting of nodes and edges. The engine must validate the workflow definition, determine the correct execution order, execute supported nodes (`trigger`, `httpRequest`, `generic`), pass outputs from upstream to downstream nodes, and return structured execution outcomes.
* **Decision**:
  - **In-Process & Synchronous Execution**: The execution engine runs synchronously in-process (`POST /api/workflows/{id}/execute`) using the authenticated user identity. No asynchronous queues, Redis, Kafka, or background executor threads are used.
  - **Graph Validation (7 Rules)**: Validate (1) non-null/non-empty workflow, (2) unique node IDs, (3) supported node types, (4) exactly one trigger node with 0 in-degree, (5) valid edge source and target node references, (6) acyclic graph topology, and (7) rejection of unknown node types.
  - **Topological Execution Ordering**: Use Kahn's algorithm starting from the root trigger node to establish deterministic linear execution order across linear and branching graphs.
  - **Node Executors**:
    - `TriggerNodeExecutor`: Produces initial execution context (`trigger: "manual"`, timestamps, metadata).
    - `HttpRequestNodeExecutor`: Executes real HTTP calls via Java 21's standard `java.net.http.HttpClient` with configurable 10s timeout, header support, and body publishers for GET, POST, PUT, DELETE, and PATCH. Differentiates transport failures (network/DNS/timeout/malformed URL) from completed HTTP responses with 4xx/5xx status codes.
    - `GenericNodeExecutor`: Safe pass-through node preserving inputs and metadata without arbitrary code execution.
  - **Data Flow Resolution**: Each node receives the output map of its upstream node(s). A single upstream node's output is passed directly; multiple upstream nodes are combined deterministically keyed by source node ID.
  - **Fail-Fast Strategy**: If any node fails (status `FAILED`), downstream node execution is halted immediately. The overall workflow execution status is marked `FAILED` with error details and duration.
  - **Ownership Enforcement**: Lookups use `findByIdAndUserId(workflowId, principal.id())`. Unauthorized or nonexistent executions return `404 Not Found`.
  - **Deferral of Execution Persistence & Redis**: Execution results remain in-memory and return directly in the API response. MongoDB execution history persistence is deferred to Phase 5. Redis asynchronous task queues are deferred to Phase 7.
* **Consequences**:
  - Positive: High simplicity, zero external queue dependencies, deterministic testability (with JDK local `HttpServer`), robust multi-tenant ownership enforcement (`findByIdAndUserId`).
  - Trade-off: Long-running or heavy workflows are constrained by HTTP request lifecycle until asynchronous workers (Phase 7) are introduced.

---

## Phase 5: Execution History & Logs

### ADR-017: Persistent Workflow Execution History, Granular Node Logs, and Secret Redaction
* **Date**: 2026-09-29
* **Status**: Accepted
* **Context**: In Phase 4, execution results were transient, returned immediately in the HTTP response (`POST /api/workflows/{id}/execute`) without persistent storage. Refreshing the browser or navigating away lost all execution context. Phase 5 requires persistent, queryable execution history, audit logs, node-level diagnostics, duration metrics, and visibility into which nodes succeeded, failed, or were skipped due to fail-fast execution.
* **Decision**:
  - **Dedicated Collection (`workflow_executions`)**: Store execution runs in a separate `workflow_executions` MongoDB collection rather than embedding arrays in the `Workflow` document. This avoids unbounded document growth in `workflows` and cleanly separates mutable graph definitions from immutable execution records.
  - **Execution Document Lifecycle**: An execution document is created in MongoDB with `RUNNING` status when execution begins. Upon engine completion, the document is updated with `SUCCESS` or `FAILED`, completion timestamp, duration in milliseconds, and the complete node-by-node execution trace.
  - **Fail-Fast & Skipped Node Accounting**: If a node fails, execution terminates immediately. The failed node records its error, and all subsequent unexecuted nodes in the planned topological order are persisted with `status: SKIPPED`, `durationMs: 0`, and empty inputs/outputs, making execution flow and skipping visible in the UI.
  - **Deep Secret Redaction (`SecretRedactor`)**: To prevent credential leakage into audit logs, input and output maps are recursively sanitized prior to persistence. Case-insensitive sensitive keys (e.g. `authorization`, `password`, `apiKey`, `token`, `secret`, `cookie`) and values matching Bearer or JWT formats are redacted to `[REDACTED]`.
  - **Strict Ownership Isolation**: Lookups use `findByIdAndUserId` and `findByWorkflowIdAndUserId`, extracting the authenticated principal from the verified JWT token. Unauthorized or nonexistent executions return `404 Not Found` without revealing document existence.
  - **Separation of List Summaries and Detail Payloads**: Listing endpoints (`GET /api/workflows/{id}/executions`, `GET /api/executions`) return lightweight summaries (`ExecutionSummaryResponse`) with pagination (`page`, `size`, `startedAt DESC`). Full node payloads are only loaded upon requesting single execution detail (`GET /api/executions/{id}`).
  - **Synchronous In-Process Execution Kept**: Execution remains synchronous and in-process. Redis and asynchronous worker queues remain deferred to Phase 7.
* **Consequences**:
  - Positive: Execution history survives browser reloads; developers can inspect historical node outputs and errors; credentials are protected; multi-tenant security is strictly maintained.
  - Trade-off: Storage requirements increase with execution frequency; retention/cleanup policies are deferred to future operational hardening phases.

---

## Phase 6: Retries & Failure Handling

### ADR-018: In-Process Node Retries, Exponential Backoff, Failure Classification, and Granular Attempt Tracking
* **Date**: 2026-09-29
* **Status**: Accepted
* **Context**: Workflows frequently encounter transient errors when integrating with external services (such as HTTP 503 Service Unavailable, 429 Rate Limits, network socket drops, and DNS glitches). Without retry policies, single transient faults abort the entire workflow run, requiring manual re-execution. Conversely, attempting retries on deterministic client errors (such as 400 Bad Request or 401 Unauthorized) wastes execution resources and inflates execution latency.
* **Decision**:
  - **Node-Level Retry Configuration (`RetryConfig`)**: Allow optional retry policies embedded in node configuration data (`enabled`, `maxRetries`, `initialBackoffMs`, `backoffMultiplier`, `maxBackoffMs`). Workflows lacking configuration default to `enabled: false, maxRetries: 0`.
  - **Retry Semantics (`maxRetries`)**: `maxRetries` strictly specifies the number of retries attempted *after* the initial failed attempt (i.e. `maxRetries = 2` yields up to 3 total attempts).
  - **Intelligent Failure Classification (`FailureClassifier`)**:
    - Retryable: HTTP 408, 429, 500, 502, 503, 504, connection timeouts, refused connections, and network drops.
    - Non-Retryable: HTTP 400, 401, 403, 404, malformed URL syntax, validation failures.
    - Non-retryable failures immediately abort retries and fail fast, preserving system resources.
  - **Exponential Backoff with Max Delay Clamping**:
    - Delay computed as `min(initialBackoffMs * (backoffMultiplier ^ (attempt - 1)), maxBackoffMs)`.
    - Enforce a pluggable delay strategy (`RetryDelayStrategy`) so production utilizes `Thread.sleep` synchronously, while unit/integration tests utilize non-blocking strategies (`noOp`) for millisecond-fast test suites.
  - **Granular Attempt Tracking (`NodeExecutionAttempt`)**:
    - Track each individual attempt with its `attemptNumber`, `status`, `startedAt`, `completedAt`, `durationMs`, `input`, `output`, and `error`.
    - Persist the attempt list in MongoDB under `NodeExecution.attempts` and expose via API response DTOs.
  - **Multi-Attempt Secret Redaction**:
    - Recursively redact secrets across every attempt input, output, and error message using `SecretRedactor` before persistence and API responses.
  - **Strict Synchronous Boundary**:
    - Execution remains synchronous and in-process within the HTTP request lifecycle. Redis, Kafka, and background asynchronous worker pools remain deferred to Phase 7.
* **Consequences**:
  - Positive: Workflows automatically recover from transient network and server issues; deterministic errors fail fast without wasteful delay; full observability into retry attempts and durations in the UI; clean backward compatibility with Phase 0–5 workflows.
  - Trade-off: Synchronous backoff delays hold the HTTP request thread during execution. Long-running retries are constrained until asynchronous job queues are implemented in Phase 7.

---

## Phase 6.1: Retry Hardening & Final Fixes

### ADR-019: HTTP Failure Semantics Decoupling, Maximum Backoff UI Exposure, and Default Synchronization
* **Date**: 2026-09-29
* **Status**: Accepted
* **Context**: In Phase 6, `HttpRequestNodeExecutor` conditioned HTTP failure reporting on whether retry was enabled, leading to an inconsistent state where HTTP status 500/503/404 would be reported as `NodeExecutionStatus.SUCCESS` if retries were disabled or not configured. Additionally, the frontend hardcoded `maxBackoffMs` to 30000ms rather than exposing it for user configuration, and frontend `maxRetries` defaulted to 3 while backend defaulted to 0 (`enabled: false`).
* **Decision**:
  - **Decoupled HTTP Failure Reporting**: `HttpRequestNodeExecutor` consistently reports any non-success HTTP status (`statusCode < 200 || statusCode >= 400`) as `NodeExecutionResult.failure(...)` with full status code, status text, and response body preserved in `output`. The executor no longer inspects retry configuration to determine failure status.
  - **Single Source of Truth for Retryability**: `FailureClassifier` alone determines whether the failed execution is retryable (408, 429, 5xx, transport errors) or non-retryable (400, 401, 403, 404, invalid configuration).
  - **Independent Retry Policy**: `RetryPolicy` alone determines whether additional attempts occur based on `retryConfig.enabled()`, `maxRetries`, and `failureClassifier.isRetryable(attemptResult)`.
  - **Frontend Maximum Backoff Exposure**: Added `Maximum Backoff` numeric input to `NodeConfigPanel.tsx` (clamped 0..60000ms, step 1000, default 30000ms) with bidirectional persistence into node data.
  - **Frontend and Backend Default Synchronization**: Synchronized frontend defaults to match backend defaults (`enabled: false`, `maxRetries: 0`, `initialBackoffMs: 1000`, `backoffMultiplier: 2.0`, `maxBackoffMs: 30000`). Existing workflows lacking retry configuration default safely without migrations.
* **Consequences**:
  - Positive: Node execution status and HTTP response status are never contradictory. Clean separation of concerns between HTTP execution, failure classification, and retry loop. Full user control over maximum backoff delay in visual editor. Zero regression on backward compatibility and security redaction.
  - Trade-off: Workflows with non-success HTTP endpoints that previously completed with node status `SUCCESS` will now correctly trigger fail-fast unless retry succeeds or custom error handling is configured.

---

## Phase 7: Redis Asynchronous Workers

### ADR-020: Redis Asynchronous Workers, Queue Abstraction, Atomic Idempotency, and Non-Blocking 202 Execution
* **Date**: 2026-09-29
* **Status**: Accepted
* **Context**: Prior to Phase 7, workflow execution was strictly synchronous and in-process within the HTTP request thread. Clients triggering `POST /api/workflows/{id}/execute` remained blocked until the entire workflow graph finished executing (including topological sorting, HTTP requests, and Phase 6 exponential backoff retries). This led to HTTP timeouts on complex or retried workflows, vulnerability to client disconnects, and thread pool exhaustion under concurrent executions.
* **Decision**:
  - **Selection of Redis 7 as the Queue Broker**: Adopt Redis 7 (`redis:7-alpine`) with Spring Data Redis (`StringRedisTemplate`). Redis was selected because it provides blazing-fast in-memory list primitives (`RPUSH`, `BLPOP` / `leftPop`) with minimal operational overhead, lightweight resource footprint, atomic operations, and seamless Docker Compose orchestration. Complex enterprise message brokers (such as Apache Kafka, RabbitMQ, or Kubernetes event operators) were intentionally avoided to prevent premature infrastructure complexity.
  - **Preservation of the WorkflowExecutionEngine Core**: The existing `WorkflowExecutionEngine` is treated as an immutable core. It was NOT rewritten or duplicated. The engine already encapsulates DAG graph validation, Kahn's topological sort, sequential execution, upstream data resolution, fail-fast mechanics, Phase 6 retry policies, failure classification, exponential backoff, attempt tracking, and Phase 5.1 secret redaction. The asynchronous worker merely acts as the invoker and orchestrator between the Redis queue, authoritative MongoDB data, and the execution engine.
  - **Minimalist Queue Payloads (`ExecutionJob`)**: Rather than serializing entire workflow documents and node configurations into the Redis queue, `ExecutionJob` contains only essential identifiers (`executionId`, `workflowId`, `userId`, `triggerType`, `queuedAt`). The worker resolves authoritative workflow definitions and execution state directly from MongoDB at execution time. This prevents document stale-reads, eliminates serialization version skew, keeps Redis memory overhead negligible, and guarantees that credentials or sensitive workflow definitions are never serialized into transit queues.
  - **Strict Separation of Queue Responsibilities vs. Node Retries**: Redis is strictly responsible for message delivery and job dispatching. Redis does NOT perform node retries or workflow step recovery. Node-level retries remain strictly within the domain of Phase 6 `RetryPolicy` and `FailureClassifier` inside the execution engine, where topological dependencies and step backoffs are understood.
  - **Queue Abstraction (`ExecutionQueue`)**: To prevent tight coupling to Redis specifics, an `ExecutionQueue` interface was introduced. `RedisExecutionQueue` implements list-based queueing (`adonis:execution:queue`) using Spring Data Redis for production, while `InMemoryExecutionQueue` provides an in-memory queue fallback that enables running unit, integration, and CI test suites without requiring an external Redis daemon on `localhost:6379`.
  - **Queue Delivery Semantics & Atomic State Idempotency**: Redis provides at-least-once message delivery, while MongoDB atomic execution claiming provides idempotent workflow execution and prevents duplicate execution across workers.
    1. Executions are initially persisted in MongoDB with status `QUEUED`.
    2. Before running a job, the worker queries MongoDB atomically via `mongoTemplate.findAndModify` with `Criteria.where("_id").is(executionId).and("status").is(ExecutionStatus.QUEUED)` and updates `status = RUNNING, startedAt = now()`.
    3. Only one worker succeeds in transitioning the state. If the query returns `null` (because another worker claimed it or it was already processed), the duplicate job is safely discarded without executing node operations.
  - **Fail-Safe Queue Submission**: If MongoDB creates the `QUEUED` execution record successfully but Redis enqueuing subsequently fails (e.g. Redis network outage), the system catches the exception, immediately transitions the execution record to `FAILED` with a sanitized message, and returns HTTP 500 without leaking Redis connection strings, preventing executions permanently stuck in `QUEUED`.
  - **Graceful Lifecycle Management**: `ExecutionWorker` implements Spring's `SmartLifecycle`. On application termination or container SIGTERM, the worker ceases polling, allows active executions to finish, and interrupts threads cleanly without corrupting execution state.
  - **Non-Blocking API Contract (`202 Accepted`)**: `POST /api/workflows/{id}/execute` authenticates the user, verifies workflow ownership, validates graph structure, creates the `QUEUED` record, enqueues the job, and immediately returns `202 Accepted` with `{ "executionId": "...", "workflowId": "...", "status": "QUEUED" }`.
  - **Frontend Controlled Polling**: The frontend displays immediate `QUEUED` status upon receiving the 202 response, triggers history refreshes, and polls `/api/executions/{id}` at controlled 1.5s intervals. Once terminal status (`SUCCESS` or `FAILED`) is detected, polling terminates.
* **Consequences**:
  - Positive: REST API execution requests respond in sub-50 milliseconds regardless of workflow duration or retries; zero HTTP timeout risk; multiple worker instances can consume concurrently from Redis without duplicate execution; existing Phase 4 execution engine, Phase 5 history, and Phase 6 retries remain 100% intact and verified; test suites remain deterministic and hermetic without live Redis dependencies.
  - Trade-off: Asynchronous execution requires client polling to inspect live execution state (WebSockets/SSE deferred to future phases). Additional infrastructure container (Redis 7) required for production orchestration.

---

## Phase 7.1: Redis Worker Reliability Hardening

### ADR-021: Redis Streams with Consumer Groups, Message Acknowledgement (XACK), Pending Message Recovery, and Stale Execution Reconciliation
* **Date**: 2026-09-29
* **Status**: Accepted
* **Context**:
  In Phase 7, the queue used basic Redis list primitives (`RPUSH` and `BLPOP` / `leftPop`). The key limitation was destructive dequeue: once a worker popped a job from the list via `BLPOP`, the message was permanently removed from Redis. If the worker process abruptly crashed or was killed before completing and persisting execution state, the job disappeared from Redis while MongoDB remained stranded in `RUNNING` status with no mechanism to detect or recover it.
* **Decision**:
  - **Transition to Redis Streams with Consumer Groups**:
    Replace Redis lists with Redis Streams (`adonis:execution:stream`) and a consumer group (`adonis-workers`). Producers publish via `XADD`. Workers consume non-destructively via `XREADGROUP` (`ReadOffset.lastConsumed()`). Delivered messages remain tracked in Redis in the group's Pending Entries List (PEL) until explicitly acknowledged with `XACK`.
  - **Strict Acknowledgement Timing (`XACK`)**:
    Workers invoke `XACK` **only after** the execution record has been successfully persisted to MongoDB in a terminal state (`SUCCESS` or `FAILED`). If MongoDB persistence throws an exception or network error, the message is NOT acknowledged, remaining in the PEL for subsequent recovery.
  - **Worker Crash Recovery via Pending Entries List (PEL)**:
    Healthy workers periodically inspect pending unacknowledged entries (`XPENDING`) for the consumer group. If an entry's idle time exceeds `WORKER_PENDING_CLAIM_IDLE_MS` (default 60,000ms), indicating the assigning worker crashed, workers reclaim ownership via `XCLAIM` and reprocess the job.
  - **MongoDB Atomic Execution Claiming & Idempotency**:
    Redis provides at-least-once message delivery, while MongoDB atomic execution claiming provides idempotent workflow execution and prevents duplicate execution across workers. When redelivered:
    1. If MongoDB execution record is missing (orphaned job): safe warning is logged and the message is acknowledged to clear the queue.
    2. If status is already `SUCCESS` or `FAILED`: the duplicate message is safely acknowledged without re-running any nodes.
    3. If status is `RUNNING`: the worker inspects elapsed execution time (`startedAt`). If elapsed time exceeds `WORKER_STALE_EXECUTION_TIMEOUT_MS` (default 300,000ms), the execution is transitioned to `FAILED` with an explicit diagnostic message and acknowledged. If not yet stale, the worker skips re-execution to prevent duplicate external HTTP side effects.
    4. If status is `QUEUED`: the worker atomically transitions `QUEUED -> RUNNING` via `mongoTemplate.findAndModify`.
  - **Safe Malformed Message Quarantine**:
    If a stream entry contains invalid JSON or cannot be deserialized, the worker quarantines the record to a dedicated dead-letter stream (`adonis:execution:stream:dlq`) and acknowledges the main stream entry. This prevents poison pills from causing infinite crash/redelivery loops while preserving payloads for offline diagnosis without logging secrets.
  - **Graceful Shutdown**:
    On application stop, the worker thread is interrupted and allowed to finish in-flight processing. If the process terminates before `XACK`, the message remains safely in the PEL and will be recovered by remaining or restarted workers.
  - **Infrastructure Simplicity**:
    Redis Streams native consumer groups provide the required at-least-once delivery, unacknowledged message tracking, and crash recovery without introducing heavyweight external brokers such as Apache Kafka or RabbitMQ.
* **Consequences**:
  - Positive: Zero risk of silent job loss under worker crashes; at-least-once delivery guarantee combined with MongoDB atomic idempotency prevents duplicate HTTP side effects; corrupted messages are quarantined without crashing the worker; deterministic test suite remains completely hermetic via `InMemoryExecutionQueue`.
  - Trade-off: Recovered `RUNNING` executions that exceed the stale timeout are marked `FAILED` rather than automatically re-executed, prioritizing idempotency and preventing duplicate external HTTP requests over speculative replay.

---

## Phase 7.1.1: Worker Lease & Stale Execution Hardening

### ADR-021.1: Renewable Worker Ownership Leases, Background Heartbeats, and Safe Expired Lease Recovery
* **Date**: 2026-09-29
* **Status**: Accepted
* **Context**:
  In Phase 7.1, stale execution reconciliation relied on total elapsed execution time (`now - startedAt > WORKER_STALE_EXECUTION_TIMEOUT_MS`). Any legitimate workflow running longer than the configured timeout (e.g. slow external HTTP queries, batch operations, or long-running graphs) was prematurely marked `FAILED` by another worker inspecting pending messages, even when the original worker was completely healthy and actively executing the workflow.
* **Decision**:
  - **Rejection of Static Execution Timeouts**:
    Total execution duration must NEVER determine whether an execution is abandoned or stale. A workflow running for 5 minutes, 20 minutes, or 2 hours is considered active as long as the worker responsible for it continuously renews its ownership lease.
  - **Execution Ownership Lease Metadata**:
    Extend `WorkflowExecution` with internal persistence-only fields:
    - `workerId`: Unique runtime identity of the worker currently executing the workflow.
    - `leaseUntil`: Expiration timestamp of the current worker's ownership lease.
    - `lastHeartbeatAt`: Timestamp of the most recent successful heartbeat renewal.
    These fields remain strictly internal to the persistence layer and are never leaked to frontend API responses (`ExecutionResponse`, `ExecutionSummaryResponse`).
  - **Unique Worker Identity**:
    Workers derive their identity from `REDIS_CONSUMER_NAME` / `adonis.worker.consumer-name`. If not explicitly configured, the worker automatically generates a unique runtime identity (`worker-<UUID>`) on startup, preventing identity collisions across concurrent application instances.
  - **Atomic Lease Acquisition during QUEUED → RUNNING**:
    During initial job claiming, `mongoTemplate.findAndModify` atomically transitions `QUEUED` to `RUNNING` while establishing the initial lease:
    `WHERE _id == executionId AND status == QUEUED` → `SET status = RUNNING, startedAt = now(), workerId = currentWorkerId, leaseUntil = now() + leaseDurationMs, lastHeartbeatAt = now()`.
  - **Lightweight Non-Blocking Heartbeat Renewal**:
    While executing a workflow, a lightweight `ScheduledExecutorService` periodically renews the worker's lease every `WORKER_HEARTBEAT_INTERVAL_MS` (default 20,000ms, clamped to `leaseDuration / 3` if misconfigured). The heartbeat executes asynchronously without blocking workflow execution threads.
  - **Ownership-Safe Lease Renewal**:
    Lease renewal updates MongoDB conditionally:
    `WHERE _id == executionId AND status == RUNNING AND workerId == currentWorkerId` → `SET leaseUntil = now() + leaseDurationMs, lastHeartbeatAt = now()`.
    If the update matches 0 documents (`matchedCount == 0`), the worker has lost ownership (e.g., due to an extreme GC pause allowing another worker to acquire the lease). The worker immediately stops its heartbeat and flags ownership as lost, aborting subsequent terminal persistence and message acknowledgement to avoid overwriting state.
    Transient MongoDB network errors during heartbeat are logged as warnings and retried on the next tick without immediately aborting the workflow.
  - **Safe Expired Lease Recovery & Duplicate Side-Effect Prevention**:
    When a worker claims or reclaims a message targeting an execution currently in `RUNNING` status:
    - If `leaseUntil > now`: The lease is valid. The existing worker is actively executing. The message is skipped without re-execution, without failure marking, and without acknowledgement.
    - If `leaseUntil <= now`: The lease has expired (the previous worker crashed or hung). Workers attempt an atomic takeover via `findAndModify`:
      `WHERE _id == executionId AND status == RUNNING AND (leaseUntil <= now OR leaseUntil == null)` → `SET workerId = currentWorkerId, leaseUntil = now() + leaseDurationMs, lastHeartbeatAt = now()`.
      Only the single winning worker that successfully acquires the expired lease transitions the execution to `FAILED` with an explicit diagnostic recovery message (`Execution lease expired (previous worker [%s] lost ownership or terminated); marked FAILED during recovery to prevent duplicate external side effects`), persists the record, and acknowledges (`XACK`) the message.
  - **Tradeoff & Side-Effect Limitation**:
    A long-running workflow is not considered stale based on total execution duration. Worker ownership is determined using a renewable lease.
    The system does not guarantee exactly-once external side effects. A worker crash after an external side effect (e.g. HTTP POST to a third-party payment gateway or webhook) but before durable completion state can require replay or terminal failure depending on the recovery policy. Adonis chooses terminal failure with diagnostic logging to avoid duplicating non-idempotent external HTTP requests.
* **Consequences**:
  - Positive: Legitimate long-running workflows can run indefinitely as long as worker heartbeats succeed; worker crashes are reliably detected when leases expire; race conditions between multiple workers during takeover are prevented via atomic MongoDB operations; heartbeat threads are bounded and fully cleaned up upon execution completion or shutdown.
  - Trade-off: Workflows interrupted by worker crash are marked `FAILED` rather than automatically resumed mid-graph, preserving strict idempotency against duplicate external side effects.

---

## Phase 8: Scheduling + Webhooks

### ADR-022: Scheduling and Webhook Triggers
* **Date**: 2026-09-30
* **Status**: Accepted
* **Context**:
  Workflows in Adonis previously required manual invocation via authenticated API requests (`POST /api/workflows/{id}/execute`). Production automation platforms require automated triggers: time-based recurrence (cron schedules) and inbound HTTP notifications (webhooks). A critical architectural constraint is that triggers must NOT introduce a second execution pipeline or bypass the Phase 7 asynchronous worker architecture.
* **Decision**:
  - **Asynchronous Trigger Producer Model**:
    Neither the cron scheduler (`AdonisScheduler`) nor the webhook controller (`WebhookController`) directly invokes `WorkflowExecutionEngine`. Both components act strictly as trigger producers that create a persistent `WorkflowExecution` record in `QUEUED` status and publish an `ExecutionJob` to Redis Streams (`ExecutionQueue`). The existing `ExecutionWorker` pool claims jobs, enforces worker leases, executes nodes, and handles retries.
  - **Why the Scheduler and Webhook Controller Must Not Directly Execute Workflows**:
    Direct synchronous execution would bypass Redis Streams queuing, worker concurrency controls, distributed lease heartbeats, stale execution recovery, and Phase 6 failure classification/retries. Enqueuing via `WorkflowExecutionService` preserves a single, unified execution path across all trigger types.
  - **Spring-Compatible Cron Evaluation**:
    Scheduled workflows define cron expressions parsed using Spring's `CronExpression`. Standard 5-field (minute, hour, day, month, weekday) and 6-field (second, minute, hour, day, month, weekday) expressions are supported. Workflows are only evaluated if their status is `ACTIVE`.
  - **Timezone Support**:
    Scheduled workflows support arbitrary IANA timezone identifiers (e.g. `Asia/Kolkata`, `America/New_York`, `UTC`), validated using `java.time.ZoneId`. If unconfigured, the scheduler defaults safely to `UTC`. Invalid timezone strings are rejected during workflow validation and isolated so they never crash the scheduler.
  - **Downtime Misfire Policy (`DO_NOT_CATCH_UP`)**:
    If the backend is down during a scheduled window, missed historical occurrences are NOT replayed upon application restart. Replaying hundreds of missed runs can trigger execution storms, exhaust database connections, and duplicate external HTTP requests. The scheduler skips missed executions and schedules from the next valid occurrence after the current timestamp.
  - **Durable Multi-Instance Duplicate Protection**:
    Multiple backend instances may run the scheduler concurrently. To prevent duplicate scheduled executions without distributed locks or static JVM synchronizations, Adonis employs a dedicated MongoDB collection (`scheduled_occurrences`) with a compound unique index on `(workflowId, scheduledFireTime)`. Before enqueuing, the scheduler atomically attempts an insert. If a duplicate key error (`DuplicateKeyException`) occurs, another instance has already claimed that occurrence, and the duplicate is silently discarded.
  - **Fault Isolation per Workflow**:
    The centralized scheduler evaluates active workflows iteratively inside a try/catch block. A syntax error, invalid timezone, or transient failure in one workflow logs a structured warning and continues to evaluate remaining workflows.
  - **Scheduler Enable/Disable Configuration**:
    Controlled via `adonis.scheduler.enabled: true` (environment variable `SCHEDULER_ENABLED`). Allows disabling scheduling in worker-only nodes, integration tests, or local environments.
  - **Webhook Capability URLs**:
    Webhook endpoints use opaque, cryptographically secure 64-character hex capability identifiers (`/api/webhooks/{webhookPath}`) generated via `SecureRandom`. Internal MongoDB workflow IDs are never used as public trigger paths, preventing enumeration attacks.
  - **Constant-Time Secret Authentication**:
    Optional webhook secrets sent via `X-Webhook-Secret` are verified using constant-time comparison (`MessageDigest.isEqual`) on SHA-256 digests, eliminating side-channel timing attacks. Plaintext secrets are never stored in MongoDB (only SHA-256 digests are persisted), never logged, and never returned in API responses.
  - **Bounded Payloads & Deep Redaction**:
    Webhook requests are bounded by `adonis.webhook.max-body-size-bytes` (default 1MB, returning HTTP `413 Payload Too Large`). Incoming headers, query parameters, and JSON payloads are sanitized via `SecretRedactor` to strip credentials, cookies, and bearer tokens before saving into `WorkflowExecution.triggerPayload`.
  - **Webhook Idempotency Key**:
    Webhooks support an optional `Idempotency-Key` header. If provided, duplicate delivery requests within the same workflow return the existing execution ID without enqueuing a duplicate job, backed by a MongoDB partial unique index on `(workflowId, idempotencyKey)`.
  - **HTTP 202 Accepted Response**:
    Webhook endpoints return HTTP `202 Accepted` with `{ "executionId": "...", "status": "QUEUED" }` immediately upon enqueuing. Clients receive fast acknowledgment and do not block waiting for execution completion.
  - **TriggerContext Propagation**:
    The execution engine receives a structured `TriggerContext` (type: `MANUAL`, `SCHEDULE`, `WEBHOOK`, payload, metadata) via `ExecutionContext`, enabling trigger nodes to pass trigger payloads downstream without knowing about HTTP controllers or scheduler internals.
* **Consequences**:
  - Positive: Consistent execution semantics across all trigger types; durable duplicate protection across multiple backend instances; resilient downtime recovery; zero leakage of sensitive secrets; immune to timing attacks; unifies manual, scheduled, and webhook flows in execution history and visual builder.
  - Trade-off: Missed scheduled runs during downtime are discarded rather than backfilled, requiring manual execution if historical batch processing is desired.

---

## ADR-023: Phase 8.1 — Trigger Reliability & Security Hardening

* **Status**: Accepted
* **Date**: 2026-09-30
* **Context**:
  Phase 8 introduced scheduling and webhook triggers into the existing asynchronous worker pipeline. Subsequent reliability audits identified several critical edge cases:
  1. *Webhook Path Collisions*: Webhook capability paths lacked a database-level uniqueness guarantee, allowing potential duplicate path resolution bugs.
  2. *Stale Trigger Type Invocations*: Webhooks resolved workflows by path without checking that `workflow.triggerType == WEBHOOK`, enabling stale webhooks to execute workflows converted to MANUAL or SCHEDULE.
  3. *Stale Configuration Leaks*: Switching trigger types left residual configuration (e.g. cron expressions remaining on webhook workflows or secrets on manual workflows).
  4. *Schedule Modification Desynchronization*: Changing cron expressions or timezones did not invalidate stored `nextFireTime`, executing workflows at obsolete schedule intervals.
  5. *Misfire Policy Ambiguity*: The scheduler configuration had a legacy misfire threshold that could trigger bounded catch-up rather than the documented `DO_NOT_CATCH_UP` policy.
  6. *Idempotency Failure Locking*: Transient queue failures during initial webhook ingestion marked executions `FAILED`, permanently stranding the `Idempotency-Key` and preventing retry.
  7. *Unbounded Headers*: `Idempotency-Key` lacked a length limit, posing storage and memory abuse vectors.
  8. *Orphan Scheduled Occurrences*: Deleting a workflow left uncleaned `ScheduledOccurrence` documents in MongoDB.
  9. *Webhook Capability Entropy & Path Validation*: Webhook capability path generation required 256 bits of entropy (64 hex characters) and strict rejection of path traversals, delimiters, and reserved words.
  10. *Scheduler Save Race*: The scheduler persisted `nextFireTime` using `workflowRepository.save(workflow)`, creating race conditions that could overwrite concurrent user edits to nodes, edges, or metadata.
* **Decision**:
  - **MongoDB Partial Unique Index**:
    Created `wf_webhook_path_idx` on `triggerConfig.webhookPath` with partial filter `{'triggerConfig.webhookPath': {$type: 'string'}}`. Application catches `DuplicateKeyException` and returns a clean 409 Conflict without leaking database internals.
  - **Strict Trigger-Type Enforcement**:
    `WebhookController` enforces `workflow.getTriggerType() == WorkflowTriggerType.WEBHOOK`. Any non-matching or draft workflow returns HTTP `404 Not Found` without disclosing workflow metadata.
  - **Trigger Configuration Lifecycle Rules**:
    `WorkflowService` cleans obsolete fields on trigger type transitions: MANUAL cleans all trigger specifics; SCHEDULE cleans webhook parameters; WEBHOOK cleans cron and timezone parameters.
  - **Schedule Invalidation**:
    Modifying `cronExpression` or `timezone` resets `nextFireTime` and `lastScheduledFireTime` to `null`, forcing the scheduler to compute a fresh occurrence from the updated schedule.
  - **Pure `DO_NOT_CATCH_UP` Misfire Policy**:
    Eliminated all catch-up thresholds. Occurrences scheduled prior to application startup are discarded; the scheduler advances directly to the next valid occurrence after `now()`, preventing execution storms after downtime.
  - **Idempotency Queue Recovery**:
    If Redis queueing fails initially, subsequent webhook deliveries with the same `Idempotency-Key` safely re-attempt Redis submission via `markRequeued()` rather than returning a permanently failed execution or creating duplicate executions.
  - **Bounded Idempotency-Key**:
    `Idempotency-Key` is strictly capped at 256 characters; oversized keys are rejected with HTTP 400.
  - **Cascade Occurrence Cleanup**:
    Deleting a workflow cascade-deletes all associated `ScheduledOccurrence` records from MongoDB.
  - **64-Character Hex Capability Paths & Strict Validation**:
    Generated paths use 32 bytes of cryptographic randomness (64 hex characters, 256 bits of entropy). Custom and generated paths are validated against `^[a-zA-Z0-9_-]{8,128}$`, rejecting path traversals, slashes, whitespace, and reserved URI prefixes.
  - **Race-Free Targeted Scheduler Updates**:
    The scheduler uses atomic `MongoTemplate.updateFirst` queries to update only `triggerConfig.nextFireTime` and `triggerConfig.lastScheduledFireTime`, guaranteeing that background scheduler ticks never overwrite user modifications to workflow canvas structures or properties.
* **Consequences**:
  - Positive: Guarantees strict trigger consistency and database integrity; eliminates race conditions between users and the scheduler; prevents stale trigger executions; ensures reliable idempotency recovery across infrastructure hiccups; hardens webhook paths against enumeration and injection attacks.
  - Trade-off: Workflows with misconfigured paths or oversized idempotency keys are strictly rejected with 4xx errors; downtime occurrences are discarded without backfill.

---

## ADR-024: Phase 8.1.1 — Scheduler Concurrency & Queue Failure Hardening

* **Status**: Accepted
* **Date**: 2026-09-30
* **Context**:
  A post-Phase 8.1 audit identified two critical scheduler reliability vulnerabilities and one trigger concurrency edge case:
  1. *Stale Scheduler Decision Race*: The scheduler evaluates an in-memory workflow snapshot, determines a due occurrence, and proceeds to claim and enqueue. If a user modifies the workflow's cron expression, timezone, trigger type, or status between the scheduler's read and claim steps, the scheduler could enqueue an execution based on obsolete schedule parameters.
  2. *Redis Enqueue Failure Permanently Losing Occurrences*: If Redis was temporarily unavailable when the scheduler attempted to enqueue an execution, the `ScheduledOccurrence` record was already inserted into MongoDB. Subsequent scheduler ticks detected the existing record as a duplicate and skipped it, causing the scheduled occurrence to be permanently lost rather than retried.
  3. *Webhook Idempotency Concurrency Race*: Concurrent client retry requests for a webhook execution that previously failed queue submission could simultaneously attempt re-queueing, causing duplicate jobs to be submitted to Redis Streams.
* **Decision**:
  - **Atomic Conditional Scheduler Claim**:
    Before inserting an occurrence or creating an execution, the scheduler executes an atomic conditional update query against MongoDB:
    ```java
    Query scheduleQuery = Query.query(
        Criteria.where("_id").is(workflow.getId())
            .and("status").is(WorkflowStatus.ACTIVE)
            .and("triggerType").is(WorkflowTriggerType.SCHEDULE)
            .and("triggerConfig.cronExpression").is(config.getCronExpression())
            .and("triggerConfig.timezone").is(config.getTimezone())
            .and("triggerConfig.nextFireTime").is(nextDue)
    );
    ```
    If `claimCheck.getModifiedCount() == 0`, the workflow was modified or deactivated after the scheduler's read step. The scheduler immediately abandons the iteration without creating stale executions, saving occurrences, or modifying fire times.
  - **Explicit Scheduled Occurrence Lifecycle**:
    Added `ScheduledOccurrenceStatus` (`CLAIMED`, `ENQUEUED`, `FAILED_RETRYABLE`). Occurrences are uniquely keyed by `(workflowId, scheduledFireTime)` via MongoDB compound unique index. At most one logical `WorkflowExecution` is created per occurrence.
  - **Deferred Schedule Advancement & Redis Failure Tolerance**:
    The workflow's `nextFireTime` is never advanced when queue submission fails. On transient Redis failure, the occurrence is marked `FAILED_RETRYABLE` with the created `executionId` and error details. Subsequent scheduler ticks or recovery sweeps detect the recoverable occurrence and retry queue submission for the existing `executionId` (`retryScheduledQueueSubmission`), avoiding duplicate `WorkflowExecution` documents. Only upon successful queue dispatch is the occurrence marked `ENQUEUED` and `nextFireTime` advanced.
  - **Atomic Webhook Idempotency Transition**:
    `WorkflowExecutionService.retryWebhookQueueSubmission` performs an atomic conditional `mongoTemplate.updateFirst` query (`status == FAILED`, `startedAt == null`) transitioning the execution to `QUEUED`. Only the first concurrent request modifies the record and enqueues to Redis; concurrent requests observe `modifiedCount == 0` and return the existing execution state without duplicate queue submissions.
  - **Precise Delivery & Execution Semantics**:
    Adonis enforces **at-least-once delivery** across Redis Streams and trigger producers, combined with **idempotent execution claiming** (`findAndModify: QUEUED → RUNNING`) and renewable worker ownership leases (`leaseUntil`, `lastHeartbeatAt`). Adonis explicitly does not claim exactly-once execution or exactly-once external side effects.
* **Consequences**:
  - Positive: Eliminates stale schedule races; guarantees scheduled occurrences survive transient Redis outages without data loss or duplication; guarantees single execution creation per occurrence; eliminates duplicate enqueuing on concurrent webhook retries; maintains strict separation between user-owned workflow canvas fields and scheduler-owned fire times.
  - Trade-off: Non-retryable application errors during execution creation are not masked as retryable queue failures; scheduler claims require an additional lightweight atomic touch query before reserving occurrences.

---

## ADR-025: Phase 8.1.2 — Scheduler State Consistency Hardening

* **Status**: Accepted
* **Date**: 2026-09-30
* **Context**:
  A final audit of the Phase 8.1.1 scheduler hardening identified four state consistency issues that could cause silent data corruption under concurrent scheduler instances or user-concurrent schedule modifications:
  1. *`modifiedCount` vs `matchedCount` in Conditional Verification*: The scheduler's stale-snapshot protection used `modifiedCount == 0` to detect whether the schedule was still current. However, `modifiedCount` returns 0 when the update query matches a document but the `$set` values are identical to the existing document (e.g., `updatedAt` happens to be the same). In such cases, `matchedCount` correctly returns 1, confirming the document with the expected schedule state exists. Using `modifiedCount` caused false-negative claim rejections under rapid successive evaluations.
  2. *Unconditional Schedule Initialization*: When `nextFireTime` was null (first initialization), the scheduler computed the next occurrence and wrote it to MongoDB unconditionally. If a user changed the cron expression or timezone between the scheduler's read and the initialization write, the stale-computed `nextFireTime` would overwrite the user's fresh schedule. Similarly, two concurrent scheduler instances could race on initialization.
  3. *Unconditional Misfire Skip Advancement*: Under the `DO_NOT_CATCH_UP` policy, the scheduler skipped missed occurrences and advanced `nextFireTime`. This write was unconditional. If the user modified the schedule during backend downtime, the post-restart misfire skip could overwrite the user's new schedule with a stale-computed advancement.
  4. *Unchecked Recovery Fire Time Advancement*: When recovering `FAILED_RETRYABLE` occurrences from a previous Redis outage, the scheduler advanced `nextFireTime` unconditionally after successful recovery. If the schedule had changed since the occurrence was originally created, this advancement could overwrite the user's current schedule state with fire times computed from an obsolete cron expression.
* **Decision**:
  - **`matchedCount` for Conditional Existence Checks (Fix #1)**:
    Changed `claimCheck.getModifiedCount() == 0` to `claimCheck.getMatchedCount() == 0` and inverted the null-check polarity to `claimCheck == null || matchedCount == 0`. This ensures the scheduler correctly detects document existence regardless of whether the `updatedAt` field actually changed.
  - **Conditional Atomic Schedule Initialization (Fix #2)**:
    Replaced the unconditional `updateNextFireTime(...)` during first initialization with `conditionalInitNextFireTime(...)`, which includes `triggerConfig.nextFireTime: null`, `triggerConfig.cronExpression: expectedCron`, `triggerConfig.timezone: expectedTimezone`, `status: ACTIVE`, and `triggerType: SCHEDULE` in the query criteria. The in-memory `config.setNextFireTime(...)` is only applied if `matchedCount > 0`, preventing stale initialization.
  - **Conditional Misfire Skip Advancement (Fix #3)**:
    Replaced the unconditional `updateNextFireTime(...)` during DO_NOT_CATCH_UP skip with `conditionalAdvanceNextFireTime(...)`, which matches `triggerConfig.nextFireTime: expectedStaleValue` along with `cronExpression` and `timezone` criteria. The in-memory config update is only applied if `matchedCount > 0`, ensuring stale misfire skips never overwrite user-modified schedules.
  - **Schedule-Aware Recovery Fire Time Advancement (Fix #4)**:
    Modified `recoverAndEnqueueOccurrence(...)` and `recoverOrphanFailedOccurrences(...)` to check whether the occurrence's `scheduledFireTime` is still relevant to the current schedule before advancing fire times. If `nextFireTime` has already advanced past the occurrence, the execution is still recovered (since the `WorkflowExecution` was already created), but fire times are NOT advanced. Fire time writes use `conditionalAdvanceFireTimes(...)` with a `triggerConfig.nextFireTime: expectedValue` guard.
* **Consequences**:
  - Positive: Eliminates all identified stale-snapshot write paths in the scheduler; conditional updates are idempotent and safe under concurrent scheduler instances; user schedule modifications are never overwritten by stale scheduler computations; existing execution recovery continues to work for orphaned `FAILED_RETRYABLE` occurrences even after schedule changes.
  - Trade-off: When a conditional update fails (because the schedule changed concurrently), the scheduler silently skips the operation and logs the decision. The next scheduler tick will pick up the fresh state from MongoDB and proceed correctly.


