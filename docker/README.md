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

## OPS RULES

Authoritative since 2026-08-27 (#127); until then rules 1-5 were recorded in PROJECT-PHASES.txt and
are now in `docs/ledger/STATUS-2026-08-08.txt` as history.

Six rules, each bought by an incident and each carrying what it cost. Eighteen citations by number in
nine other files resolve here; this section is what they resolve to. The wording of 1-5 is the
wording they were written in — copied, not restated, because a rule reworded is a rule re-decided,
and these were decided by incidents rather than by editing.

- **OPS RULE 1** (learned the hard way, cost ~12 min): the deploy pipeline
  pulls IMAGES ONLY — it never syncs docker-compose.prod.yml or
  .env.prod to the host. Any merged change to those files must be
  applied on the server BY HAND before/with the next deploy (#22 was
  merged in git but absent on the host until an ops session copied
  it; rollback copy at docker-compose.prod.yml.bak-20260807-162246).
  DOCS DEBT: DONE — arc42 07, "CI deploys images, never configuration",
  including the .bak rule.
- **OPS RULE 2:** an .env.prod edit is INERT until the container is
  recreated (`up -d --force-recreate backend`); env freezes at
  container creation. A container once ran 150 calls into a 401 with
  a stale key while the file was already correct. DOCS DEBT: DONE —
  arc42 §7.4, "An .env.prod edit is inert until the container is
  recreated", with the printenv check.
- **OPS RULE 3** (learned 2026-08-08, cost one failed deploy round): the
  prod compose bind-mounts the Caddyfile as a SINGLE FILE
  (./caddy/Caddyfile:/etc/caddy/Caddyfile:ro), and a single-file bind
  mount tracks the file's INODE, not its path. Replacing the host file
  with `mv` gives it a NEW inode, and the container keeps reading the
  OLD one. Nothing reports this: `caddy validate` and `caddy reload`
  run INSIDE the container and both happily operate on the stale
  content, and the reload reports success. Measured: a grep inside the
  container found 0 CSP lines while the host file had 2. REMEDY: `cp`
  OVER the existing file (same inode), or
  `up -d --force-recreate caddy`. Never `mv`. The same trap applies to
  any single-file bind mount — frontend-config.json is the other one
  in this stack. DOCS DEBT: DONE — arc42 §7.4, next to the .env rule.

  APPLIED TO THIS STACK — the four single-file mounts, rows 3, 4, 5 and 7 of the table in
  *Files the pipeline never deploys* below. A single-file bind mount tracks the file's INODE,
  not its path: replacing the host file with `mv` gives it a new inode and the container keeps
  reading the old one, silently — `caddy validate` and `caddy reload` run INSIDE the container
  and both happily report success against stale content. **Always `cp` OVER the existing file**,
  or `up -d --force-recreate <service>`. Never `mv`. The theme (row 6) is a directory mount and
  does not carry the trap, though theme caching means it still needs a force-recreate to take
  effect.
- **OPS RULE 4** (learned 2026-08-27, cost one root console mid-deploy):
  BEFORE A HAND-DEPLOY, `ls -l` THE TARGET. The deploy account has no
  sudo, so a root-owned file on the host CANNOT be replaced from it, and
  the failure arrives halfway through a session rather than at its
  start. docker-compose.prod.yml was found root:root 644, sixteen days
  after a root session created it and did not hand it over — invisible
  until a deploy finally needed to write that file. A file that is not
  deploy:deploy needs the root console, and gets `chown deploy:deploy`
  while you are there. DOCS DEBT: DONE — docker/README.md, above the
  OPS RULE 3 note, with the ls -l command.
- **OPS RULE 5, THE SHARED-HOST RULE** (2026-08-27): ROOT CREATES AND HANDS
  OVER, deploy OPERATES. /opt is root-owned, and `deploy` is not in
  sudoers at all — infra/provision.sh builds it that way on purpose
  (adduser --disabled-password, passwd -l, docker group, no sudoers
  entry). So a second project's directory needs exactly one root action
  to exist, plus a chown, and everything after that is deploy's. This is
  the design working rather than a gap: the account that runs containers
  every day is not the account that can rewrite the machine.
  NOTE THAT docker GROUP MEMBERSHIP IS ROOT-EQUIVALENT in practice, and
  the rule stands anyway — a boundary that is trivially crossable is
  still the written statement of who is supposed to do what, and using
  the docker socket to sidestep sudoers is how an operator surprises the
  next one.

**OPS RULE 6 — A BACKUP IS NAMED FOR ITS CONTENT DATE, NOT FOR THE DAY IT WAS COPIED.**

```bash
f=docker-compose.prod.yml; cp -pn "$f" "$f.bak.of-$(date -r "$f" +%F)"
```

The name is `<file>.bak.of-<YYYY-MM-DD>`, and the date comes from the SOURCE FILE'S mtime
(`date -r FILE +%F`) — never from today. `cp -p` preserves that mtime onto the copy, so the name
and the timestamp on disk agree and a later `ls -l` corroborates the filename instead of
contradicting it. Two backups of the same content taken on different days collide by design;
`cp -n` refuses the second, which is the right answer, because they are the same bytes.

**Why, measured 2026-08-27.** The host held `.env.prod.bak-2026-08-27`,
`docker-compose.prod.yml.bak-2026-08-27` and `Caddyfile.bak-2026-08-27` whose contents were from
**2026-08-19, 2026-08-11 and 2026-08-08**. Every one of them was named for the day somebody ran
`cp`, and every one of them was wrong about what it contained. **A backup named for the wrong day is
restored with confidence**, which is worse than no backup at all — the operator does not stop to
check, because the filename already answered the question.

NUMBERED 6 BECAUSE 4 AND 5 ARE TAKEN: OPS RULE 4 is "before a hand-deploy, `ls -l` the target" and
OPS RULE 5 is the shared-host rule, both written 2026-08-27. Rule 6 was written straight into this
file on 2026-08-27 because rules 1-5 had no home to be written into; that is what this section
fixes.

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
| 3 | `caddy/Caddyfile` | TLS, routes, security headers — and one site block for a FOREIGN project, see below | single file |
| 4 | `frontend-config.json` | the SPA runtime configuration | single file |
| 5 | `keycloak/realm-export.json` | realm, roles, clients | single file |
| 6 | `keycloak/themes/wartungsassistent/` | the login theme | directory |
| 7 | `postgres/init-keycloak-db.sh` | runs once, on an empty data directory | single file |

**THEY ARE OWNED BY `deploy`, AND ONE OF THEM WAS NOT.** The account that runs `docker compose` on
the host is `deploy`, and it has **no sudo** — that is `infra/provision.sh` working as designed, not
an omission. So every file above has to be `deploy:deploy` for a hand-deploy to be possible at all.
On 2026-08-27 `docker-compose.prod.yml` turned out to be `root:root 644`, and had been since the
first manual deploy on 2026-08-11: it was created by a root session and never handed over. Nothing
reported it for sixteen days, because nobody had needed to overwrite that file since. It was
corrected the same day (`cp` as root, then `chown deploy:deploy`).

**So the first step of a hand-deploy is `ls -l`, not `cp`:**

```bash
cd /opt/maintenance-assistant
ls -l docker-compose.prod.yml caddy/Caddyfile .env.prod frontend-config.json
```

A file that is not `deploy:deploy` cannot be replaced from the deploy account. It needs the **root
console** — and while you are there, `chown deploy:deploy` it, so the next person does not meet the
same wall. **Root creates and hands over; `deploy` operates.**

**OPS RULE 3 applies to rows 3, 4, 5 and 7** — the four single-file mounts. The inode trap, the
remedy and why the theme (row 6) escapes it are stated once, under OPS RULE 3 above.

### caddy/Caddyfile

TLS, routing and the security headers, for **three** hostnames — and the third one is not this
project's.

`api.smartsupplypro.de` proxies to `ssp-backend:8081`, a container belonging to
[inventory-service](https://github.com/Keglev/inventory-service), which runs on the same host as a
second compose project under `/opt/smartsupplypro` and joins this project's network
(`maintenance-assistant_default`) so the name resolves. Caddy is the only thing the two projects
share; see [arc42 §7.1, "Shared host"](../docs/arc42/07-deployment-view.md).

Three things follow, and all three have bitten somebody somewhere:

- **The foreign site block is edited HERE and deployed BY HAND**, like every other line in this
  file. Committing it changes nothing on the server. inventory-service's own pipeline does not
  deploy it either — it has no reason to know this file exists.
- **A 502 on `api.smartsupplypro.de` is the expected answer when inventory-service is down.** Caddy
  still holds a valid certificate for the name, because the site block is what makes it ask for one.
  A certificate error means the block is missing or the file is stale; a 502 means the block is
  live and the upstream is not.
- **Removing inventory-service from this host means removing the block.** A `reverse_proxy` to a
  name that no longer resolves fails at request time, not at reload, so `caddy validate` will keep
  reporting a valid configuration while the site returns 502 forever.

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
f=frontend-config.json; cp -pn "$f" "$f.bak.of-$(date -r "$f" +%F)"
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
