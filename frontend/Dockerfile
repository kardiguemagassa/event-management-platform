# syntax=docker/dockerfile:1

# Build stage: dependencies from the lockfile only (cached layer), then the production build.
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# Runtime stage: static files behind nginx, non-root (port 8080), no Node.js.
FROM nginxinc/nginx-unprivileged:1.31-alpine
# Alpine publishes security fixes before the base image is rebuilt: apply them at build time (as root, then back to the
# unprivileged user of the image), so that the release scan does not depend on the upstream rebuild schedule.
USER root
RUN apk upgrade --no-cache
USER 101
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /workspace/dist/event-platform-web/browser /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
