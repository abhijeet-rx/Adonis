# Adonis Architecture Overview

This document describes the high-level architecture, design principles, and component interactions for the **Adonis** workflow automation platform.

---

## 1. System Vision

Adonis is designed as an event-driven, developer-centric workflow orchestration platform. It balances visual simplicity on the frontend with robust, resilient backend execution using Java 21 and Spring Boot.

### Core Architectural Principles
- **Separation of Concerns**: Visual modeling (Frontend) is decoupled from workflow compilation, validation, and execution (Backend).
- **Extensible Node Ecosystem**: Nodes (triggers, actions, transformers, AI nodes) follow a uniform interface allowing plug-and-play expansions.
- **Fail-Safe & Idempotent**: Step-level retries, execution state journaling, and isolated asynchronous execution.
- **Developer First**: Fully inspectable execution traces, structured logs, and webhook triggers.

---

## 2. Current Architecture (Phase 4 Operational)

In Phase 4, the operational system topology provides an interactive visual workflow canvas integrated with the persistent workflow domain and a synchronous, in-process workflow execution engine:

```text
React (Vite + TypeScript + Tailwind + @xyflow/react)
   ├── Visual Workflow Builder (Canvas, MiniMap, Controls, Background)
   ├── Node Palette (Trigger, HTTP Request, Generic) & Node Config Drawer
   ├── Run Workflow Action & Execution Results Modal
   └── Bidirectional Graph Adapter (workflowAdapter.ts)
   ↓ HTTP / JSON (Bearer JWT, CORS-enabled)
Spring Boot REST API (Java 21, Spring Boot 3.3.4)
   ↓
Spring Security + JWT (Stateless filter, BCrypt password encoder)
   ↓
Service Layer (AuthService, UserService, WorkflowService, WorkflowExecutionService)
   ↓
Execution Engine (Kahn's Topological Sort, Fail-Fast In-Process Sequential Runner)
   ├── WorkflowExecutionValidator (7-rule graph & trigger validation)
   ├── WorkflowExecutionEngine (sequential execution & upstream output resolution)
   └── NodeExecutors: TriggerNodeExecutor, HttpRequestNodeExecutor, GenericNodeExecutor
   ↓
MongoDB (Spring Data MongoDB, 7.0 container)
   ├── Collection: users (unique index on lowercase email)
   └── Collection: workflows (nodes with positions & edges with handles, indexed by userId)
```

### Component Status (Implemented vs. Deferred)

| Subsystem / Component | Current Status | Milestone |
|---|---|---|
| **Core Monorepo & Build Pipeline** | **Operational** | Phase 0 (Completed) |
| **Spring Boot 3.3 REST Baseline** | **Operational** (`GET /api/health`) | Phase 0 (Completed) |
| **React + TypeScript UI Shell** | **Operational** (Landing & Diagnostics) | Phase 0 (Completed) |
| **MongoDB Persistence** | **Operational** (Documents `User`, `Workflow`) | Phase 1, 2 & 3 (Completed) |
| **Authentication & User Management** | **Operational** (Stateless JWT + BCrypt) | Phase 1 (Completed) |
| **Protected User Profile API** | **Operational** (`GET /api/users/me`) | Phase 1 (Completed) |
| **Workflow CRUD APIs** | **Operational** (`POST/GET/PUT/DELETE /api/workflows`) | Phase 2 (Completed) |
| **React Flow Visual Canvas** | **Operational** (`@xyflow/react` v12 visual builder) | Phase 3 (Completed) |
| **Workflow Execution Engine** | **Operational** (Topological DAG, in-process, fail-fast) | Phase 4 (Completed) |
| **Execution History & Logs** | *NOT Implemented* | Phase 5 (Execution History + Logs) |
| **Retries & Failure Handling** | *NOT Implemented* | Phase 6 (Retries + Failure Handling) |
| **Redis Asynchronous Workers** | *NOT Implemented* | Phase 7 (Redis Asynchronous Workers) |
| **Scheduling & Webhooks** | *NOT Implemented* | Phase 8 (Scheduling + Webhooks) |
| **AI Intelligent Nodes** | *NOT Implemented* | Phase 9 (AI Nodes) |
| **Automated Testing & Testcontainers** | *NOT Implemented* | Phase 10 (Testcontainers deferred to Phase 10) |
| **Production Docker Deployment** | *NOT Implemented* | Phase 11 (Docker + Deployment) |
| **CI/CD & Production Hardening** | *NOT Implemented* | Phase 12 (Production Hardening) |

