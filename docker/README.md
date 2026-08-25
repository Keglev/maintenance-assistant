# Local development stack

Two services: PostgreSQL + pgvector and Keycloak. The application is not one of them — the
backend runs outside compose (`cd backend && mvn spring-boot:run`, or from the IDE) and the
frontend on the Angular dev server, which is the stack
[the e2e suite documents](../frontend/e2e/README.md#what-it-needs-running).
[ADR-001](../docs/adr/ADR-001-modular-monolith-first.md) counts three containers for the
DEPLOYED system; `docker-compose.prod.yml` is where all of them run together.

All images are published for `linux/amd64` **and**
`linux/arm64`, so this file runs unchanged on Apple Silicon and on the Hetzner CAX (arm64) target.

## Start

```bash
cd docker
cp .env.example .env        # never commit .env — it is git-ignored
docker compose up
```

## What you should see

- `maintenance-postgres` reaches **healthy** (`pg_isready`) after a few seconds.
- `maintenance-keycloak` logs `Imported realm maintenance` (or `Realm 'maintenance' imported`),
  then `Keycloak 26.x on JVM ... started`, and reaches **healthy**.
- Keycloak admin console at <http://localhost:8081> — log in with `KEYCLOAK_ADMIN` /
  `KEYCLOAK_ADMIN_PASSWORD` from `.env`, switch to the **maintenance** realm and find four realm
  roles, four users and the `frontend` / `backend` clients.
- The realm's OIDC discovery document answers at
  <http://localhost:8081/realms/maintenance/.well-known/openid-configuration>.

Check status with `docker compose ps` — both services should read `healthy`.

There is no `app` service here. A commented-out one survived until 2026-08-25 saying it would be
"enabled in Phase 1, once backend/ contains a Spring Boot app" — which had been true for one
week of this project and false ever since. Start the backend yourself against the two services
above; `frontend/e2e/README.md` lists the ports.

**If port 5432 is already taken** by a PostgreSQL installed on the host, the container fails to
bind. Set `POSTGRES_PORT=5433` in `.env` — only the host-side port changes; inside the compose
network the database stays on 5432.

## Realm

| | |
|---|---|
| Realm | `maintenance` |
| Realm roles | `operator`, `techniker`, `schichtleiter`, `admin` |
| Public client | `frontend` — Authorization Code Flow + PKCE (S256), redirect `http://localhost:4200/*` |
| Audience | `backend` — added to the access token by an audience mapper on the `frontend` client |

### Demo users — development only

| Username | Realm role | Password |
|---|---|---|
| `operator` | operator | `demo1234` |
| `techniker` | techniker | `demo1234` |
| `schichtleiter` | schichtleiter | `demo1234` |
| `admin` | admin | `demo1234` |

These credentials are deliberately in the versioned realm export: this is a synthetic-data demo
stack with no real users, and reproducibility beats secrecy here. The public deployment gets its own
passwords, set outside the repository.

### How the backend validates a token

The `frontend` client carries an audience mapper that writes `backend` into the access token's `aud`
claim, so a Spring Boot OAuth2 Resource Server configured with

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/maintenance
```

can validate the signature via JWKS **and** check that the token was actually meant for it. Realm
roles arrive in the standard `realm_access.roles` claim (from Keycloak's built-in `roles` scope) and
are mapped to Spring Security authorities in the application.

## Changing the realm

The export is the source of truth and is imported on every start. A change made in the admin console
and not re-exported is lost on the next fresh start (risk R-7):

```bash
docker compose exec keycloak /opt/keycloak/bin/kc.sh export \
  --dir /tmp/export --realm maintenance --users realm_file
docker compose cp keycloak:/tmp/export/maintenance-realm.json ./keycloak/realm-export.json
```

Review the diff before committing — the export contains generated ids and timestamps that add noise.

## Reset

```bash
docker compose down -v   # also drops the postgres volume — all data is gone
```
