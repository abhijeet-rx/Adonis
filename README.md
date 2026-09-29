# Adonis

> Developer-focused workflow automation platform inspired by lightweight versions of n8n & Zapier.

Adonis enables developers to design, schedule, and execute automated event-driven workflows with a visual node-based editor, resilient Java/Spring Boot execution engine, asynchronous job processing, and AI integrations.

---

## Current Development Phase

**Phase 7 — Redis Asynchronous Workers** *(Completed)*

This phase introduces an asynchronous, distributed-capable workflow execution architecture powered by Redis 7 and Spring Data Redis. The REST API execution endpoint (`POST /api/workflows/{id}/execute`) no longer blocks the HTTP request thread while running workflow graphs. Instead, it validates workflow graph structure upfront, generates an authoritative execution record in MongoDB with status `QUEUED`, enqueues a lightweight `ExecutionJob` message (`executionId`, `workflowId`, `userId`, `triggerType`, `queuedAt`) to the Redis queue (`RPUSH`), and immediately returns `202 Accepted` to the caller. Background worker processes (`ExecutionWorker`) poll jobs from the queue (`BLPOP` / `leftPop`), atomically claim the job from `QUEUED` to `RUNNING` via MongoDB `findAndModify` to enforce idempotency and prevent duplicate executions across concurrent workers, invoke the existing, unchanged `WorkflowExecutionEngine` (preserving all Phase 6 retry policies, failure classification, exponential backoff, attempt tracking, and Phase 5.1 secret redaction), and persist final `SUCCESS` or `FAILED` outcomes with granular node logs to MongoDB. If Redis enqueuing fails, the execution record transitions safely to `FAILED` with sanitized messaging to prevent permanently stuck `QUEUED` records. The frontend receives `202 Accepted`, displays immediate `QUEUED` status in modal and history views, and utilizes controlled polling (every 1.5s) until terminal execution state (`SUCCESS` or `FAILED`).

---

## Current Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 21 LTS, Spring Boot 3.3.4, Maven, Spring Web, Spring Data MongoDB, Spring Data Redis, Spring Security 6, JJWT 0.12, BCrypt |
| **Frontend** | React 19, TypeScript, Vite, Tailwind CSS, @xyflow/react, Lucide Icons |
| **Database** | MongoDB 7.0 (Docker container `adonis-mongodb` on port 27017, collections: `users`, `workflows`, `workflow_executions`) |
| **Queue & Cache** | Redis 7 Alpine (Docker container `adonis-redis` on port 6379, persistent appendonly storage `redis_data`) |
| **Containerization** | Docker, Docker Compose (Multi-stage builds) |
| **Testing** | JUnit 5, Spring Boot Test, Spring Security Test, Mockito, MockMvc, pure-Java in-memory MongoServer, in-memory queue fallback |
| **CI/CD** | GitHub Actions |

---

## API Endpoints

| Method | Endpoint | Access | Description |
|---|---|---|---|
| `GET` | `/api/health` | Public | System and service health diagnostic |
| `POST` | `/api/auth/register` | Public | Register new user account with hashed password and return JWT |
| `POST` | `/api/auth/login` | Public | Authenticate user credentials and return JWT |
| `GET` | `/api/users/me` | Protected (`Bearer <token>`) | Retrieve authenticated user profile |
| `POST` | `/api/workflows` | Protected (`Bearer <token>`) | Create a new workflow for the authenticated user |
| `GET` | `/api/workflows` | Protected (`Bearer <token>`) | List all workflows owned by the authenticated user |
| `GET` | `/api/workflows/{id}` | Protected (`Bearer <token>`) | Retrieve specific workflow (returns 404 if not owned or nonexistent) |
| `PUT` | `/api/workflows/{id}` | Protected (`Bearer <token>`) | Update workflow fields (name, description, status, nodes, edges) |
| `DELETE` | `/api/workflows/{id}` | Protected (`Bearer <token>`) | Delete workflow by ID (returns 204 No Content) |
| `POST` | `/api/workflows/{id}/execute` | Protected (`Bearer <token>`) | Asynchronously enqueue workflow execution (returns `202 Accepted` with `QUEUED` status) |
| `GET` | `/api/workflows/{id}/executions` | Protected (`Bearer <token>`) | Paginated execution history summary for specific workflow (`?page=0&size=20`) |
| `GET` | `/api/executions/{executionId}` | Protected (`Bearer <token>`) | Retrieve full node-by-node execution record (`QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`) |
| `GET` | `/api/executions` | Protected (`Bearer <token>`) | Paginated global execution history for authenticated user (`?page=0&size=20&status=SUCCESS`) |
| `GET` | `/actuator/health` | Public | Spring Boot Actuator health diagnostic |

