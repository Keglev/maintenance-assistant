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
| **Images** | `ghcr.io/keglev/maintenance-assistant/{backend,frontend}`, built `linux/amd64` only since 2026-08-10 (DECISIONS.txt, "REVISED AGAIN 2026-08-10"); the base images stay multi-arch |
| **Stack definition** | [`docker/docker-compose.prod.yml`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/docker-compose.prod.yml), deployed to `/opt/maintenance-assistant/` |
| **Host preparation** | [`infra/provision.sh`](https://github.com/Keglev/maintenance-assistant/blob/main/infra/provision.sh), idempotent |

The host was planned as a CAX21 (arm64) and is a CX33 (x86_64); the reasoning and date are recorded
in DECISIONS.txt. That revision changed a purchase order and nothing else — but the follow-on did
cost something, and the record says so: on 2026-08-10 the arm64 build leg was removed because it
had no consumer and was crashing under QEMU. Moving back to an arm64 host is now a purchase
order PLUS restoring one line in each deploy workflow, or adding a native arm64 runner.

### Shared host

Since 2026-08-27 this VPS also hosts **[inventory-service](https://github.com/Keglev/inventory-service)**,
the SmartSupplyPro backend, as a **second compose project** under `/opt/smartsupplypro`. The two
projects are separate stacks — separate databases, separate volumes, separate lifecycles, separate
repositories. **Caddy is the only shared component**, and `api.smartsupplypro.de` terminates here.

**The coupling is a network name, and that is the whole of it.** `docker-compose.prod.yml` declares
no `networks:` block, so Compose creates the default network and names it after the project:
`maintenance-assistant_default` — the value of `name:` at the top of the file, plus `_default`.
The other project joins **that** network as an external one, which is what lets this Caddyfile write
`reverse_proxy ssp-backend:8081` and have the container name resolve.

Two consequences worth stating before somebody trips over them:

- **The name is derived, not configured.** Nothing in this repository sets it; it falls out of
  `name: maintenance-assistant`. Changing that line renames the network, and the other project's
  containers then fail to attach — a failure that surfaces over there, in a repository whose
  maintainer may not be reading this one.
- **The declaration lives on the joining side.** inventory-service's compose file names
  `maintenance-assistant_default` as `external: true`. This project's compose file is untouched by
  the arrangement, which is deliberate: a shared network declared here would make this stack's
  startup depend on a project it does not own.

**A new public hostname is probed within seconds of its certificate being issued.** Four
certificate-transparency scanners reached `api.smartsupplypro.de` from four addresses inside a
minute of Caddy obtaining the certificate on 2026-08-27, while the upstream did not yet exist and
every request was answered with a 502. CT logs are public and watched continuously, so the window
between "the name resolves and serves TLS" and "the application behind it is configured" is not
private: **the upstream's own security configuration has to be right before its container starts**,
not shortly afterwards.

If inventory-service ever leaves this host, the removal is the site block in
[`docker/caddy/Caddyfile`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/caddy/Caddyfile)
and nothing else here.

#### Memory limits, and why they arrived with the second project

Measured on the host, 2026-08-27, with nothing limited:

| Container | Resident | JVM heap ceiling |
|---|---|---|
| backend | 301.9 MiB | **5,812 MiB** (`-XX:MaxRAMPercentage=75.0` against the host's RAM) |
| keycloak | 686.1 MiB | **5,424 MiB** (the image's `MaxRAMPercentage=70`) |
| postgres | 96.0 MiB | — |
| caddy | 66.0 MiB | — |
| frontend | 4.8 MiB | — |

`docker inspect --format '{{.HostConfig.Memory}}'` returned `0` for every container: the `LIMIT`
column in `docker stats` was reading the host's 7.565 GiB.

**A JVM sizes its heap as a percentage of the memory it can see, and without a cgroup limit that is
the whole machine.** Two JVMs were therefore entitled to roughly 11 GiB between them on a 7.5 GiB
host, and a third — inventory-service is also Spring Boot — was about to join them. Nothing had
broken, because neither heap had ever been asked to fill; that is not a property anyone designed.

So the two Java services now carry `deploy.resources.limits.memory`: **1536m** for backend and
**1280m** for keycloak, with the arithmetic in the compose file beside each. Setting the limit is
also what re-sizes the heap — 75% of 1,536 MiB is 1,152 MiB, and 70% of 1,280 MiB is 896 MiB — so a
limit is a heap decision here as much as a memory-safety one. Keycloak's number was 1024m for one
day and was raised before it ever ran: RSS + 256 MiB is a reasonable rule for a process whose memory
is its resident set, and the wrong rule for a JVM, where the limit is the *input* to the percentage.
At 1024m the heap alone could have taken 717 MiB and left ~300 MiB for metaspace, code cache,
threads and direct buffers — an OOM kill on the one service whose restart logs every user out. `postgres`, `caddy` and `frontend` are
deliberately unlimited: none of them sizes itself from visible RAM, and a cgroup limit on Postgres
would count the page cache it depends on.

**The host had no swap, and now has 2 GB.** Until 2026-08-27 `swapon --show` printed nothing, so an
over-committed container was OOM-killed rather than slowed — survivable while nothing was near its
ceiling, and not survivable as a third JVM arrives. A 2 GB file was created as root during the
shared-host preparation and entered in `/etc/fstab` (`/swapfile none swap sw 0 0`) so it survives a
reboot:

```
NAME      TYPE SIZE USED PRIO
/swapfile file   2G   0B   -1
```

It is insurance, not capacity. Swap that is actually being used by a JVM heap is its own performance
incident; the reason it is here is that a burst which would have been a kill becomes a slowdown
somebody can observe and act on.

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

**The OPS RULES — the six hand-deploy rules cited by number throughout this repository — are defined
in [`docker/README.md`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/README.md),
section OPS RULES.** Ruled 2026-08-28: this published site carries the architecture, and operator
rules live beside the files they operate, which is docs-as-code applied rather than recited. The
subsections below are those rules *applied to this deployment*, not the rules themselves.

### Empty is not unset

Compose substitutes an **empty string** for `${VAR:-}`, and an empty string is a *set* value.
Spring's `${VAR:default}` only falls back when the variable is absent entirely. So `${VAR:-}` in
the compose file does not "leave the application's default alone" — it overrides it with `""`.

This is why the backend ran with an empty `LLM_BASE_URL` and an empty embedding model name until
2026-08-07: both had correct defaults in `application.yml` and both were being overwritten by
placeholders that looked inert. Two rules follow, and the compose file states them where they
apply:

- A variable that has a real default in `application.yml` **repeats that default** in the compose
  file (`${LLM_BASE_URL:-https://openai.inference.de-txl.ionos.com/v1}`), or is not listed at all.
- A variable that is genuinely empty by nature — a secret, or a placeholder for a feature that does
  not exist yet — keeps `${VAR:-}` and says so in a comment.

The same applies to `.env.prod`: an override you no longer want must be **deleted**, not blanked.

### An `.env.prod` edit is inert until the container is recreated

A container's environment is fixed **when the container is created**. `docker compose up -d` on an
unchanged image and an unchanged service definition leaves the existing container running, so a
corrected value in `.env.prod` is read by nothing. The only reliable way to apply it is:

```bash
docker compose -f docker-compose.prod.yml up -d --force-recreate backend
```

This is not a theoretical footnote. A backend container once ran an entire indexing pass into HTTP
401s using a revoked API token while the correct token was already sitting in `.env.prod`, edited
minutes earlier. Nothing in `docker compose ps`, in the file, or in the logs said the two disagreed
— the logs said "unauthorised", which reads as a bad key rather than as a stale one. The check that
settles it in one line:

```bash
docker compose exec backend printenv LLM_API_KEY | head -c 12   # what the process actually has
```

The same applies to a rotated token, a changed budget, and every other value in that file.

### A single-file bind mount follows the inode, so never replace it with `mv`

Two files reach their container as a **single-file** bind mount rather than as a directory:

```yaml
- ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
- ./frontend-config.json:/usr/share/nginx/html/config.json:ro
```

The kernel binds the *inode* it found at that path when the container started, not the path. `mv
new.Caddyfile caddy/Caddyfile` puts a **different inode** at the same path, and the container keeps
reading the old one — for as long as it lives.

Nothing anywhere reports this. `caddy validate` and `caddy reload` execute *inside* the container,
so both read the stale file, both succeed, and the reload logs a successful configuration change
that changed nothing. It cost a deploy round on 2026-08-08: the host file carried two
`Content-Security-Policy` lines while a `grep` inside the container found none.

```bash
f=caddy/Caddyfile; cp -pn "$f" "$f.bak.of-$(date -r "$f" +%F)"   # rollback copy
cp new-Caddyfile caddy/Caddyfile                                 # cp, NOT mv — same inode
docker compose -f docker-compose.prod.yml --env-file .env.prod exec caddy \
  caddy reload --config /etc/caddy/Caddyfile

# ...or sidestep the question entirely by giving the container a fresh view of the path:
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate caddy

# and confirm from OUTSIDE, where the answer cannot be stale:
curl -sSI https://<app host>/ | grep -i content-security-policy
```

The rule generalises: **edit in place or `cp` over it; never `mv`, and never `rm` + recreate.** An
editor that writes atomically — which most do, by writing a temporary file and renaming it over the
target — has exactly the same effect as `mv`.

### Protocol documents and the volume

`protocol.source_file` stores a path, never the document (domain-model.md keeps BLOBs out of
Postgres), so the `protocol-files` volume *is* the documents. Two things have to line up, and both
are now in the compose file:

| | |
|---|---|
| Mount | `protocol-files:/var/lib/maintenance/files` |
| `MAINTENANCE_FILES_PATH` | `/var/lib/maintenance/files` |

Without the variable the application uses its local-development default, `./data/protocol-files`,
which inside the container is `/app/data/protocol-files` — the writable layer. Documents would be
written successfully, survive a restart, and vanish on the next `compose up -d`, leaving rows whose
`source_file` points at nothing. Nothing would report an error at the time.

**Volume ownership needs no provisioning step, and here is why.** The backend runs as uid 1001, and
a fresh named volume is normally root-owned — but Docker seeds an *empty* volume from the image at
the mount path, ownership included, and `backend/Dockerfile` creates `/var/lib/maintenance/files`
owned by `app:app` before the volume is ever attached. Verified rather than assumed:

```console
# fresh volume at the path the image creates
$ docker run --rm -v v1:/var/lib/maintenance/files <image> ls -ldn /var/lib/maintenance/files
drwxr-xr-x 2 1001 1001 …                              -> writes succeed

# fresh volume at a path the image does not create
$ docker run --rm -v v2:/somewhere/else <image> touch /somewhere/else/probe
drwxr-xr-x 2 0 0 …            touch: Permission denied
```

That second case is the one to remember, because it is what a future change would look like: if the
mount path moves somewhere the image does not create, or the `mkdir`/`chown` leaves the Dockerfile,
or the volume is replaced by a **host bind mount** (Docker never chowns a host path), the container
gets a root-owned directory and every upload fails. The check and the fix:

```bash
docker compose exec backend ls -ldn /var/lib/maintenance/files    # expect 1001 1001
# only if it is not:
docker run --rm -v maintenance-assistant_protocol-files:/d alpine chown -R 1001:1001 /d
```

## 7.5 Delivery

CI owns the rollout. When `backend-ci` or `frontend-ci` succeeds on `main`, the matching deploy
workflow builds a `linux/amd64` image, pushes it to GHCR, then connects to the host as the `deploy`
user and runs `docker compose pull <service> && docker compose up -d <service>` for that one
service. Both workflows also accept a manual `workflow_dispatch`, which redeploys whatever tag
`.env.prod` currently names — the button for a config change or a stuck container.

The rollout is verified from the runner against the **public** URL rather than on the host, because
a container can be up while DNS, TLS or the proxy in front of it is broken; the job fails if the
endpoint does not come back healthy. Both deploy jobs share one concurrency group with cancelling
disabled, so two rollouts never race over the same containers and a superseded build never
interrupts one in flight.

### CI deploys images, never configuration

The pipeline runs `docker compose pull <service> && docker compose up -d <service>` against the
compose file **that is already on the host**. It never copies one there. So
`docker-compose.prod.yml` and `.env.prod` are the two files in this repository that merging does not
deploy:

| Changed in a merged PR | Reaches the host by |
|---|---|
| Backend or frontend code | CI, automatically |
| `docker/docker-compose.prod.yml` | **a person, by hand** |
| `docker/caddy/Caddyfile` | **a person, by hand** — bind-mounted from the host, same as the compose file |
| `.env.prod` (documented by `.env.prod.example`) | **a person, by hand** — it is not in the repo at all |

The failure mode is quiet and was met in practice: PR #22 fixed the file-volume wiring, merged, and
deployed nothing, because the host kept running the compose file it already had. The stack looked
healthy and was running the old definition. Applying such a change:

```bash
ssh deploy@<host>
cd /opt/maintenance-assistant
f=docker-compose.prod.yml; cp -pn "$f" "$f.bak.of-$(date -r "$f" +%F)"   # rollback copy first
# copy the new file over (scp from the workstation, or edit in place)
docker compose -f docker-compose.prod.yml up -d --force-recreate backend
```

The Caddyfile is applied the same way, and reloaded rather than recreated — Caddy re-reads its
configuration without dropping connections or re-issuing certificates:

```bash
f=caddy/Caddyfile; cp -pn "$f" "$f.bak.of-$(date -r "$f" +%F)"   # rollback copy first
# copy the new file over
docker compose -f docker-compose.prod.yml --env-file .env.prod exec caddy \
  caddy reload --config /etc/caddy/Caddyfile
curl -sSI https://<app host>/ | grep -i content-security-policy   # it either arrived or it did not
```

The `.bak` copy is the rule rather than a suggestion: this file is the only definition of the
running stack, there is no second host to compare against, and `git` on the workstation cannot tell
you what the server had a minute ago. Combine this with the recreate rule in §7.4 — a compose change
that alters `environment:` needs the recreate for the same reason an `.env.prod` change does.

CI authenticates with a dedicated ed25519 key that exists only for this purpose, held in the
`DEPLOY_SSH_KEY` repository secret alongside `DEPLOY_HOST` and `DEPLOY_USER`. It is written from the
step environment rather than interpolated into a command line, and deleted in a step that runs even
when the deploy fails.

## 7.6 Backup and recovery

Two mechanisms, covering different failures: a **nightly backup** on the host for data loss, and a
**Hetzner snapshot** for the host itself.

### 7.6.1 Nightly backup

[`infra/backup.sh`](https://github.com/Keglev/maintenance-assistant/blob/main/infra/backup.sh),
installed as `/usr/local/bin/maintenance-backup` by
[`infra/install-backup.sh`](https://github.com/Keglev/maintenance-assistant/blob/main/infra/install-backup.sh)
and driven by a systemd timer at **03:15 UTC**. It writes two artefacts to
`/opt/maintenance-assistant/backups/`, mode 0700:

| Artefact | What it is | Retention |
|---|---|---|
| `maintenance-db-YYYY-MM-DD.dump` | `pg_dump --format=custom --compress=9` of the application database | last 14 |
| `protocol-files-YYYY-MM-DD.tar.gz` | `tar czf` of the `protocol-files` volume | last 14 |

**Both are needed, and neither is sufficient.** The database stores only the *path* of an uploaded
protocol document (`protocol.source_file`); the document itself lives on the volume, because
domain-model.md keeps BLOBs out of Postgres. A database dump restored on its own gives you rows
whose `source_file` points at nothing.

For the **synthetic corpus the volume archive is redundant** — those documents are regenerated from
`backend/src/main/resources/corpus/protocols.ndjson` by re-running the seed, so the repository is
already their backup. It stops being redundant the moment the upload endpoint ships (PR 3): a file
a writer uploads exists nowhere else, and from that point the volume archive is its only
copy. The archive is in place now rather than later precisely so that switch-over needs no action.

**Design decisions worth stating:**

- **A systemd timer, not cron.** `Persistent=true` reruns a night missed to a reboot or a snapshot
  restore; cron silently skips it. Output goes to the journal under the unit name
  (`journalctl -u maintenance-backup`) rather than to a local mailbox nobody reads.
  `systemctl list-timers` states when the next run is due and `systemctl status` says whether the
  last one failed — cron offers no equivalent of either.
- **Runs as `deploy`, not root.** The job needs the Docker socket and read access to `.env.prod`,
  and that user has both already; root is required to *install* the units and for nothing else. To
  be honest about what this buys: membership of the `docker` group is effectively root on this
  host, so this is least-privilege as a habit and correct file ownership in practice, not a
  security boundary.
- **Custom format rather than plain SQL through gzip.** It is already compressed, and it is the
  only format `pg_restore` can list, verify and restore selectively. A gzipped `.sql` can be
  checked only by restoring it somewhere.
- **Every artefact is verified before it is named.** The dump is written as `.part`, checked with
  `pg_restore --list` in a throwaway container, and only then renamed; the archive likewise with
  `tar -tzf`. A file that fails its check is deleted and the run exits non-zero. So a file in the
  backup directory is one that was *checked*, not merely one that was written — and a broken backup
  shows up as a failed systemd unit that night instead of on the night it is needed.
- **Retention is "keep the newest 14", not "delete anything older than 14 days."** If the timer
  stops running, the second rule quietly expires the last good copies; the first holds them.
- **Keycloak's database is deliberately not dumped.** The realm is reproducible from the versioned
  `realm-export.json`, and the four demo users are in it. Losing it costs a re-import, not data.

Operating it:

```bash
ssh deploy@<host> maintenance-backup          # run one now
journalctl -u maintenance-backup -n 50        # what happened
systemctl list-timers maintenance-backup.timer
ls -lh /opt/maintenance-assistant/backups/
```

### 7.6.2 Hetzner snapshots

The nightly backup protects the *data*. A snapshot protects the *host* — the compose files,
`.env.prod`, Caddy's certificate store, the Keycloak database and the state of the OS itself. It is
the difference between "restore the protocols" and "have the demo working again".

**When to snapshot:**

- **Before any risky change** — an OS upgrade, a Docker major version, a Postgres image bump, an
  edit to sshd or the firewall, or anything touching Caddy's TLS configuration. Rule of thumb: if
  getting it wrong could stop you logging in or stop the certificate renewing, snapshot first. It
  takes a minute and costs cents.
- **Before a pause** (see below).
- **After a milestone** that would be tedious to rebuild — a release tag, a realm change made in the
  admin console rather than in `realm-export.json`.

**Cost model, which is the part that catches people out:** a *stopped* Hetzner server still bills in
full — stopping it saves nothing. The only way to stop paying for a server is to snapshot it and
**delete** the server; the snapshot itself costs cents per month. Recreating from a snapshot gives a
new IP, so the DNS records for both hostnames must be repointed before Caddy can renew a
certificate. Per DECISIONS.txt the standing decision is to **pay during application phases and
snapshot only in a real pause** — and never to delete the server while applications are out, because
the demo link in them has to keep working.

**Restoring** is a Hetzner console action: create a server from the snapshot, repoint DNS, and let
Caddy re-issue. The application data is then whatever the snapshot contained, which is why the
nightly dump exists alongside it — a snapshot from Sunday plus Wednesday's dump beats either alone.

## 7.7 Host hardening

`infra/provision.sh` is the record of what the host is, and is safe to re-run:
unattended security upgrades; Docker from the official repository; an unprivileged `deploy` user
that owns the stack; sshd with `PasswordAuthentication no` and `PermitRootLogin prohibit-password`
via a drop-in that survives package upgrades; fail2ban on the sshd jail.

## 7.8 Known gaps

- **Backups are on the same disk as the data.** The nightly dump and archive sit on the host they
  back up, so they survive a bad migration, a dropped table or a corrupted volume — and not a lost
  server. A Hetzner snapshot is the only off-host copy, and it is manual. **No offsite copy is
  automated**, deliberately: the corpus is synthetic and regenerable, and an offsite target means
  another credential on the host. It becomes a real gap once uploaded documents exist.
- **Restore is manual and unrehearsed as a whole.** Each artefact is verified nightly and the dump
  has been restored into a scratch database to prove it works, but there is **no automated restore**
  and no drill of the full path — new server, snapshot, DNS, then dump plus archive. Recovery time
  is therefore unmeasured rather than merely long.
- **No staging environment.** A deploy goes straight to the only host there is, so CI on `main` is
  the last gate before the demo changes under a visitor.
- **Single host, no redundancy.** Recovery is a fresh `docker compose up -d` on a new server.
- **`latest` is a moving tag.** Pinning `sha-<commit>` in `.env.prod` is the safer habit once the
  demo is being shown.
