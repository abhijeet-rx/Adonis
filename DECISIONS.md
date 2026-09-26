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
* **Status**: Accepted
* **Context**: The full system roadmap includes a visual React Flow canvas, node execution engine, Redis workers, and AI integration. Attempting to build these prematurely risks over-engineering and architectural debt.
* **Decision**: Strictly limit Phase 2 to workflow CRUD data modeling and REST API contracts. Defer React Flow visual canvas to Phase 3, execution engine to Phase 4, execution history to Phase 5, and Redis task workers to Phase 7.
* **Consequences**:
  - Positive: Maintains clean architectural boundaries, keeps the test surface focused and reliable, and enables incremental delivery.