> **Explicit Boundary & Design Principles**:
> - **In-Process & Synchronous**: In Phase 4, the execution engine runs synchronously in-process upon request. No asynchronous workers, job queues, Redis, Kafka, or background executor threads are used.
> - **Fail-Fast Error Handling**: If any node fails during execution, execution immediately terminates. The failed node is recorded with its error, downstream nodes are skipped, and the overall execution status is set to `FAILED`.
> - **Transient Execution Results**: Execution results are returned directly in the HTTP response (`POST /api/workflows/{id}/execute`) without being permanently persisted into MongoDB. Permanent audit history and execution logs are deferred to Phase 5.
> - **Redis Deferral**: Redis and queue workers are intentionally deferred to Phase 7 to maintain a minimal, resilient architecture before introducing distributed asynchronous complexity.

---

## 3. Workflow Domain & Ownership Isolation

### Workflow Document Structure
Each workflow document in MongoDB (`workflows` collection) represents a persistent graph definition:
- `id`: Unique identifier (auto-generated MongoDB ObjectID string).
- `userId`: Owner ID, strictly set from the verified `UserPrincipal.id()` in JWT.
- `name`: Workflow title (1–100 characters, required).
- `description`: Optional overview (up to 500 characters).
- `status`: Lifecycle state (`DRAFT`, `ACTIVE`).
- `nodes`: List of `WorkflowNode` objects (`id`, `type`, `data`).
- `edges`: List of `WorkflowEdge` objects (`id`, `source`, `target`).
- `createdAt` / `updatedAt`: Server-managed timestamps.

### Ownership Enforcement & Security Guarantees
1. **No Client-Controlled Ownership**: The client cannot specify or mutate `userId`.
2. **Database-Level Isolation**:
   - `findByUserId(userId)` ensures a user's listing only scans and returns their own workflows.
   - `findByIdAndUserId(id, userId)` scopes retrieval, mutation, and deletion to the owner at query time.
3. **No Cross-Tenant Existence Leaks**: Attempting to query, update, or delete a workflow belonging to another user returns `404 Not Found` (never 403), entirely hiding whether the workflow ID exists.

---

## 4. High-Level Topology (Operational vs Planned)

```mermaid
graph TD
    subgraph Client["Client Tier (Operational)"]
        UI["React 19 + TypeScript SPA<br/>(Auth, Workflow CRUD, React Flow & Execution Inspector)"]
    end

    subgraph Gateway["API & Ingress Tier (Operational)"]
        API["Spring Boot 3.3 REST API<br/>(/api/health, /api/auth/*, /api/users/me, /api/workflows/*)"]
        AUTH["Spring Security & JWT Filter<br/>(Stateless Bearer token validation)"]
    end

    subgraph ServiceLayer["Service & Business Logic (Operational)"]
        AUTH_SVC["AuthService (Register, Login, BCrypt)"]
        USER_SVC["UserService (Profile retrieval)"]
        WF_SVC["WorkflowService (CRUD & Ownership Scoping)"]
        EXEC_SVC["WorkflowExecutionService (Graph Validation & Execution)"]
        ENGINE["WorkflowExecutionEngine (Topological Sequential Runner)"]
        VALIDATOR["WorkflowExecutionValidator (7-Rule DAG Validation)"]
    end

    subgraph Storage["Data Tier (Operational)"]
        MONGO[("MongoDB 7.0<br/>(Collections: users, workflows)")]
    end

    subgraph Deferred["Deferred Subsystems (NOT Implemented)"]
        HISTORY[("Execution History Persistence (Planned Phase 5)")]
        REDIS[("Redis Task Queue & Workers (Planned Phase 7)")]
        AI["AI Provider Integrations (Planned Phase 9)"]
    end

    UI -->|HTTP / JSON| API
    API --> AUTH
    AUTH --> AUTH_SVC
    AUTH --> USER_SVC
    AUTH --> WF_SVC
    AUTH --> EXEC_SVC
    EXEC_SVC --> VALIDATOR
    EXEC_SVC --> ENGINE
    AUTH_SVC --> MONGO
    USER_SVC --> MONGO
    WF_SVC --> MONGO
    EXEC_SVC --> MONGO
```

