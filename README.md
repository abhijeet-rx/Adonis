# Adonis

> Developer-focused workflow automation platform inspired by lightweight versions of n8n & Zapier.

Adonis enables developers to design, schedule, and execute automated event-driven workflows with a visual node-based editor, resilient Java/Spring Boot execution engine, asynchronous job processing, and AI integrations.

---

## Current Development Phase

**Phase 10 — Automated Integration Testing + Testcontainers** *(Completed)*

This phase adds automated integration testing with real containerized dependencies using Testcontainers, validating the end-to-end infrastructure of Adonis without external API dependencies:
- **Real Infrastructure Validation**: Replaced in-memory approximations in the integration test suite with genuine Docker containers running official `mongo:7.0` and `redis:7-alpine`.
- **Redis Streams & Distributed Worker Verification**: Validated XADD, consumer groups (`XREADGROUP`), atomic MongoDB claiming (`findAndModify`), background heartbeat lease renewals, expired lease takeover, and PEL recovery via `XCLAIM`.
- **Scheduler Concurrency & State Consistency**: Verified distributed lock safety, idempotency of fire time evaluation, schedule modification race resilience, and unique scheduled occurrence constraints under concurrent evaluation.
- **Webhook Pipeline & Idempotency**: Verified capability URL routing, constant-time secret authentication, execution enqueuing, and `Idempotency-Key` deduplication under concurrent delivery.
- **Execution & Retry Policies**: Verified HTTP node status matrix (2xx, 4xx, 5xx, timeouts) and AI node failure classification (429/5xx retryable vs 4xx non-retryable) with granular attempt history persisted in MongoDB.
- **Local Mock HTTP Server**: Built-in zero-dependency JDK `HttpServer` mocking external HTTP targets, OpenAI (`/openai/chat/completions`), and Google Gemini (`/gemini/models`) endpoints with request inspection and canned response queues.
- **Flagship End-to-End Test**: Complete pipeline validation: Webhook Trigger → HTTP Request Node → AI Node (429 rate limit retried to 200) → Structured JSON Schema Validation → Redis Stream → ExecutionWorker → MongoDB execution history with attempt tracking.
- **Preserved Fast Feedback**: All existing unit and slice tests preserved; integration tests run seamlessly via `./mvnw clean test` with dynamic Docker detection and strict CI enforcement.

---

## Current Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 21 LTS, Spring Boot 3.3.4, Maven, Spring Web, Spring Data MongoDB, Spring Data Redis, Spring Security 6, JJWT 0.12, BCrypt |
| **Frontend** | React 19, TypeScript, Vite, Tailwind CSS, @xyflow/react, Lucide Icons |
| **Database** | MongoDB 7.0 (Docker container `adonis-mongodb` on port 27017, collections: `users`, `workflows`, `workflow_executions`, `scheduled_occurrences`) |
| **Queue & Cache** | Redis 7 Alpine (Docker container `adonis-redis` on port 6379, persistent appendonly storage `redis_data`) |
| **Containerization** | Docker, Docker Compose (Multi-stage builds), Testcontainers 1.19.8 |
| **Testing** | JUnit 5, Spring Boot Test, Spring Security Test, Mockito, MockMvc, Testcontainers (MongoDB 7.0, Redis 7 Alpine), Local Mock HTTP Server (`com.sun.net.httpserver`), Awaitility |
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
| `POST` | `/api/workflows/{id}/execute` | Protected (`Bearer <token>`) | Asynchronously enqueue manual workflow execution (returns `202 Accepted` with `QUEUED` status) |
| `POST` | `/api/workflows/{id}/webhook/regenerate` | Protected (`Bearer <token>`) | Regenerate unguessable webhook capability URL and crypto-random secret |
| `POST` | `/api/webhooks/{webhookPath}` | Public (Capability URL) | Trigger asynchronous workflow execution via HTTP endpoint (returns `202 Accepted` with `QUEUED` status) |
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
| `REDIS_STREAM_NAME` | `adonis:execution:stream` | Redis Stream key name for execution jobs |
| `REDIS_CONSUMER_GROUP` | `adonis-workers` | Redis consumer group name for worker coordination |
| `REDIS_CONSUMER_NAME` | `worker-<uuid>` | Unique worker runtime identity in the consumer group |
| `WORKER_ENABLED` | `true` | Toggle execution worker polling loop (set `false` in tests) |
| `WORKER_POLL_TIMEOUT_MS` | `2000` | Stream read block timeout (`XREADGROUP`) in milliseconds |
| `WORKER_PENDING_CLAIM_IDLE_MS` | `60000` | Minimum idle time before unacknowledged pending messages are reclaimed from crashed workers |
| `WORKER_LEASE_DURATION_MS` | `60000` | Ownership lease duration in milliseconds for active workers |
| `WORKER_HEARTBEAT_INTERVAL_MS` | `20000` | Lease heartbeat renewal interval in milliseconds (< lease duration) |
| `QUEUE_TYPE` | `redis` | Queue backend provider (`redis` for production, `in-memory` for tests) |
| `SCHEDULER_ENABLED` | `true` | Toggle centralized cron scheduler (useful for worker-only nodes or tests) |
| `SCHEDULER_POLL_INTERVAL_MS` | `5000` | Interval in milliseconds between scheduler evaluation cycles |
| `WEBHOOK_MAX_BODY_SIZE_BYTES` | `1048576` | Bounded payload size for incoming webhook requests (1MB default, returns 413) |