> **Workflow & Execution Ownership Isolation**:
> All workflows and execution records are strictly scoped to the authenticated user derived from the validated JWT token (`UserPrincipal.id()`). Lookups, updates, and listings enforce ownership at query time (`findByIdAndUserId`), ensuring users can never inspect or modify another user's execution history. Cross-tenant or nonexistent resource requests return `404 Not Found` without leaking record existence. Sensitive tokens (passwords, Authorization headers, API keys) are redacted to `[REDACTED]` prior to persistence.

---

## Project Structure

```
adonis/
├── .github/
│   └── workflows/
│       └── ci.yml               # Automated CI pipeline for backend & frontend
├── backend/
│   ├── .mvn/                    # Maven wrapper assets
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/adonis/
│   │   │   │   ├── AdonisApplication.java   # Spring Boot entry point
│   │   │   │   ├── config/                  # CORS and web configuration
│   │   │   │   ├── controller/              # REST controllers (GET /api/health)
│   │   │   │   ├── dto/                     # Data transfer objects
│   │   │   │   ├── exception/               # Global exception handlers (reserved)
│   │   │   │   ├── model/                   # Domain entities (reserved)
│   │   │   │   ├── repository/              # Data access layer (reserved)
│   │   │   │   ├── security/                # Authentication & JWT (reserved)
│   │   │   │   └── service/                 # Business logic services (reserved)
│   │   │   └── resources/
│   │   │       └── application.yml          # Backend configuration
│   │   └── test/
│   │       └── java/com/adonis/             # Context & health controller unit tests
│   ├── mvnw / mvnw.cmd          # Maven wrapper scripts
│   └── pom.xml                  # Maven project descriptor
├── docker/
│   ├── backend/
│   │   └── Dockerfile           # Multi-stage Java 21 build
│   ├── frontend/
│   │   └── Dockerfile           # Multi-stage Node 20 + Nginx build
│   └── README.md
├── frontend/
│   ├── src/
│   │   ├── App.tsx              # Landing / dashboard placeholder with live diagnostics
│   │   ├── index.css            # Tailwind baseline & design tokens
│   │   └── main.tsx             # React DOM root
│   ├── package.json
│   ├── tailwind.config.js
│   └── vite.config.ts
├── ARCHITECTURE.md              # System design and component interaction flow
├── DECISIONS.md                 # Architectural Decision Records (ADRs)
├── docker-compose.yml           # Local multi-service orchestration
└── README.md                    # Project documentation
```

---

## Getting Started

### Prerequisites

- **Java**: JDK 21+
- **Node.js**: Node 20+ and npm 10+
- **Docker**: Docker Engine & Docker Compose (optional for local non-containerized dev)

---

### Starting the Backend

From the repository root:

```bash
cd backend

# On Linux/macOS:
./mvnw clean test
./mvnw spring-boot:run

# On Windows (PowerShell/CMD):
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

- Backend server URL: `http://localhost:8080`
- Health check endpoint: `http://localhost:8080/api/health`
- Spring Actuator health: `http://localhost:8080/actuator/health`

---

### Starting the Frontend

From the repository root in a separate terminal:

```bash
cd frontend
npm install
npm run dev
```

- Frontend dev server URL: `http://localhost:5173`

---

### Running via Docker Compose

Both MongoDB 7 and Redis 7 are orchestrated via Docker Compose:

```bash
# Start MongoDB and Redis background services:
docker compose up -d mongodb redis

# Or build and launch the entire stack (MongoDB, Redis, Backend, Frontend):
docker compose up --build -d
```

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`
- MongoDB: `localhost:27017`
- Redis: `localhost:6379`

### Environment Variables for Redis & Asynchronous Worker

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis server hostname (`redis` in Docker Compose) |
| `REDIS_PORT` | `6379` | Redis server TCP port |
| `WORKER_ENABLED` | `true` | Toggle execution worker polling loop (set `false` in tests) |
| `EXECUTION_QUEUE_NAME` | `adonis:execution:queue` | Redis list queue key name for execution jobs |
| `WORKER_POLL_TIMEOUT_MS` | `2000` | Redis blocking poll timeout (`BLPOP` / `leftPop`) in milliseconds |
| `QUEUE_TYPE` | `redis` | Queue backend provider (`redis` for production, `in-memory` for tests) |

---

## Current Status vs. Planned Milestones

- **Current (Phase 0 through Phase 7 — Operational)**:
  - Clean monorepo layout (`backend`, `frontend`, `docker`, `.github/workflows`)
  - Java 21 LTS + Spring Boot 3.3.4 foundation with `/api/health` diagnostic endpoint
  - MongoDB 7.0 persistence (`users`, `workflows`, and `workflow_executions` collections)
  - Redis 7.0 persistence queue (`adonis:execution:queue` list with `RPUSH` / `BLPOP`)
  - Spring Security 6 stateless authentication with BCrypt password hashing
  - JJWT 0.12 Bearer token generation, verification, and protected endpoints (`GET /api/users/me`, `/api/workflows/**`, `/api/executions/**`)
  - Workflow CRUD REST API (`POST`, `GET`, `GET {id}`, `PUT {id}`, `DELETE {id}`) with ownership-level query isolation
  - React Flow visual workflow builder (`@xyflow/react`) with custom nodes (Trigger, HTTP Request, Generic), handles, zoom/pan/minimap, node palette, configuration drawer, and dirty state management
  - Asynchronous, non-blocking workflow execution (`POST /api/workflows/{id}/execute` returns `202 Accepted` immediately with status `QUEUED`)
  - Fail-safe queue submission: gracefully transitions execution record to `FAILED` with sanitized messaging if Redis enqueuing fails, preventing permanently stuck `QUEUED` records
  - Queue abstraction: `ExecutionQueue` interface with `RedisExecutionQueue` (production) and `InMemoryExecutionQueue` (test isolation)
  - Autonomous `ExecutionWorker` process implementing Spring's `SmartLifecycle` for graceful shutdown
  - Idempotent execution claims via atomic MongoDB `findAndModify` (`QUEUED` → `RUNNING`), guaranteeing exactly-once execution per job across concurrent worker instances
  - Workflow execution engine: deterministic topological sort, fail-fast behavior, data flow propagation, and structured node execution outcomes
  - Node executors: `TriggerNodeExecutor` (manual execution context), `HttpRequestNodeExecutor` (real HTTP requests via standard Java `HttpClient` for GET/POST/PUT/DELETE/PATCH), and `GenericNodeExecutor` (safe pass-through)
  - Persistent workflow execution records (`workflow_executions`) tracking status (`QUEUED` → `RUNNING` → `SUCCESS`/`FAILED`), timestamps, duration, and granular node executions
  - Fail-fast skipped node persistence (downstream nodes marked `SKIPPED`)
  - Node-level retry policies (`RetryConfig`: `enabled`, `maxRetries`, `initialBackoffMs`, `backoffMultiplier`, `maxBackoffMs`) with safe defaults and exponential backoff
  - Intelligent failure classification (`FailureClassifier`) distinguishing retryable errors (408, 429, 500, 502, 503, 504, connection timeouts, refused connections) from non-retryable errors (400, 401, 403, 404, invalid URLs)
  - Granular attempt tracking (`NodeExecutionAttempt`: attemptNumber, status, timestamps, duration, input, output, error) with retry count recorded on `NodeExecution`
  - Deep secret redaction (`SecretRedactor`) for sensitive headers, API keys, bearer tokens, and credentials across all attempts, results, and persistence
  - Pluggable backoff delay strategy (`RetryDelayStrategy`: production thread sleep, non-blocking test stub)
  - Paginated execution history endpoints (`GET /api/workflows/{id}/executions`, `GET /api/executions`) and detailed execution inspector (`GET /api/executions/{id}`)
  - Execution history panel with pagination and enhanced execution results modal inspecting node inputs, outputs, errors, skipped steps, and attempt histories
  - Controlled frontend execution polling (every 1.5s) until terminal execution state (`SUCCESS` or `FAILED`)
  - Multi-stage Docker configurations and Docker Compose with `backend`, `frontend`, `mongodb`, and `redis`
  - Automated GitHub Actions CI pipeline (backend test & frontend build)

- **Planned Functionality (Phases 8–12)**:
  - Scheduling & webhooks (Planned for Phase 8)
  - AI nodes powered by Gemini/OpenAI (Planned for Phase 9)
  - Automated testing & Testcontainers (Planned for Phase 10)
  - Production Docker & deployment (Planned for Phase 11)
  - CI/CD & production hardening (Planned for Phase 12)

---

## Roadmap

- [x] **Phase 0 — Project Initialization**
- [x] **Phase 1 — Authentication + MongoDB + User Management**
- [x] **Phase 2 — Workflow CRUD**
- [x] **Phase 3 — React Flow Visual Workflow Builder**
- [x] **Phase 4 — Workflow Execution Engine**
- [x] **Phase 5 — Execution History + Logs**
- [x] **Phase 6 — Retries + Failure Handling**
- [x] **Phase 7 — Redis Asynchronous Workers**
- [ ] **Phase 8 — Scheduling + Webhooks**
- [ ] **Phase 9 — AI Nodes**
- [ ] **Phase 10 — Automated Testing + Testcontainers**
- [ ] **Phase 11 — Docker + Deployment**
- [ ] **Phase 12 — GitHub Actions CI/CD + Production Hardening**
