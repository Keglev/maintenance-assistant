# maintenance-assistant

**A machine stops at 02:00. No technician on site.**

The operator reads an error code off a 2005 hydraulic press that offers no diagnostics beyond
that code. The fix is written down — in a PDF, a scan, or a paper folder in a cabinet two halls
away. Often it was solved last month. Nobody can find it. So the escalation chain runs its
course (operator → technician after 10 minutes → shift supervisor → management), and every step
costs downtime.

This project makes that knowledge findable. Shop-floor staff ask in plain German or English —
*"Presse 3 zeigt Fehler E-47, gab es das schon?"* — and get an answer **with citations to the
source protocols**, so it can be verified rather than trusted. Answers are filtered by role: an
operator sees only what an operator may safely do (clean a sensor, clear a jam, load a program)
plus a clear "escalate now" when a technician is required. When no protocol matches, the system
says so instead of inventing one. All data processing happens exclusively on EU infrastructure.

It is not a ticketing or CMMS system. The escalation chain is organizational context, not a
feature.

## Planned architecture

A modular monolith in Spring Boot 4.1 (Java 21) with internal modules for ingestion, query and
web, fronted by a thin Angular client — three containers in total, running on a single 8 GB VPS
via one `docker compose up`. Protocols are chunked, embedded through an EU-hosted LLM provider,
and stored in PostgreSQL with pgvector, so a single SQL query combines the machine filter with
vector ranking. Authentication and the four roles come from Keycloak via OIDC (Authorization
Code Flow + PKCE in the browser, OAuth2 Resource Server in the backend), with role-based answer
filtering enforced server-side.

**Status: in development, Phase 1 (walking skeleton).**

## Demo

**<https://maintenance.smartsupply.com.de>**

Sign in with any of the four demo users — the role decides what the answer looks like:

| Username | Role | Password |
|---|---|---|
| `operator` | Operator — operator-safe steps and escalation advice only | `demo1234` |
| `techniker` | Techniker — full technical answers with citations | `demo1234` |
| `schichtleiter` | Schichtleiter — the above plus protocol upload | `demo1234` |
| `admin` | Admin — user and role administration | `demo1234` |

Synthetic data only; these credentials are public on purpose. Also reachable:
[API docs](https://maintenance.smartsupply.com.de/swagger-ui.html) ·
[Keycloak](https://auth.smartsupply.com.de).

**Phase 1 scope:** signing in works end to end and the protected page shows the identity and realm
roles the backend read from your token. Search and upload arrive in Phase 3.

## Documentation

_Placeholder — will link to the published documentation site (GitHub Pages, `gh-pages` branch)._

In the repository meanwhile:

- [docs/arc42/](docs/arc42/) — architecture documentation (arc42)
- [docs/adr/](docs/adr/) — architecture decision records
- [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) — requirements and non-functional requirements
- [docs/DOMAIN-MODEL.md](docs/DOMAIN-MODEL.md) — entities and data-model rationale
- [AI-USAGE.md](AI-USAGE.md) — how AI was used in this repository

## Local Development

Requires Docker, Java 21, Maven and Node 20+. Start the three pieces in this order — each one
depends on the previous.

**1. Infrastructure** — PostgreSQL + pgvector and Keycloak:

```bash
cd docker
cp .env.example .env          # never committed
docker compose up -d
```

Wait until `docker compose ps` shows both services `healthy`. Keycloak imports the `maintenance`
realm on startup: four roles, four demo users (password `demo1234`), and the public `frontend`
client. Admin console: <http://localhost:8081>.

If port 5432 is already taken by a PostgreSQL on your machine, set `POSTGRES_PORT=5433` in
`docker/.env` and pass the matching JDBC URL to the backend below.

**2. Backend** — Spring Boot on port 8080:

```bash
cd backend
mvn spring-boot:run
# with a relocated database port:
# SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/maintenance mvn spring-boot:run
```

Check it: <http://localhost:8080/api/health> answers `{"status":"UP",…}`, and the API docs are at
<http://localhost:8080/swagger-ui.html>.

**3. Frontend** — Angular dev server on port 4200:

```bash
cd frontend
npm install
npm start
```

Open <http://localhost:4200>. You are redirected to `/login`; signing in sends you to Keycloak and
back to `/home`, which shows your username and realm roles. `/api/*` is proxied to the backend on
port 8080, so the browser stays on one origin and no CORS setup is needed.

Log in with any demo user — `operator`, `techniker`, `schichtleiter` or `admin`, password
`demo1234`.

### Tests and documentation

```bash
cd backend  && mvn verify        # JUnit + JaCoCo  -> backend/target/site/jacoco/
cd frontend && npm run test:ci   # vitest (jsdom)  -> frontend/coverage/
cd frontend && npm run docs      # Compodoc        -> frontend/documentation/
```

Coverage and generated documentation are not committed; CI regenerates them and publishes them to
the documentation site.

### Shutting down

```bash
cd docker && docker compose down     # add -v to also drop the database volume
```

## License

[MIT](LICENSE)
