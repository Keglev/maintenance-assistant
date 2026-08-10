# ADR-006: Answer the Insider Threat with Traceability and Moderation, Not with Approval

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-10 |
| **Deciders** | Project owner (solo) |
| **Related** | Builds on [ADR-003](ADR-003-keycloak-for-iam.md) (roles, `preferred_username`); the quantified limits it starts from are in [arc42 §11.1](../architecture/11-risks-and-technical-debt.html); implements the remediation half of NFR-2's citation promise |

## Context

Every guard this project has built so far bounds **how much** and **how fast**. The write path is
restricted to one role (DECISIONS.txt: quality, consistency, anti-garbage). PR #36 added a size cap,
a content check and a per-user rate limit, and [arc42 §11.1](../architecture/11-risks-and-technical-debt.html)
quantifies what they are worth: a saturating attacker costs well under a cent a day and needs decades
to fill the disk.

None of that addresses the threat the project owner raised from plant experience, and it is the one
that matters:

> A Schichtleiter can file a protocol that looks entirely normal and contains a wrong Massnahme.

**This is worse than profanity or volume, and it is worse precisely because of what makes the system
good.** Mode A does not paraphrase and does not hedge — it answers from the corpus and names the
protocol each claim came from. A poisoned protocol is therefore repeated faithfully, to a technician
who has been taught that a green answer with a citation is the trustworthy kind. The citation
discipline that makes a correct answer checkable makes an incorrect one credible.

It cannot be filtered. There is no size, rate, encoding or vocabulary test that separates "the seal
kit was replaced" from "the seal kit was replaced" with the wrong torque figure. The content is
plausible by construction, because the person writing it knows what plausible looks like.

## Decision

**Answer it in three parts, of which two already existed.**

### 1. Traceability — already in place, now stated as a control

`protocol.uploaded_by` records the Keycloak username of the author on every upload (ADR-003: the
token's `preferred_username`, since there is no user table to reference). Combined with Mode A's
citation discipline, every answer traces to a specific protocol, and every protocol to a specific
person. A bad answer is therefore attributable — not by investigation, but by reading two fields.

This is the half that makes the threat *bounded* rather than anonymous, and it was a side effect of
decisions made for other reasons. It is written down here as a control so that nobody removes it as
a redundant column.

### 2. Containment — already in place

NFR-7's budgets and #36's guards bound the volume of what one account can insert (~200 protocols/day
across all users, and 10 uploads/minute each). They do not stop one bad protocol; they stop a
thousand.

### 3. Remediation — this PR

The **admin** role gets its first shop-floor function: list every protocol in the corpus, read any
of them, and delete any of them. Deletion is hard and immediate — chunks, row, file — and logged at
INFO with the protocol's identity and the administrator's username.

Moderation is deliberately given to the role that **cannot write**. The Schichtleiter is the role
this decision is about; a moderation tool they could reach would let the author of a bad protocol
remove the evidence, and the audit function would be judging itself.

### Correction is delete-then-reupload, not editing

There is no edit endpoint and no edit UI, and that is the decision rather than the backlog.

An answer cites a protocol as it stood when the answer was produced. If a protocol could be edited
in place, an answer already on a technician's screen — or quoted in a handover, or screenshotted into
a ticket — would point at a source whose text had silently changed. The citation would still resolve,
which is the dangerous part: it would look verified and no longer be.

Deleting instead makes the break honest. The old citation 404s, which is a visible, checkable state.
And the replacement path costs nothing: re-ingestion is already idempotent per protocol (chunks are
deleted and rewritten), so re-uploading a corrected protocol is a first-class operation rather than a
workaround.

## Consequences

**Positive**

- The threat becomes attributable and reversible, which is what "we cannot filter it" leaves as the
  only honest answer.
- The admin role stops being an empty seat in the application.
- Deletion is auditable: who removed what, in the log, in the same identity the authorship is
  recorded in.
- No schema change, no new service, no new dependency — three endpoints and a table view.

**Negative, and accepted**

- **Deletion is irreversible.** There is no soft delete, no retention window and no undo. A
  mis-click removes a protocol permanently, mitigated only by a confirmation dialog that names the
  protocol.
- **One administrator is enough.** No four-eyes rule, so the moderation power is exactly as
  trustworthy as the single account that holds it — which for this demo is a published one.
- **Detection is manual.** Nothing surfaces a suspicious protocol; an administrator has to be
  looking. The corpus is 150 protocols and paged ten at a time, which is reviewable; at ten thousand
  it would not be.
- **The window stays open.** A bad protocol is citable from the moment it is indexed until someone
  removes it. This decision shortens that window; it does not close it.

## What is deliberately deferred

Stated plainly, in the same spirit as ADR-005's rejection of the BFF: these are the right answers in
an enterprise, and the wrong amount of machinery for this system today.

| Deferred | What it would add | Why not now |
|---|---|---|
| **Draft / approve workflow** — technician writes, Schichtleiter approves before indexing | Closes the window entirely: nothing is citable until a second person has read it | A second state on every protocol, a review queue, a notification path, and a role that must be present for the corpus to grow at all. It also contradicts the reason write access was narrowed in the first place (DECISIONS.txt): the bottleneck was accepted deliberately. The right evolution, and a work package rather than a feature. |
| **Four-eyes deletion** — a second administrator confirms | Removes the single-admin trust assumption above | There is one administrator. A quorum of one is theatre. |
| **Soft delete with retention** — hide, keep for N days, then purge | Makes a mis-click recoverable and keeps a forensic trail | A `deleted_at` column, a retention job, and every query in the system growing a filter it must never forget — a filter that, forgotten once, makes a "deleted" protocol citable again. That failure is worse than the mis-click it prevents. Reconsider when deletion is frequent enough to be routine. |
| **Automated suspicion signals** — flag protocols that contradict others about the same machine | Turns manual review into triage | Needs a notion of contradiction the system does not have. Closer to a research question than a feature. |

## Alternatives considered

- **Restrict writing further** (e.g. only an admin may upload). Rejected: it does not address the
  threat — it renames the role that can carry it out — and it removes the feature the shop floor
  actually needs.
- **Trust the role and do nothing.** Rejected: the write restriction was already justified as a
  quality control, and a quality control with no remediation path is a policy rather than a
  mechanism.
- **Version protocols instead of deleting** (keep every revision, cite a specific one). The most
  correct answer, and it makes editing safe by making citations point at immutable versions. Rejected
  for now on proportion: it is a schema change, a citation-format change and a retrieval change, to
  solve a problem this demo does not yet have. It is the natural successor if editing is ever wanted.
