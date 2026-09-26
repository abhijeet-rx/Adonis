# Adonis

> Developer-focused workflow automation platform inspired by lightweight versions of n8n & Zapier.

Adonis enables developers to design, schedule, and execute automated event-driven workflows with a visual node-based editor, resilient Java/Spring Boot execution engine, asynchronous job processing, and AI integrations.

---

## Current Development Phase

**Phase 2 — Workflow CRUD** *(Completed)*

This phase introduces the workflow domain, MongoDB persistence for workflow definitions, RESTful CRUD endpoints, strict user ownership isolation derived from JWT identity, request validation, and comprehensive automated test suites.

---

## Current Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 21 LTS, Spring Boot 3.3.4, Maven, Spring Web, Spring Data MongoDB, Spring Security 6, JJWT 0.12, BCrypt |
| **Frontend** | React 19, TypeScript, Vite, Tailwind CSS, Lucide Icons |
| **Database** | MongoDB 7.0 (Docker container `adonis-mongodb` on port 27017) |
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
| `GET` | `/actuator/health` | Public | Spring Boot Actuator health metric |

> **Workflow Ownership & Privacy**:
> Workflows are strictly scoped to the authenticated user derived from the validated JWT token (`UserPrincipal.id()`). Lookups, updates, and deletions enforce ownership in database-level queries (`findByIdAndUserId`), ensuring users can never see, modify, or delete another user's workflows. Non-owned workflows return `404 Not Found` without leaking document existence.

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

- **Current (Phase 0 & Phase 1 — Operational)**:
  - Clean monorepo layout (`backend`, `frontend`, `docker`, `.github/workflows`)
  - Java 21 LTS + Spring Boot 3.3.4 foundation with `/api/health` diagnostic endpoint
  - MongoDB 7.0 persistence (`users` collection, unique lowercase email index)
  - Spring Security 6 stateless authentication with BCrypt password hashing
  - JJWT 0.12 Bearer token generation, verification, and protected endpoints (`GET /api/users/me`)
  - React 19 + TypeScript + Vite + Tailwind CSS frontend with registration, login, logout, and protected API testing
  - Multi-stage Docker configurations and Docker Compose with `backend`, `frontend`, and `mongodb`
  - Automated GitHub Actions CI pipeline (backend test & frontend build)

- **Planned Functionality (Phases 2–12)**:
  - Workflow CRUD APIs (Planned for Phase 2)
  - React Flow visual workflow builder (Planned for Phase 3)
  - Workflow execution engine (Planned for Phase 4)
  - Execution history & logs (Planned for Phase 5)
  - Retries & failure handling (Planned for Phase 6)
  - Redis asynchronous workers (Planned for Phase 7)
  - Scheduling & webhooks (Planned for Phase 8)
  - AI nodes powered by Gemini/OpenAI (Planned for Phase 9)
  - Automated testing & Testcontainers (Planned for Phase 10)
  - Production Docker & deployment (Planned for Phase 11)
  - CI/CD & production hardening (Planned for Phase 12)

---

## Roadmap

- [x] **Phase 0 — Project Initialization**
- [x] **Phase 1 — Authentication + MongoDB + User Management**
- [ ] **Phase 2 — Workflow CRUD**
- [ ] **Phase 3 — React Flow Visual Workflow Builder**
- [ ] **Phase 4 — Workflow Execution Engine**
- [ ] **Phase 5 — Execution History + Logs**
- [ ] **Phase 6 — Retries + Failure Handling**
- [ ] **Phase 7 — Redis Asynchronous Workers**
- [ ] **Phase 8 — Scheduling + Webhooks**
- [ ] **Phase 9 — AI Nodes**
- [ ] **Phase 10 — Automated Testing + Testcontainers**
- [ ] **Phase 11 — Docker + Deployment**
- [ ] **Phase 12 — GitHub Actions CI/CD + Production Hardening**