---

## 5. Current Request Flows

```
[Browser / React App] 
      │
      ├── POST /api/auth/register          ──> Validates input, hashes password (BCrypt), persists User to MongoDB, returns JWT
      ├── POST /api/auth/login             ──> Verifies credentials with BCrypt, returns JWT
      ├── GET  /api/users/me               ──> Authenticated via Bearer JWT, extracts UserPrincipal, returns UserResponse
      ├── POST /api/workflows              ──> Authenticated via JWT, binds userId = principal.id(), persists Workflow
      ├── GET  /api/workflows              ──> Authenticated via JWT, queries findByUserId(principal.id())
      ├── GET  /api/workflows/{id}         ──> Authenticated via JWT, queries findByIdAndUserId, returns 404 on cross-user
      ├── PUT  /api/workflows/{id}         ──> Authenticated via JWT, updates mutable fields, refreshes updatedAt, returns 404 on cross-user
      ├── DELETE /api/workflows/{id}       ──> Authenticated via JWT, deletes own workflow, returns 204 (404 on cross-user)
      ├── POST /api/workflows/{id}/execute ──> Authenticated via JWT, verifies ownership, validates DAG (7 rules), topologically executes nodes, returns WorkflowExecutionResult
      └── GET  /api/health                 ──> Public health diagnostic (Phase 0)
```

---

## 6. Package Architecture (Backend)

```
backend/src/main/java/com/adonis/
├── AdonisApplication.java       # Application Bootstrap
├── config/                      # Web MVC, CORS, and HttpClient configuration
├── controller/                  # REST Controllers (HealthController, AuthController, UserController, WorkflowController)
├── dto/                         # Strongly-typed Java 21 Records (CreateWorkflowRequest, UpdateWorkflowRequest, WorkflowResponse, etc.)
├── exception/                   # Global exception handling (GlobalExceptionHandler, WorkflowValidationException, etc.)
├── execution/                   # Phase 4 Workflow Execution Engine
│   ├── ExecutionContext.java            # Runtime thread-safe execution state
│   ├── ExecutionStatus.java             # SUCCESS, FAILED status enum
│   ├── GenericNodeExecutor.java         # Pass-through generic node executor
│   ├── HttpRequestNodeExecutor.java     # JDK HttpClient executor for GET/POST/PUT/DELETE/PATCH
│   ├── NodeExecutionResult.java         # Node execution output, duration, and error record
│   ├── NodeExecutor.java                # Extensible node executor interface
│   ├── TriggerNodeExecutor.java         # Starting trigger node executor
│   ├── WorkflowExecutionEngine.java     # In-process sequential runner with fail-fast semantics
│   ├── WorkflowExecutionResult.java     # Overall workflow execution outcome record
│   ├── WorkflowExecutionService.java    # Ownership lookup and orchestration service
│   └── WorkflowExecutionValidator.java  # 7-rule graph validation & Kahn's topological sort
├── model/                       # MongoDB Document Models (User, Workflow, WorkflowNode, WorkflowEdge, WorkflowStatus)
├── repository/                  # Spring Data MongoDB Repositories (UserRepository, WorkflowRepository)
├── security/                    # SecurityConfig, JwtService, JwtAuthenticationFilter, UserPrincipal
└── service/                     # Business Logic (AuthService, UserService, WorkflowService)
```

---

## 7. Port Allocations & Networking

| Service | Internal Port | Host / Exposed Port | Protocol | Status | Purpose |
|---|---|---|---|---|---|
| `frontend` | 80 (prod) / 5173 (dev) | 5173 | HTTP | **Operational** | User Interface & Workflow CRUD Dashboard |
| `backend` | 8080 | 8080 | HTTP | **Operational** | REST API & Security Engine |
| `mongodb` | 27017 | 27017 | TCP | **Operational** | MongoDB 7.0 User & Workflow Persistence |
| `redis` | 6379 | 6379 | TCP | *Deferred (Phase 7)* | Async job queue & worker tasks |