---

## Phase 8 & 8.1: Scheduling, Webhooks & Trigger Reliability Hardening

Adonis introduces two automated trigger producers without creating competing execution paths, hardened in Phase 8.1 for reliability, security, and concurrency safety:

### 1. Cron Scheduling Engine & Reliability
- **Spring 5/6-Field Cron Expressions**: Supports expressions such as `0 */5 * * * *` (every 5 minutes), `0 0 * * * *` (hourly), `0 0 9 * * MON-FRI` (weekdays at 9 AM).
- **Timezone Aware**: Validated against `java.time.ZoneId` (e.g. `UTC`, `Asia/Kolkata`, `America/New_York`, `Europe/London`). Defaults safely to `UTC` if unspecified. Invalid timezones do not crash the scheduler.
- **Centralized Evaluation**: A single background thread periodically evaluates active workflows, avoiding 1-thread-per-workflow thread exhaustion.
- **Fault Isolation**: Workflow evaluation errors (e.g. malformed cron expressions) are caught, safely logged, and isolated per workflow, ensuring one broken workflow cannot disrupt others.
- **Pure `DO_NOT_CATCH_UP` Misfire Policy**: If the backend is down when a scheduled occurrence passes, missed occurrences are never replayed upon startup. The scheduler discards historical occurrences prior to startup time and calculates the next valid occurrence after `now()`, preventing execution storms after downtime.
- **Schedule Invalidation on Modification**: Whenever `cronExpression` or `timezone` changes (or trigger type switches to SCHEDULE), stored `nextFireTime` and `lastScheduledFireTime` are immediately cleared so the scheduler recalculates from the new schedule.
- **Race-Free Atomic Scheduler Updates**: The scheduler updates `nextFireTime` and `lastScheduledFireTime` via targeted atomic `MongoTemplate.updateFirst` operations rather than replacing the entire `Workflow` document, preventing user canvas/graph edits from being overwritten.
- **Durable Multi-Instance Deduplication**: Prevents duplicate executions across distributed scheduler instances via an atomic uniqueness constraint on the `scheduled_occurrences` collection `(workflowId, scheduledFireTime)`.
- **Occurrence Cleanup on Workflow Deletion**: Deleting a workflow cascades to clean all associated `ScheduledOccurrence` records in MongoDB, preventing orphan records.

