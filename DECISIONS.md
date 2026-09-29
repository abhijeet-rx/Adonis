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

