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

## 2. High-Level Component Topology

```mermaid
graph TD
    subgraph Client["Client Tier"]
        UI["React 19 + TypeScript SPA<br/>(Vite, Tailwind, React Flow - Planned Phase 3)"]
    end

    subgraph Gateway["API & Ingress Tier"]
        API["Spring Boot 3.3 REST API<br/>(/api/health active, others Phase 1+)"]
        AUTH["Spring Security & JWT<br/>(Planned Phase 1)"]
    end

    subgraph Engine["Execution Tier (Planned Phase 4)"]
        DAG["DAG Graph Compiler & Validator<br/>(Planned Phase 4)"]
        SYNC["Synchronous Step Engine<br/>(Planned Phase 4)"]
        QUEUE["Redis Task Queue / PubSub<br/>(Planned Phase 7)"]
        WORKER["Async Workflow Worker Pool<br/>(Planned Phase 7)"]
    end

    subgraph Storage["Data & Cache Tier (Planned)"]
        MONGO[("MongoDB 7.0<br/>(Planned Phase 1)")]
        REDIS[("Redis 7.2<br/>(Planned Phase 7)")]
    end

    subgraph Integrations["External Services (Planned Phase 8 & 9)"]
        AI["AI Providers<br/>(Gemini / OpenAI - Planned Phase 9)"]
        HOOKS["External Webhooks & Schedulers<br/>(Planned Phase 8)"]
    end

    UI -->|REST / JSON| API
    API --> AUTH
    API --> DAG
    DAG --> SYNC
    DAG --> QUEUE
    QUEUE --> WORKER
    SYNC --> MONGO
    WORKER --> MONGO
    WORKER --> REDIS
    WORKER --> AI
    WORKER --> HOOKS
```

### Component Status (Current vs. Planned)

The architecture diagram above outlines the full platform vision. Current development status (**Phase 1**):

| Subsystem / Technology | Status | Implementation Milestone |
|---|---|---|
| **Core Monorepo & Build Pipeline** | **Operational** | Phase 0 (Completed) |
| **Spring Boot 3.3 REST Baseline** | **Operational** (`/api/health`) | Phase 0 (Completed) |
| **React + TypeScript UI Shell** | **Operational** (Auth & Diagnostic) | Phase 0 & 1 (Completed) |
| **MongoDB Persistence** | **Operational** (`users` collection) | Phase 1 (Completed) |
| **Authentication & User Management** | **Operational** (JWT + BCrypt) | Phase 1 (Completed) |
| **Workflow CRUD APIs** | *Planned (Not Implemented)* | Phase 2 (Workflow CRUD) |
| **React Flow Visual Canvas** | *Planned (Not Implemented)* | Phase 3 (React Flow Visual Workflow Builder) |
| **Workflow Execution Engine** | *Planned (Not Implemented)* | Phase 4 (Workflow Execution Engine) |
| **Execution History & Logs** | *Planned (Not Implemented)* | Phase 5 (Execution History + Logs) |
| **Retries & Failure Handling** | *Planned (Not Implemented)* | Phase 6 (Retries + Failure Handling) |
| **Redis Asynchronous Workers** | *Planned (Not Implemented)* | Phase 7 (Redis Asynchronous Workers) |
| **Scheduling & Webhooks** | *Planned (Not Implemented)* | Phase 8 (Scheduling + Webhooks) |
| **AI Intelligent Nodes** | *Planned (Not Implemented)* | Phase 9 (AI Nodes) |

---

## 3. Current Phase 1 Architecture

In Phase 1, client-server communication incorporates stateless JWT authentication and MongoDB persistence:

```
[Browser / React App] 
      │
      ├── POST /api/auth/register ──> Hashes password (BCrypt), saves User to MongoDB, returns JWT
      ├── POST /api/auth/login    ──> Verifies password with BCrypt, returns JWT
      ├── GET  /api/users/me      ──> Authenticated via Bearer JWT, returns user profile
      └── GET  /api/health        ──> Public health diagnostic
```

### Component Breakdown

| Component | Responsibility in Phase 0 | Technology |
|---|---|---|
| `frontend` | Visual interface, landing shell, and backend health diagnostic | React, TypeScript, Vite, Tailwind CSS |
| `backend` | Core REST entry point, health endpoint, Spring Boot web runtime | Java 21, Spring Boot 3.3.4, Maven |
| `docker` | Multi-stage container definitions for isolated builds | Docker, Docker Compose |
| `.github/workflows` | Continuous integration pipeline verifying build and test integrity | GitHub Actions |

---

## 4. Package Architecture (Backend)

The backend follows a layered architecture with strict dependency flow:

```
backend/src/main/java/com/adonis/
├── AdonisApplication.java       # Application Bootstrap
├── config/                      # Web MVC, CORS, and cross-cutting beans
├── controller/                  # REST Controllers (exposing HTTP endpoints)
├── dto/                         # Strongly-typed Data Transfer Records
├── exception/                   # Global exception handling & API error formats (Phase 1+)
├── model/                       # Domain entities & MongoDB documents (Phase 1+)
├── repository/                  # Spring Data MongoDB repositories (Phase 1+)
├── security/                    # Spring Security configuration, JWT filters (Phase 1+)
└── service/                     # Workflow business logic & orchestration (Phase 4+)
```

---

## 5. Port Allocations & Networking

| Service | Internal Port | Host / Exposed Port | Protocol | Purpose |
|---|---|---|---|---|
| `frontend` | 80 (prod) / 5173 (dev) | 5173 | HTTP | User Interface |
| `backend` | 8080 | 8080 | HTTP | REST API & Engine |
| `mongodb` | 27017 | 27017 | TCP | Workflow & User persistence *(Planned Phase 1)* |
| `redis` | 6379 | 6379 | TCP | Async job queue *(Planned Phase 7)* |
