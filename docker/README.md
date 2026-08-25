# Local development stack

Two services: PostgreSQL + pgvector and Keycloak. The application is not one of them — the
backend runs outside compose (`cd backend && mvn spring-boot:run`, or from the IDE) and the
frontend on the Angular dev server, which is the stack
[the e2e suite documents](../frontend/e2e/README.md#what-it-needs-running).
[ADR-001](../docs/adr/ADR-001-modular-monolith-first.md) counts three containers for the
DEPLOYED system; `docker-compose.prod.yml` is where all of them run together.

The **base** images are published for `linux/amd64` **and** `linux/arm64`, so this file runs
unchanged on a laptop of either architecture. The images CI publishes for production are
`linux/amd64` only — the arm64 leg was removed on 2026-08-10 (DECISIONS.txt, "REVISED AGAIN
2026-08-10") because production is a CX33 x86_64 and the emulated build was crashing.

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

## Files the pipeline never deploys

CI ships **images and nothing else**. Every file below lives on the host, is edited there by
hand, and is invisible to a merge — a change committed here is not live until someone copies it
across. This is OPS RULE 1 in DECISIONS.txt, and it has cost a failed deploy round more than
once.

SEVEN, MEASURED 2026-08-25 from the bind mounts in `docker-compose.prod.yml` and from the host
itself — not three, and not the four the survey expected:

| # | On the host, under `/opt/maintenance-assistant/` | What it is | Mount |
|---|---|---|---|
| 1 | `docker-compose.prod.yml` | the stack definition itself | — |
| 2 | `.env.prod` | every secret and hostname | — |
| 3 | `caddy/Caddyfile` | TLS, routes, security headers | single file |
| 4 | `frontend-config.json` | the SPA runtime configuration | single file |
| 5 | `keycloak/realm-export.json` | realm, roles, clients | single file |
| 6 | `keycloak/themes/wartungsassistent/` | the login theme | directory |
| 7 | `postgres/init-keycloak-db.sh` | runs once, on an empty data directory | single file |

**OPS RULE 3 applies to all four single-file mounts (3, 4, 5, 7).** A single-file bind mount
tracks the file's INODE, not its path: replacing the host file with `mv` gives it a new inode
and the container keeps reading the old one, silently — `caddy validate` and `caddy reload` run
INSIDE the container and both happily report success against stale content. **Always `cp` OVER
the existing file**, or `up -d --force-recreate <service>`. Never `mv`. The theme (6) is a
directory mount and does not carry the trap, though theme caching means it still needs a
force-recreate to take effect.

### frontend-config.json

The frontend image is environment-agnostic on purpose: it is built once and deployed anywhere,
so it cannot contain a hostname. `config.json` is bind-mounted over the built bundle and read
once at startup by `ConfigService`, before the first route renders.

`frontend-config.json.example` in this directory is **the shape**; the copy on the server is
**the value**. Its three keys are exactly the `RuntimeConfig` interface
(`frontend/src/app/core/config/runtime-config.ts`) — `keycloakIssuer`, `keycloakClientId`,
`apiBaseUrl`. `apiBaseUrl` is `/api` in every environment, because the SPA and the API are
same-origin behind Caddy; it is in the file so the shape is complete, not because it varies.
`frontend/public/config.json` is the versioned development copy of the same shape.

A missing or malformed file is **not** an error: `ConfigService` falls back to the values
compiled into `environment.ts`, so a deployment that forgets the mount serves working defaults
rather than a blank page. That is a safety net, not a licence — it also means a broken
`config.json` fails **silently**, pointing the browser at whatever was compiled in. So:

```bash
# on the host, from /opt/maintenance-assistant
cp frontend-config.json frontend-config.json.bak-$(date +%Y%m%d-%H%M%S)
cp /path/to/new frontend-config.json      # cp OVER it — OPS RULE 3
python3 -m json.tool frontend-config.json >/dev/null && echo "valid JSON"
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate frontend
```

`jq empty frontend-config.json` is the shorter check where `jq` is installed; it is not on this
host, which is why the Python one-liner is written out.

**Backups.** None of these seven are in the nightly `infra/backup.sh`, and that is deliberate
rather than an omission: the script protects the *data* (a `pg_dump` and the protocol-files
volume), while the *host* — these files included — is covered by the Hetzner snapshot. arc42
§7.6.2 says so in as many words: "A snapshot protects the *host* — the compose files,
`.env.prod`, Caddy's certificate store, the Keycloak database and the state of the OS itself."

## Reset

```bash
docker compose down -v   # also drops the postgres volume — all data is gone
```
