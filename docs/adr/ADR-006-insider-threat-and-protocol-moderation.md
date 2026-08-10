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

---

## Revision — 2026-08-10: editing is allowed, deletion becomes an archive

*Appended, not rewritten. Everything above is what was decided and shipped in #37 and is left
standing; this section records what changed, and why the reasoning above does not survive contact
with the two things it did not weigh.*

### 1. Editing, previously rejected, is now the primary correction path

The argument against editing was: *an answer cites a protocol as it stood when the answer was
produced, so an edit makes a citation that still resolves and is no longer what was read.*

**What that argument assumed, and the system does not do: it assumed answers are stored.** They are
not. Every answer is generated per query, from a retrieval that runs at that moment, and a citation
is resolved live against the protocol the technician is looking at. There is no answer table, no
answer history and no shareable answer URL — the query cache is keyed by question and role and
expires, and it is a cost control, not a record. So the population of "answers pointing at text that
changed underneath them" is not the corpus of everything the system has ever said. It is *the
answers currently open on a screen*.

**That residual is real and is not being explained away.** A technician holding a screenshot, or a
tab open since before the correction, can still read a claim beside a protocol that now says
something else. Three things make that acceptable where the original framing did not:

- The direction of the change. An edit exists to replace a **wrong Massnahme with a right one**. The
  stale reader is holding the wrong version either way; the edit is what makes every subsequent
  reader hold the right one, and the previous rule made that improvement impossible to deliver
  without first destroying the protocol.
- It is recorded. Every edit writes a `moderation_event` row — who, when, and a **mandatory
  comment saying why**. "The text changed and nobody can say when or by whose hand" was the actual
  danger; a corrected protocol with a dated, attributed, explained edit is the opposite of that.
- It re-indexes. An edit that changed the file but left the old chunks in place would be strictly
  worse than no edit at all: retrieval would keep matching the wrong text while the document read
  correctly. **Re-indexing is therefore not a step in the flow, it is a condition of the flow** —
  the same delete-then-write path an upload takes, so the vector the search uses is always the
  vector of the text on screen.

**Real-plant rationale, which is what tipped it.** A wrong torque figure is corrected in seconds by
the person who spots it. Under the old rule the correction was: delete the protocol, tell the
Schichtleiter, have them retype the whole thing and re-upload it. That is not a stricter process —
it is a process expensive enough that the wrong figure stays in the corpus instead.

#### Machine and protocol type are not editable

Title, fault code and the text are words about an event. **Machine identity is not a property of a
protocol, it is its provenance** — it is what the retrieval filter runs on, what the citation names,
and what makes the protocol evidence about *that* press rather than about presses in general.
Re-attributing a defect to another machine is not a correction; it is a different protocol wearing
this one's history, and it would silently move a record out of one machine's evidence and into
another's without the second machine's uploader ever touching it. Protocol type is the same shape of
claim one level down: a classification, not content.

The rule in one line: **words can be fixed, identity cannot.** If the machine is wrong, the protocol
is wrong at its root, and the path is delete plus a fresh upload — which now costs an archive entry
rather than a hole. A request that tries to change either field is **refused with a 400 and a stable
code**, not silently ignored: a client that thought it was moving a protocol and got a 200 back
would be told it succeeded.

### 2. Hard delete becomes a soft delete that is an audit archive

#37 deleted the chunks, the row and the file. That removed the bad protocol and, with it, **the
evidence that it had ever existed** — which is the one thing the insider-threat chain cannot afford
to lose. The chain this ADR is built on is *create → detect → trace → remediate*, and it was missing
its last link: **preserve**. An administrator who cleans up a poisoned protocol and thereby erases
the record of who filed it has completed the attacker's work for them.

Deletion now: chunks are deleted (unchanged, and it is the load-bearing step), `deleted_at` is set,
and a `moderation_event` row records the actor and a **mandatory comment**. The row and the file
stay. The protocol is dead to every read path in the application except one admin archive view.

**This is an archive, not a recycle bin, and the difference is the whole design:**

- **Removal is instant and total for every role.** A soft-deleted protocol leaves retrieval,
  the protocol list, "Meine Uploads" and the shop-floor document endpoint in the same request that
  deleted it.
- **There is no restore, by design.** No endpoint, no UI, no flag. Undelete would make the archive a
  staging area for putting bad protocols back, and would give the moderation power a reverse gear
  its own audit trail cannot describe. Correcting a protocol is what editing is now for; the archive
  is for looking at what was removed, not for reversing it.
- **The admin can still read it**, content and all, which is what makes it evidence rather than a
  tombstone.
- **It is capped at 50 per machine.** Past that, the oldest deletions are purged completely — row and
  file — because an archive that only grows is a disk that only fills, and this application already
  bounds every other unbounded thing it has (NFR-7). The `moderation_event` rows are *not* purged
  with them: see below.

#### The objection this ADR raised against soft delete, answered

The deferred-items table above rejected soft delete on a specific and good ground:

> …every query in the system growing a filter it must never forget — a filter that, forgotten once,
> makes a "deleted" protocol citable again. That failure is worse than the mis-click it prevents.

That objection stands, and it is why **the chunks are still deleted.** Retrieval is not protected by
a `deleted_at` filter it could forget; it is protected by there being nothing left to retrieve. The
`WHERE deleted_at IS NULL` guards on the row-based reads are the second line, and the failure they
guard against is a deleted protocol appearing in a *list* — visible, embarrassing and harmless —
rather than being cited in an answer. The dangerous failure mode was designed out; the survivable
one is filtered.

The other half of the original objection — the retention job — did not survive either, and did not
need to: the cap is enforced synchronously by the delete that breaches it. There is no scheduler, no
timer and no state that can silently stop running.

#### Why `moderation_event` outlives the protocol

`moderation_event` carries no foreign key to `protocol`, deliberately. When the cap purges the
oldest deletions, the ledger entries stay. A cascade would mean the fifty-first deletion erased the
record of the first — **the audit function losing its own audit trail**, which is the exact failure
this revision exists to prevent. The purge is logged at INFO with the protocol's identity, so the
human-readable "what was it" survives next to the machine-readable "who removed it and why".

### Consequences of this revision

**Positive**

- Correction is now cheap enough to actually happen, and every correction is attributed and
  explained.
- Removing garbage no longer destroys the evidence of who produced it.
- Retrieval's safety property is structural (no chunks) rather than dependent on a filter.

**Negative, and accepted**

- **A stale open answer can still cite corrected text.** Narrowed, not eliminated. Versioned
  protocols remain the correct fix and remain deferred, for the same proportion reasons as above.
- **The archive is a second place bad content lives.** Only an administrator can read it, and it is
  out of retrieval entirely, but it is not nothing.
- **The cap destroys old evidence.** Fifty deletions per machine is generous for this plant and
  arbitrary in principle; a machine under sustained attack would roll its oldest evidence out of the
  archive. The `moderation_event` ledger is what survives that, which is why it is not cascaded.
- **Still one administrator.** Editing hands that single account the power to change what the corpus
  says, not only to remove from it. Four-eyes remains deferred and is now a larger gap than it was.
