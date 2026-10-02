# ==============================================================================
# Adonis React Frontend - Production Dockerfile (Vite + Nginx)
# Multi-stage build: Compile with Node.js 20 -> Serve with minimal Nginx Alpine
# ==============================================================================

# Stage 1: Build React SPA
FROM node:20-alpine AS builder
WORKDIR /app

# Cache package dependencies
COPY frontend/package*.json ./
RUN npm ci

# Default to empty string for production (same-origin /api proxy via Nginx)
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

# Copy source code and build production assets
COPY frontend/ ./
RUN npm run build

# Stage 2: Production Nginx Runtime
FROM nginx:alpine AS runner

# Apply latest Alpine security patches
RUN apk --no-cache upgrade

# Replace default Nginx configuration with SPA fallback + /api reverse proxy
COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copy compiled static HTML/JS/CSS assets
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

# Health check against Nginx /healthz endpoint
HEALTHCHECK --interval=10s --timeout=5s --start-period=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
