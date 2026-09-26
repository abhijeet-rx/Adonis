# Adonis Docker Configurations

This directory contains containerization assets for Adonis.

## Directory Structure

```
docker/
├── backend/
│   └── Dockerfile      # Multi-stage Java 21 Spring Boot build
├── frontend/
│   └── Dockerfile      # Multi-stage Node 20 build + Nginx alpine serving
└── README.md
```

## Running with Docker Compose

From the project root:

```bash
# Build and run backend and frontend services
docker compose up --build
```

- Frontend: http://localhost:5173
- Backend Health: http://localhost:8080/api/health
