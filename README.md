# Event Management Platform

Microservices platform (Spring Boot 4, Spring Cloud, Eureka, Gateway, Resilience4j, PostgreSQL, Docker).

## Running locally

Prerequisites: Java 21 and Docker with Compose v2. Create your local configuration once:

```bash
cp .env.example .env   # then set your own values; .env is git-ignored and must never be committed
```

### Option 1: whole platform in Docker

```bash
docker compose --profile app up -d --build
docker compose --profile app ps          # wait until the 8 containers are (healthy)
```

| URL                                     | Purpose                                            |
|-----------------------------------------|----------------------------------------------------|
| `http://localhost:8080`                 | API Gateway, the single entry point                |
| `http://localhost:8080/swagger-ui.html` | Swagger UI of all services (Select a definition)   |
| `http://localhost:8761`                 | Eureka dashboard                                   |

Published ports are bound to `127.0.0.1` only. The services publish no port: they are reachable through the gateway.

### Option 2: databases in Docker, services from the IDE

```bash
docker compose up -d                     # databases only (ports 5433, 5434, 5435)
```

Then start `discovery-server` first, followed by the other services. The aggregated Swagger UI works here too
(`http://localhost:8080/swagger-ui.html`), and each service also serves its own, for example
`http://localhost:8083/swagger-ui/index.html`.

> Do not run both options at the same time. Containers publish on `127.0.0.1` while the IDE binds every
> interface, so both can hold the same port and register in the same Eureka, which mixes the instances.
> Stop one option before starting the other (`docker compose --profile app stop`).

### End-to-end smoke test

```bash
./scripts/e2e-docker.sh
```

Runs against Option 1, through the gateway only: nominal registration, duplicate (`409 ALREADY_REGISTERED`),
full event (`409 EVENT_FULL`), `event-service` outage (fast `503` once the circuit is open, gateway fallback),
recovery after restart, and no seat leaked.

### Docker setup at a glance

- One multi-stage `Dockerfile` for every module (`--build-arg MODULE=...`): layered jar, Maven cache mount, non-root user.
- `docker` Spring profile: unique Eureka instance id, service name announced instead of an IP, short discovery
  intervals (development values; production keeps the defaults).
- One private network per service and its database: a database is reachable by its owner only.
- Health checks on `/actuator/health/readiness`, used to order the startup.

### Stopping

```bash
docker compose --profile app down        # keeps the data volumes
docker compose --profile app down -v     # also deletes the databases
```