### 2. Webhook Triggers & Security Hardening
- **Capability URLs & 256-Bit Entropy**: Workflows are assigned a 64-character cryptographically secure hex identifier (`/api/webhooks/{webhookPath}`) generated via `SecureRandom` (32 bytes = 256 bits of entropy) rather than exposing predictable workflow IDs.
- **Database Uniqueness**: Backed by a MongoDB partial unique compound index `wf_webhook_path_idx` on `triggerConfig.webhookPath` when non-null and string. Application-level collision detection and `DuplicateKeyException` translation return clean 4xx responses.
- **Path Validation**: Strictly validates custom and generated paths against `^[a-zA-Z0-9_-]{8,128}$`, rejecting path traversal (`..`), path separators (`/`, `\`), whitespace, and reserved URI prefixes.
- **Trigger Type Enforcement**: Webhook endpoints resolve workflows strictly requiring `workflow.triggerType == WEBHOOK`. Inactive or stale webhooks (e.g. switched to MANUAL or SCHEDULE) return HTTP `404 Not Found` without revealing workflow existence.
- **Trigger Lifecycle Isolation**: Switching trigger types explicitly cleans obsolete configuration (switching to MANUAL clears cron, timezone, nextFireTime, and webhook details; switching to SCHEDULE clears webhook details; switching to WEBHOOK clears schedule details).
- **Constant-Time Secret Authentication**: Optional shared secrets verified in constant time (`MessageDigest.isEqual`) using SHA-256 digests. Plaintext secrets are never persisted, returned via API, or logged.
- **Bounded Request Processing**: Enforces configurable body size limits (default 1MB). Payloads exceeding the threshold return HTTP `413 Payload Too Large`.
- **Bounded Idempotency-Key**: Supports `Idempotency-Key` header with strict 256-character length limit (rejecting oversized values with HTTP 400).
- **Idempotency Recovery on Queue Failure**: If Redis enqueueing suffers a transient failure on initial webhook ingestion, subsequent retries with the same `Idempotency-Key` re-attempt queue submission rather than stranding the key in a permanent `FAILED` state, while guaranteeing no duplicate executions are spawned.
- **Deep Redaction**: Incoming request headers, query parameters, and JSON payloads are sanitized via `SecretRedactor` to strip credentials, cookies, and bearer tokens before persistence.
- **Asynchronous Execution (`202 Accepted`)**: Returns HTTP 202 immediately with execution ID and `QUEUED` status; the engine is never invoked synchronously.

### 3. SSRF (Server-Side Request Forgery) Considerations
The `HttpRequestNode` enables outbound HTTP calls to user-specified destinations. When paired with Webhook triggers, untrusted external inputs can influence outbound URLs.
- **Mitigation & Constraints**: Webhook payloads are never directly interpreted as unvalidated URLs for automatic background fetching.
- **Documented Security Boundary**: In Phase 8/8.1, Adonis operates in a trusted/private deployment perimeter. Blocking internal IP ranges (e.g. 10.0.0.0/8, 127.0.0.1, 169.254.169.254, and cloud instance metadata endpoints) is formally documented as a production-hardening requirement for Phase 12.

---

## AI Workflow Nodes (Phase 9 Operational)

Adonis provides native AI execution nodes that execute directly within the core `WorkflowExecutionEngine` without extra runtime dependencies or vendor SDKs.

### Environment Configuration

LLM API keys and HTTP client timeouts are configured exclusively on the server side via environment variables:

| Variable | Description | Default |
|---|---|---|
| `OPENAI_API_KEY` | OpenAI API secret key (`sk-...`) | `""` (Disabled if absent) |
| `GEMINI_API_KEY` | Google Gemini API key (`AIzaSy...`) | `""` (Disabled if absent) |
| `ADONIS_AI_TIMEOUT_SECONDS` | HTTP connect & read timeout for AI provider calls | `60` |

> **Zero-Trust Credential Security**:
> API keys must **never** be supplied in workflow JSON definitions. The engine actively rejects any node configuration containing `apiKey`, `api_key`, `secretKey`, or `token` with a `WorkflowValidationException`.
> For Google Gemini, the API key is passed strictly via the `x-goog-api-key` HTTP request header (never via URL query parameter) to eliminate URI-based credential logging.

### Supported Node Types

#### 1. AI Text Generation (`ai_text_generation`)
Generates unstructured text completions using OpenAI or Google Gemini:
- **`provider`**: `"openai"` or `"gemini"`
- **`model`**: e.g., `"gpt-4o"`, `"gpt-4o-mini"`, `"gemini-1.5-flash"`, `"gemini-1.5-pro"`
- **`systemPrompt`**: Optional system instruction guiding model tone and persona
- **`userPrompt`**: Main prompt text supporting runtime variable interpolation
- **`temperature`**: Sampling temperature (`0.0` to `2.0`, default `0.7`)
- **`maxTokens`**: Maximum completion tokens (optional)

**Node Output Schema**:
```json
{
  "content": "The generated text response from the model.",
  "provider": "openai",
  "model": "gpt-4o-mini",
  "usage": {
    "promptTokens": 18,
    "completionTokens": 10,
    "totalTokens": 28
  }
}
```

#### 2. AI Structured Output (`ai_structured_output`)
Generates strictly conforming JSON objects validated against a provided JSON Schema:
- **`provider`**: `"openai"` or `"gemini"`
- **`model`**: Target model identifier
- **`systemPrompt`**: Optional system prompt
- **`userPrompt`**: Main prompt text supporting variable interpolation
- **`jsonSchema`**: JSON Schema object defining required fields, property types (`string`, `number`, `integer`, `boolean`, `array`, `object`), and enums

**Node Output Schema**:
```json
{
  "content": "{\"sentiment\": \"positive\", \"score\": 0.95}",
  "structured": {
    "sentiment": "positive",
    "score": 0.95
  },
  "provider": "gemini",
  "model": "gemini-1.5-flash",
  "usage": {
    "promptTokens": 42,
    "completionTokens": 14,
    "totalTokens": 56
  }
}
```

### Prompt Variable Interpolation

Prompts support runtime interpolation using mustache-style delimiters:
- **`{{nodeId.output.property}}`**: Resolves a nested field from an upstream node's output (e.g. `{{http_1.output.body.data[0].title}}`).
- **`{{nodeId.output}}`**: Resolves the entire output of an upstream node as text or serialized JSON.
- **`{{input.key}}`** or **`{{trigger.output.key}}`**: Resolves properties from the initial trigger payload.

Unresolved variables safely evaluate to empty strings. Dynamic variables are sanitized via `SecretRedactor` before being sent to LLM endpoints.

### Example AI Workflow JSON

Below is a complete, executable workflow demonstrating Webhook ingestion → AI Structured Extraction → AI Text Generation:

```json
{
  "name": "Customer Feedback Triage",
  "description": "Extracts sentiment and generates a support response draft",
  "status": "ACTIVE",
  "triggerType": "WEBHOOK",
  "triggerConfig": {
    "webhookPath": "feedback-triage-12345"
  },
  "nodes": [
    {
      "id": "trigger_1",
      "type": "trigger",
      "name": "Webhook Ingestion",
      "config": {}
    },
    {
      "id": "ai_extract",
      "type": "ai_structured_output",
      "name": "Analyze Feedback Sentiment",
      "config": {
        "provider": "gemini",
        "model": "gemini-1.5-flash",
        "systemPrompt": "Extract sentiment and categorization from feedback.",
        "userPrompt": "Analyze the customer message: {{trigger_1.output.body.message}}",
        "jsonSchema": {
          "type": "object",
          "properties": {
            "sentiment": { "type": "string", "enum": ["POSITIVE", "NEUTRAL", "NEGATIVE"] },
            "priority": { "type": "string", "enum": ["LOW", "MEDIUM", "HIGH"] },
            "summary": { "type": "string" }
          },
          "required": ["sentiment", "priority", "summary"]
        }
      }
    },
    {
      "id": "ai_reply",
      "type": "ai_text_generation",
      "name": "Draft Customer Reply",
      "config": {
        "provider": "openai",
        "model": "gpt-4o-mini",
        "systemPrompt": "You are a courteous support representative.",
        "userPrompt": "Draft a personalized response acknowledging their feedback. Sentiment: {{ai_extract.output.structured.sentiment}}. Summary: {{ai_extract.output.structured.summary}}.",
        "temperature": 0.7,
        "maxTokens": 250
      }
    }
  ],
  "edges": [
    { "id": "e1", "source": "trigger_1", "target": "ai_extract" },
    { "id": "e2", "source": "ai_extract", "target": "ai_reply" }
  ]
}
```

---

## Automated Integration Testing & Testcontainers (Phase 10)

Adonis provides a comprehensive automated integration testing suite using [Testcontainers](https://testcontainers.com/) to validate real infrastructure interactions without depending on external network calls or cloud services:

### Testing Architecture & Infrastructure
- **Real Containerized Dependencies**: Integration tests run against official Docker containers for `mongo:7.0` and `redis:7-alpine` managed via Testcontainers singletons (`MongoTestContainer`, `RedisTestContainer`), eliminating discrepancies between in-memory mocks and production behavior.
- **Dynamic Property Configuration**: Container network ports and credentials are dynamically bound to Spring Boot test contexts via `@DynamicPropertySource`.
- **Zero External Network Dependencies**: External HTTP targets, OpenAI (`/openai/chat/completions`), and Google Gemini (`/gemini/models`) endpoints are served locally by `LocalMockHttpServer` (built using the JDK's standard `com.sun.net.httpserver.HttpServer`), supporting request recording, inspection, and queued mock responses.
- **Docker Detection & CI Guard**: `DockerAvailability` dynamically checks Docker daemon availability. In local environments lacking Docker, integration tests gracefully skip via JUnit 5 `assumeTrue` while unit tests execute. In CI environments (`CI=true`), integration tests strictly assert Docker availability to ensure test coverage is never bypassed in automated pipelines.

### Test Coverage Highlights
- **Persistence (`MongoPersistenceIntegrationTest`)**: User normalization, unique email indexes, workflow ownership queries, execution pagination, granular attempt persistence, and scheduled occurrence unique compound constraints.
- **Redis Streams (`RedisStreamsIntegrationTest`)**: Enqueueing via XADD, consumer group stream consumption (`XREADGROUP`), atomic state transitions (`QUEUED` → `RUNNING` → `SUCCESS`), and post-persistence message acknowledgement (`XACK`).
- **Distributed Worker Leases (`WorkerLeaseIntegrationTest`)**: Worker ownership lease establishment, background heartbeat renewals, ownership loss detection, and expired lease takeover by peer workers.
- **Worker Crash & PEL Recovery (`RedisPendingRecoveryIntegrationTest`)**: Unacknowledged message recovery from the Pending Entries List (PEL) via `XCLAIM` when a worker abruptly crashes.
- **Idempotency & Concurrency (`DuplicateDeliveryIdempotencyIntegrationTest`)**: Atomic `findAndModify` claim race prevention ensuring at-least-once deliveries do not trigger duplicate graph executions.
- **Failure Handling & Retries (`RetryPolicyIntegrationTest`)**: Exponential backoff, retry attempt recording in MongoDB, and downstream step skipping on exhausted attempts.
- **HTTP Node Matrix (`HttpNodeIntegrationTest`)**: Parameterized test coverage across HTTP 200, 201, 204, 400..404, 408, 429, 500..504, request timeouts, and connection errors.
- **AI Nodes & Providers (`AINodeIntegrationTest`)**: Provider execution (OpenAI, Gemini), token usage tracking, and failure classification distinguishing retryable errors (429, 5xx) from non-retryable errors (400, 401, 404).
- **Structured Output & Schema Validation (`AIStructuredOutputIntegrationTest`)**: Strict JSON schema validation, automatic stripping of markdown code fences, malformed JSON rejection, and required field violation handling.
- **Prompt Interpolation (`PromptInterpolationIntegrationTest`)**: Multi-hop output resolution (`{{http_1.output.body.name}}`), trigger input mapping (`{{input.key}}`), and fallback resolution for missing keys.
- **Secret Redaction & Security (`SecretSecurityIntegrationTest`)**: Deep scrubbing of API keys (`sk-...`, `AIzaSy...`) and bearer tokens across execution history, attempts, and error logs in MongoDB.
- **Scheduler Concurrency & State (`SchedulerIntegrationTest`, `SchedulerConcurrencyIntegrationTest`, `ScheduleModificationRaceIntegrationTest`)**: Next fire time calculations, concurrent scheduler race handling via `scheduled_occurrences` unique keys, and schedule modification race resilience.
- **Webhooks & Idempotency (`WebhookIntegrationTest`, `WebhookIdempotencyIntegrationTest`)**: Capability URL verification, constant-time secret check, and `Idempotency-Key` deduplication under concurrent ingestion.
- **Flagship E2E Test (`EndToEndWorkflowIntegrationTest`)**: Full pipeline verification: Webhook ingestion → HTTP Node (200) → AI Node (429 retry then 200) → JSON Schema Validation → Redis Stream → ExecutionWorker → MongoDB execution record with attempt history.

### Running the Test Suite

```bash
cd backend

# Run all unit tests and integration tests (requires Docker daemon for Testcontainers):
./mvnw clean test

# On Windows (PowerShell/CMD):
.\mvnw.cmd clean test
```

---

## Current Status vs. Planned Milestones

- **Current (Phase 0 through Phase 10 — Operational)**:
  - Clean monorepo layout (`backend`, `frontend`, `docker`, `.github/workflows`)
  - Java 21 LTS + Spring Boot 3.3.4 foundation with `/api/health` diagnostic endpoint
  - MongoDB 7.0 persistence (`users`, `workflows`, `workflow_executions`, and `scheduled_occurrences` collections)
  - Partial unique index on `triggerConfig.webhookPath` and compound index on `scheduled_occurrences` `(workflowId, scheduledFireTime)`
  - Redis 7.0 Streams with Consumer Groups (`adonis:execution:stream` using `XADD`, `XREADGROUP`, `XACK`, `XPENDING`, `XCLAIM`)
  - Spring Security 6 stateless authentication with BCrypt password hashing
  - JJWT 0.12 Bearer token generation, verification, and protected endpoints (`GET /api/users/me`, `/api/workflows/**`, `/api/executions/**`)
  - Public unauthenticated capability URL endpoint (`POST /api/webhooks/{webhookPath}`) with 256-bit entropy, strict validation, trigger-type enforcement, and secret verification
  - Workflow CRUD REST API (`POST`, `GET`, `GET {id}`, `PUT {id}`, `DELETE {id}`) with ownership-level query isolation and cascade deletion of scheduled occurrences
  - React Flow visual workflow builder (`@xyflow/react`) with custom nodes (Trigger, HTTP Request, Generic, AI Text Generation, AI Structured Output), handles, zoom/pan/minimap, node palette, configuration drawer, and dirty state management
  - Trigger configuration UI with Manual, Schedule (cron presets + timezone dropdown), and Webhook (URL copy, masked secret, secret regeneration, async notice, and lifecycle cleanup on transition)
  - Asynchronous, non-blocking workflow execution (`POST /api/workflows/{id}/execute` and `POST /api/webhooks/{webhookPath}` return `202 Accepted` immediately with status `QUEUED`)
  - Fail-safe queue submission: transitions execution record to `FAILED` with sanitized messaging if Redis enqueuing fails, with idempotent retry support on webhook re-delivery
  - Queue abstraction: `ExecutionQueue` interface with `RedisExecutionQueue` (production) and `InMemoryExecutionQueue` (test isolation)
  - Autonomous `ExecutionWorker` process implementing Spring's `SmartLifecycle` for graceful shutdown
  - At-least-once message delivery via Redis Streams combined with MongoDB atomic execution claiming (`findAndModify`: `QUEUED` → `RUNNING`) establishing initial worker lease
  - Explicit message acknowledgement (`XACK`) executed strictly after terminal execution state (`SUCCESS` or `FAILED`) is safely persisted to MongoDB
  - Worker crash recovery: automated reclamation of unacknowledged pending messages from the consumer group's Pending Entries List (PEL) via `XCLAIM`
  - Renewable execution ownership lease: workers periodically renew `leaseUntil` and `lastHeartbeatAt` via a background heartbeat scheduler. A long-running workflow is not considered stale based on total execution duration. Worker ownership is determined using a renewable lease.
  - Ownership-safe lease renewals: conditional MongoDB update ensures workers only renew leases they still own, halting heartbeats immediately if ownership is lost
  - Expired lease recovery: workers atomically acquire expired leases (`leaseUntil <= now`). Winning worker marks execution `FAILED` with recovery diagnostics and ACKs message, preventing duplicate side effects.
  - Safe malformed message quarantine: corrupted stream entries are moved to `adonis:execution:stream:dlq` and acknowledged to prevent poison-pill infinite loops
  - Centralized cron scheduler (`AdonisScheduler`) with pure `DO_NOT_CATCH_UP` misfire policy, durable occurrence reservation, race-free atomic MongoDB field updates, and fault isolation
  - Webhook controller (`WebhookController`) with constant-time secret check, bounded body limit, redaction, 256-char max `Idempotency-Key`, and retryable queue failure handling
  - Workflow execution engine: deterministic topological sort, fail-fast behavior, data flow propagation, and structured node execution outcomes
  - Node executors: `TriggerNodeExecutor`, `HttpRequestNodeExecutor`, `GenericNodeExecutor`, `AITextGenerationNodeExecutor`, `AIStructuredOutputNodeExecutor`
  - AI Provider Layer: Provider-neutral SPI (`AIProvider`, `AIRequest`, `AIResponse`, `AIUsage`), `OpenAIProvider`, `GeminiProvider` using native Java `HttpClient`, and `PromptInterpolator`
  - JSON Schema Validator: Lightweight Jackson-based schema validator with markdown code-fence stripping for structured outputs
  - Persistent workflow execution records (`workflow_executions`) tracking status (`QUEUED` → `RUNNING` → `SUCCESS`/`FAILED`), timestamps, duration, trigger type, and granular node executions
  - Fail-fast skipped node persistence (downstream nodes marked `SKIPPED`)
  - Node-level retry policies (`RetryConfig`: `enabled`, `maxRetries`, `initialBackoffMs`, `backoffMultiplier`, `maxBackoffMs`) with safe defaults and exponential backoff
  - Intelligent failure classification (`FailureClassifier`) distinguishing retryable errors (408, 429, 500, 502, 503, 504, `RESOURCE_EXHAUSTED`, connection timeouts, refused connections) from non-retryable errors (400, 401, 403, 404, schema validation errors, missing API keys)
  - Granular attempt tracking (`NodeExecutionAttempt`: attemptNumber, status, timestamps, duration, input, output, error) with retry count recorded on `NodeExecution`
  - Deep secret redaction (`SecretRedactor`) for sensitive headers, API keys (`sk-...`, `AIzaSy...`), bearer tokens, and credentials across all attempts, results, and persistence
  - Pluggable backoff delay strategy (`RetryDelayStrategy`: production thread sleep, non-blocking test stub)
  - Paginated execution history endpoints (`GET /api/workflows/{id}/executions`, `GET /api/executions`) and detailed execution inspector (`GET /api/executions/{id}`)
  - Execution history panel with trigger badges (`MANUAL`, `SCHEDULE`, `WEBHOOK`), pagination, and enhanced execution results modal inspecting node inputs, outputs, errors, skipped steps, and attempt histories
  - Controlled frontend execution polling (every 1.5s) until terminal execution state (`SUCCESS` or `FAILED`)
  - Multi-stage Docker configurations and Docker Compose with `backend`, `frontend`, `mongodb`, and `redis`
  - Testcontainers integration test suite with real MongoDB 7.0 and Redis 7 Alpine containers, Local Mock HTTP Server, and full infrastructure verification
  - Automated GitHub Actions CI pipeline (backend unit & integration tests & frontend build)

- **Planned Functionality (Phases 11–12)**:
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
- [x] **Phase 7.1 — Redis Worker Reliability Hardening**
- [x] **Phase 7.1.1 — Fix Stale RUNNING Execution Handling**
- [x] **Phase 8 — Scheduling + Webhooks**
- [x] **Phase 8.1 — Trigger Reliability & Security Hardening**
- [x] **Phase 8.1.1 — Scheduler Concurrency & Queue Failure Hardening**
- [x] **Phase 8.1.2 — Scheduler State Consistency Hardening**
- [x] **Phase 9 — AI Nodes**
- [x] **Phase 10 — Automated Testing + Testcontainers**
- [ ] **Phase 11 — Docker + Deployment**
- [ ] **Phase 12 — GitHub Actions CI/CD + Production Hardening**


