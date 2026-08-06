# 7. Deployment View

The system runs as five containers on one Hetzner VPS. Only the reverse proxy is reachable from the
internet; everything else exists solely on the compose network.

![Production deployment](diagrams/deployment.svg)

*Diagram source: [`diagrams/deployment.mmd`](diagrams/deployment.mmd).*

## 7.1 Infrastructure

| | |
|---|---|
| **Host** | Hetzner CX33 — x86_64, 4 vCPU, 8 GB RAM, 75 GB disk, Ubuntu 26.04 LTS, Nuremberg |
| **Application** | <https://maintenance.smartsupply.com.de> |
| **Identity provider** | <https://auth.smartsupply.com.de> |
| **Images** | `ghcr.io/keglev/maintenance-assistant/{backend,frontend}`, built multi-arch (amd64 + arm64) |
| **Stack definition** | [`docker/docker-compose.prod.yml`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/docker-compose.prod.yml), deployed to `/opt/maintenance-assistant/` |
| **Host preparation** | [`infra/provision.sh`](https://github.com/Keglev/maintenance-assistant/blob/main/infra/provision.sh), idempotent |

The host was planned as a CAX21 (arm64) and is a CX33 (x86_64); the reasoning and date are recorded
in DECISIONS.txt. Because CI keeps publishing both architectures, that revision changed a purchase
order and nothing else.

## 7.2 Containers

| Container | Exposed | Purpose |
|---|---|---|
| **caddy** | **80, 443** | The only internet-facing container. Terminates TLS with certificates it obtains and renews from Let's Encrypt on its own, and routes by hostname and path. |
| **frontend** | — | nginx serving the Angular bundle. `try_files` keeps client-side routes working on reload. |
| **backend** | — | Spring Boot. Reached only at `/api/*` plus the two OpenAPI paths. |
| **keycloak** | — | Production mode (`start`, not `start-dev`) against Postgres, behind the proxy. |
| **postgres** | — | pgvector image, holding both the application schema and Keycloak's, separated by role. |

Nothing but Caddy publishes a port. The database is not merely firewalled off — it has no host port
at all, so there is no path to it from outside the compose network.

## 7.3 Routing and TLS

Caddy serves two hostnames from one certificate authority account:

- `maintenance.smartsupply.com.de` → `/api/*`, `/swagger-ui*` and `/v3/api-docs*` to **backend**;
  everything else to **frontend**. The API is therefore same-origin with the application, which is
  why the browser needs no CORS grant and the frontend's `apiBaseUrl` is a relative path.
- `auth.smartsupply.com.de` → **keycloak**.

TLS ends at Caddy; the internal network is plain HTTP. Keycloak is told the public URL
(`KC_HOSTNAME`) and to trust the forwarded headers (`KC_PROXY_HEADERS=xforwarded`), so the tokens it
issues carry the address the browser actually used. That matters because the backend validates the
`iss` claim against the same public URL, even though it could reach Keycloak by container name.

## 7.4 Configuration and secrets

Configuration is environment variables from `/opt/maintenance-assistant/.env.prod`, which is mode
0600, owned by the unprivileged `deploy` user, and never committed —
[`docker/.env.prod.example`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/.env.prod.example)
documents every variable. Database and Keycloak admin passwords were generated on the host.

The frontend image contains no hostname. It reads `/config.json` at startup, which the deployment
bind-mounts, so the same image tag runs locally and in production. Image references are per service,
so a rollback is a value change in `.env.prod` plus `docker compose up -d`.

## 7.5 Host hardening

`infra/provision.sh` is the record of what the host is, and is safe to re-run:
unattended security upgrades; Docker from the official repository; an unprivileged `deploy` user
that owns the stack; sshd with `PasswordAuthentication no` and `PermitRootLogin prohibit-password`
via a drop-in that survives package upgrades; fail2ban on the sshd jail.

## 7.6 Known gaps

- **No backups yet.** The Postgres volume is the only copy of the data. Acceptable while the corpus
  is synthetic and reproducible; it stops being acceptable in Phase 2.
- **Deployment is manual.** `backend-deploy.yml` and `frontend-deploy.yml` publish images but stop
  short of the host; the SSH step is still a commented TODO.
- **Single host, no redundancy.** Recovery is a fresh `docker compose up -d` on a new server.
- **`latest` is a moving tag.** Pinning `sha-<commit>` in `.env.prod` is the safer habit once the
  demo is being shown.
