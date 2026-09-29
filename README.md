# Adonis

> Developer-focused workflow automation platform inspired by lightweight versions of n8n & Zapier.

Adonis enables developers to design, schedule, and execute automated event-driven workflows with a visual node-based editor, resilient Java/Spring Boot execution engine, asynchronous job processing, and AI integrations.

---

## Current Development Phase

**Phase 6 — Retries & Failure Handling** *(Completed)*

This phase introduces node-level retry policies, intelligent failure classification, exponential backoff, and granular attempt tracking to the Adonis workflow execution engine. Developers can configure retry behavior per node (`enabled`, `maxRetries`, `initialBackoffMs`, `backoffMultiplier`, `maxBackoffMs`). Failures are classified into retryable (HTTP 408, 429, 500, 502, 503, 504, connection timeouts, network drops) and non-retryable (HTTP 400, 401, 403, 404, validation errors), failing fast on deterministic client errors while recovering from transient infrastructure faults. Every execution attempt is tracked (`NodeExecutionAttempt`) with its duration, status, and error details, sanitized via `SecretRedactor`, persisted in MongoDB (`NodeExecution.attempts`), and visualized in the UI with retry attempt badges and expandable execution traces. Execution remains strictly synchronous and in-process, with asynchronous queue workers deferred to Phase 7.

---

## Current Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 21 LTS, Spring Boot 3.3.4, Maven, Spring Web, Spring Data MongoDB, Spring Security 6, JJWT 0.12, BCrypt |
| **Frontend** | React 19, TypeScript, Vite, Tailwind CSS, @xyflow/react, Lucide Icons |
| **Database** | MongoDB 7.0 (Docker container `adonis-mongodb` on port 27017, collections: `users`, `workflows`, `workflow_executions`) |
| **Containerization** | Docker, Docker Compose (Multi-stage builds) |
| **Testing** | JUnit 5, Spring Boot Test, Spring Security Test, Mockito, MockMvc, pure-Java in-memory MongoServer |
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
| `POST` | `/api/workflows/{id}/execute` | Protected (`Bearer <token>`) | Synchronously validate, execute, and persist workflow execution |
| `GET` | `/api/workflows/{id}/executions` | Protected (`Bearer <token>`) | Paginated execution history summary for specific workflow (`?page=0&size=20`) |
| `GET` | `/api/executions/{executionId}` | Protected (`Bearer <token>`) | Retrieve full node-by-node execution record (404 if not owned) |
| `GET` | `/api/executions` | Protected (`Bearer <token>`) | Paginated global execution history for authenticated user (`?page=0&size=20&status=SUCCESS`) |
| `GET` | `/actuator/health` | Public | Spring Boot Actuator health metric |

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

```bash
docker compose up --build
```

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`

## Current Status vs. Planned Milestones

- **Current (Phase 0, Phase 1, Phase 2, Phase 3, Phase 4, Phase 5 & Phase 6 — Operational)**:
  - Clean monorepo layout (`backend`, `frontend`, `docker`, `.github/workflows`)
  - Java 21 LTS + Spring Boot 3.3.4 foundation with `/api/health` diagnostic endpoint
  - MongoDB 7.0 persistence (`users`, `workflows`, and `workflow_executions` collections)
  - Spring Security 6 stateless authentication with BCrypt password hashing
  - JJWT 0.12 Bearer token generation, verification, and protected endpoints (`GET /api/users/me`, `/api/workflows/**`, `/api/executions/**`)
  - Workflow CRUD REST API (`POST`, `GET`, `GET {id}`, `PUT {id}`, `DELETE {id}`) with ownership-level query isolation
  - React Flow visual workflow builder (`@xyflow/react`) with custom nodes (Trigger, HTTP Request, Generic), handles, zoom/pan/minimap, node palette, configuration drawer, and dirty state management
  - Synchronous in-process workflow execution engine (`POST /api/workflows/{id}/execute`) with graph validation (7 integrity checks), Kahn's topological ordering, fail-fast behavior, data flow propagation, and structured node execution outcomes
  - Node executors: `TriggerNodeExecutor` (manual execution context), `HttpRequestNodeExecutor` (real HTTP requests via standard Java `HttpClient` for GET/POST/PUT/DELETE/PATCH), and `GenericNodeExecutor` (safe pass-through)
  - Persistent workflow execution records (`workflow_executions`) tracking status (`RUNNING` → `SUCCESS`/`FAILED`), timestamps, duration, and granular node executions
  - Fail-fast skipped node persistence (downstream nodes marked `SKIPPED`)
  - Node-level retry policies (`RetryConfig`: `enabled`, `maxRetries`, `initialBackoffMs`, `backoffMultiplier`, `maxBackoffMs`) with safe defaults and exponential backoff
  - Intelligent failure classification (`FailureClassifier`) distinguishing retryable errors (408, 429, 500, 502, 503, 504, connection timeouts, refused connections) from non-retryable errors (400, 401, 403, 404, invalid URLs)
  - Granular attempt tracking (`NodeExecutionAttempt`: attemptNumber, status, timestamps, duration, input, output, error) with retry count recorded on `NodeExecution`
  - Deep secret redaction (`SecretRedactor`) for sensitive headers, API keys, bearer tokens, and credentials across all attempts, results, and persistence
  - Pluggable backoff delay strategy (`RetryDelayStrategy`: production thread sleep, non-blocking test stub)
  - Paginated execution history endpoints (`GET /api/workflows/{id}/executions`, `GET /api/executions`) and detailed execution inspector (`GET /api/executions/{id}`)
  - Execution history panel with pagination and enhanced execution results modal inspecting node inputs, outputs, errors, skipped steps, and attempt histories
  - Multi-stage Docker configurations and Docker Compose with `backend`, `frontend`, and `mongodb`
  - Automated GitHub Actions CI pipeline (backend test & frontend build)

- **Planned Functionality (Phases 7–12)**:
  - Redis asynchronous workers & queues (Planned for Phase 7)
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
- [ ] **Phase 7 — Redis Asynchronous Workers**
- [ ] **Phase 8 — Scheduling + Webhooks**
- [ ] **Phase 9 — AI Nodes**
- [ ] **Phase 10 — Automated Testing + Testcontainers**
- [ ] **Phase 11 — Docker + Deployment**
- [ ] **Phase 12 — GitHub Actions CI/CD + Production Hardening**
