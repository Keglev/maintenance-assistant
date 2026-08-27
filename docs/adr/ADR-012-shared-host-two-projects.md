# ADR-012: Run two portfolio projects on one host, and say what that costs

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-27 |
| **Deciders** | Carlos Keglevich |
| **Related** | ADR-001 (modular monolith — the same proportionality argument, one layer down), PR #119 (the Caddy site block and the memory limits), PR #120 (the hand-deploy record), `docs/DECISIONS.txt:995-1011` (memory limits) and `:1012-1026` (the privilege split), `docker/docker-compose.prod.yml`, `docker/caddy/Caddyfile`, `infra/provision.sh` |

## Context

One Hetzner CX33 — 4 vCPU, 8 GB RAM, 75 GB disk, Nuremberg — has run
**maintenance-assistant** since 2026-08-06. Since 2026-08-27 it also serves
**[inventory-service](https://github.com/Keglev/inventory-service)**, the SmartSupplyPro backend:
its Caddy already terminates `api.smartsupplypro.de` and proxies to `ssp-backend:8081` (#119),
hand-deployed and verified in #120, where the hostname answers 502 over a valid certificate until
the container exists.

Both are **portfolio deployments**. They exist to be opened by a recruiter or a hiring engineer and
to be read as evidence. Neither has a paying user, an SLA, or anyone on call.

The decision is not whether the second project *can* run here — it demonstrably does. It is whether
running it here is a defensible engineering position or an economy that has not been priced. This
record prices it.

## Decision

**One host, one Caddy, one compose stack per project.**

| | `/opt/maintenance-assistant/` | `/opt/smartsupplypro/` |
|---|---|---|
| compose file | hand-deployed | **delivered by CI** |
| `.env.prod` | hand-deployed | hand-deployed |
| `caddy/` | hand-deployed, **holds both projects' routing** | — |

The two directories are otherwise independent: separate databases, separate volumes, separate
lifecycles, separate repositories, separate pipelines.

**Caddy belongs to project 1 and carries project 2's site block, and that asymmetry is deliberate.**
It is the single coupling between the two directories. The alternative — a third compose project
owning a shared proxy — would have made both applications depend on a stack neither repository
owns, and would have needed its own deploy path, its own hand-deployed config and its own place in
two runbooks. One asymmetric edge is cheaper to hold in the head than a symmetric third thing.

**Memory limits are set per JVM container, from the heap ceiling the runtime will produce, not from
the resident set it happens to show** — `docs/DECISIONS.txt:995-1011`. A JVM sizes its heap as a
percentage of visible memory, so an unlimited container on a shared host is one process entitled to
most of the machine; the limit is the input to that percentage. backend `1536m` (75% → 1,152 MiB
heap), keycloak `1280m` (70% → 896 MiB). `postgres`, `caddy` and `frontend` stay unlimited, because
none of them sizes from visible RAM.

**Root creates and hands over; `deploy` operates** — `docs/DECISIONS.txt:1012-1026`. `/opt` is
root-owned and `deploy` is deliberately not in sudoers (`infra/provision.sh`), so a second project's
directory takes exactly one root action to exist plus a `chown`, and everything afterwards belongs
to the unprivileged account. Exercised for an OS change for the first time in the maintenance window
of 2026-08-27 20:00–20:10 UTC.

### Why

**Cost, and these are portfolio deployments with no paying user.** That is the whole reason, in that
order, and it is written first so nobody later reconstructs a technical justification that was never
the point. A second VPS is a recurring charge against two projects that need to be *reachable and
correct*, not *available*. The engineering content of this decision is not the sharing — it is
being explicit about what the sharing costs and what would end it.

## Consequences

**Positive**

- One machine to provision, patch, back up and reboot. Host maintenance is one window covering both
  projects instead of two outages on two schedules — first exercised 2026-08-27, kernel 7.0.0-29 →
  7.0.0-30 (see `docs/PROJECT-PHASES.txt`, MAINTENANCE WINDOW).
- One Caddy, so one certificate account, one ACME configuration, one place where TLS can be wrong.
- The second project inherits a hardened host: key-only SSH, fail2ban, the unprivileged deploy
  account, and a nightly backup that already exists.

**Negative — the accepted risk, stated plainly**

- **One blast radius.** A kernel reboot, a host fault or a disk failure takes **both domains down
  together**. There is no scenario in which one survives the machine.
- **A memory incident in one project degrades the other.** The limits bound each JVM's heap; they do
  not stop a runaway container from making the box slow for everyone on it, and the 2 GB swap file
  added on 2026-08-27 turns some kills into slowdowns rather than removing them.
- **A quieter coupling, and the one most likely to bite: project 2's routing lives in project 1's
  `caddy/Caddyfile`.** A Caddyfile mistake made while deploying maintenance-assistant can take
  `api.smartsupplypro.de` offline. **The reverse is not true** — nothing in `/opt/smartsupplypro/`
  can break this project's routing. The asymmetry is the price of not building a third stack, and it
  means a maintenance-assistant deploy is, silently, a change window for the other project too.
- The Caddyfile is hand-deployed (OPS RULE 1), so project 2's routing is not covered by project 2's
  pipeline — or by anyone's.

**Mitigations already on record**, none of them added by this ADR:

- the per-container memory limits above;
- the privilege split, so an ordinary deploy cannot reconfigure the machine;
- **host maintenance is done in one window covering both projects**, which is what makes the shared
  blast radius a schedule rather than a surprise.

## Reversal conditions

This decision stops being acceptable at any one of:

1. **A paying user or an SLA on either domain.** Shared failure is a cost somebody is now owed.
2. **A second operator.** The privilege split is a written rule between one person and their own
   future self; with two people, one project's deploy touching the other's routing needs a boundary
   an agreement cannot provide.
3. **A measured incident in which one project took the other down.** Not a near miss and not an
   argument — an incident, recorded.

Then the second project moves to its own host, **and the site block in `docker/caddy/Caddyfile` is
the only thing here that has to change.** That is deliberate: the reversal was kept to one file
before the arrangement was accepted, because a coupling you cannot cheaply undo is not a coupling
you should agree to.

## Alternatives considered

- **A second VPS, one project each.** No shared blast radius, no shared Caddyfile, and the reversal
  conditions above would all be answered in advance. It loses on cost against two projects with no
  paying user, and on operational surface: two hosts to patch and reboot is the recurring price, and
  neither project is large enough to earn it. *Rejected — revisit at any reversal condition.*
- **A third compose project owning a shared reverse proxy.** Symmetric, and it removes the awkward
  fact that one project's repository holds the other's routing. It loses because it makes both
  applications depend on a stack neither repository owns: a new deploy path, a new hand-deployed
  config, a new entry in two runbooks, and a third thing to remember during an incident. The
  asymmetry it removes is cheaper to document than the component it adds. *Rejected.*
- **Managed hosting for the second project** (a PaaS free or hobby tier). Removes the coupling
  entirely and costs nothing at first. Rejected on the same ground as ADR-002's provider reasoning:
  the demo has to stay reachable and predictable, and a free tier that sleeps, rate-limits or
  changes terms is a demo that fails while somebody is reading the CV that points at it.
  *Rejected.*
- **Do nothing and leave it undocumented.** The arrangement already worked on 2026-08-27, so this
  ADR changes no behaviour at all. Rejected because an undocumented shared blast radius is a
  decision nobody made: the next person to touch that Caddyfile would have no way to know that a
  second domain depends on it. *Rejected.*
