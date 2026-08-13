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

---

## Note — 2026-08-10: approval and duplicate detection, deferred to v1.2

*A dated note, not a revision: nothing above changes. This records two decisions taken with the
project owner about what comes next and what shape it will have, so that v1.2 starts from a position
rather than from a blank page.*

### Approve workflow — deferred, and the trust chain has changed shape

The deferred-items table above sketches draft/approve as "technician writes, Schichtleiter approves
before indexing". That sketch is superseded here, because it put the author and the approver one
step apart and left the Schichtleiter approving their own corrections. **The chain for v1.2 is three
roles deep:**

| Role | May |
|---|---|
| **Techniker** | Write a protocol. **Never edit their own.** |
| **Schichtleiter** | Edit any protocol, and forward it for approval. |
| **Admin** | Approve — and may close a protocol after editing it. |

**The rule that makes it worth building: the author and the approver are never the same person.**
That is the four-eyes principle applied where it actually bites — not to deletion (there is one
administrator, and a quorum of one is theatre) but to the moment a protocol becomes citable. It also
answers the objection the original ADR raised against restricting writing further: this does not
rename the role that can carry the threat out, it puts a second pair of eyes between the threat and
the corpus.

A protocol's approval state then becomes a **search facet**: "approved protocols only" is a filter
that follows from the state existing, not a feature to design separately.

What it costs, and why it is not in this PR: a second state on every protocol, a review queue, a
notification path, and a role that has to be present for the corpus to grow at all. It is a work
package, and it wants the edit-with-re-index path this PR just built underneath it.

### Duplicate detection on approval — deferred, and it must warn rather than block

On approval, run the **existing** pgvector similarity of the new protocol against the protocols of
the same machine. High similarity raises a **warning with links to the neighbours it found**. The
administrator decides: join them, keep both, or delete one.

**It must never block, and the corpus already contains the proof.** The E-47 demo seed is four
protocols on Presse 3 with the same fault code and *four different root causes* — every one of them
legitimate, and any similarity threshold high enough to be useful would flag them as duplicates of
each other. A blocking check would refuse the single most valuable case in the corpus. A warning
costs a glance and catches the case it is actually for: the same protocol filed twice.

It reuses what exists — the embeddings are already stored, the machine filter is already a column on
`chunk` (ADR-004) — so the cost is a query and a screen, not a new mechanism. It is deferred with
approval because that is the moment it belongs to: a duplicate warning is only actionable while
someone is already deciding whether the protocol should exist.

---

## Revision — 2026-08-13: the trust chain, as implemented

The 2026-08-10 note above described a plan. This describes what was built (v1.2, PR 1 of 2: backend
and data; the interface follows in PR 2). Carlos's three decisions of 2026-08-11 are the input, and
they are decisions rather than options.

### Approval is a status, and an unapproved protocol stays searchable

In Carlos's words: **the admin may not review at a weekend, and the factory does not stop.** Gating
retrieval on approval would mean the newest knowledge in the plant — the protocol about the fault
happening right now — is exactly the knowledge search cannot see. So approval changes what the
system can SAY about a source, not whether the source can be found.

**The trade-off is accepted, not hidden: an unapproved protocol is less reliable to troubleshoot
from.** That is why the state is returned everywhere a protocol appears — the moderation list, the
caller's own uploads, and the citations on an answer. It has to be on the CITATION in particular,
because NFR-2's discipline (every claim names its source) is what makes a Mode A answer checkable,
and it has the side effect of making any cited claim look verified. An unmarked unreviewed source
would be the worst of both worlds. PR 2 renders the mark; this PR guarantees the data is there.

`approvedOnly` exists as a query parameter for a caller who wants only the reviewed subset. It
defaults to false at every caller, and a test asserts that unapproved protocols are still indexed
and still retrievable — because this is the decision most likely to be "fixed" by accident later.

### The schema: a state, not a boolean

`protocol.approval_state` is a check-constrained `varchar` (`UNAPPROVED` | `APPROVED`) beside
`approved_by` and `approved_at`. A boolean was the obvious first answer and lost on three counts:
`approved = false` conflates "nobody has looked at this" with "somebody looked and withdrew"; the
vocabulary is written down where a reader of the schema can see it; and it matches how this schema
already spells a small closed set (`protocol.status`, `moderation_event.action`). The cost — a
constraint to alter if the set ever grows — is only paid if it grows, and a boolean would have
needed a second column in that case anyway.

A constraint enforces that **an APPROVED row carries an approver and a time, and an UNAPPROVED row
carries neither**. The first half is this ADR expressed as SQL: an approval without an actor is not
an audit record. The second stops the columns describing a state the protocol is no longer in; the
history lives in `moderation_event`, which gained `APPROVE` and `UNAPPROVE`.

**Existing rows.** The 150 seeded protocols become APPROVED in the migration, identified by their
deterministic id prefix, with the actor `system:corpus-seed` — a name that is not a Keycloak user
and cannot be mistaken for one, because no human read them. **Everything else already in a live
database stays UNAPPROVED**, which is the honest answer for production's test uploads: the migration
cannot know whether anyone reviewed them, and approving them would fabricate the unearned trust the
flag exists to expose. It costs nothing, because unapproved protocols remain searchable; they simply
appear in the queue on the first login after deploy, which is where a handful of unreviewed test
uploads belong.

### Four eyes, enforced on the act

Decision 3: the Techniker writes and never corrects, not even their own protocol; a correction is
requested from the Schichtleiter, who performs it; the Admin approves. Three people.

Two permissions moved to make that true, and both are widenings of what the code did before:

- **The Techniker may now file a protocol.** They are the person standing at the machine. Requiring
  them to dictate it to a Schichtleiter is how a plant ends up with protocols written by someone who
  was not there — or with no protocol at all.
- **The Schichtleiter may now correct one.** Until now correcting belonged to the administrator
  alone, which made the corrector and the approver the same role and collapsed four eyes to two.

**The administrator KEEPS the edit they have had since #39.** Removing a power silently while adding
another would change the chain in a way nobody asked for. That leaves one hole — an admin could edit
and then approve their own correction — and it is closed where it can actually be closed:
`ProtocolApprovalService` refuses an approval by whoever **filed** the protocol or **last corrected**
it, checked against `uploaded_by` and the newest `EDIT` event rather than against a role. Roles
cannot express "the same human did both"; the ledger can.

Approving is **idempotent**: it asserts a state rather than performing a transition, so a
double-clicked button is not an error and writes no second audit row. A comment is **required to
withdraw** approval and optional to grant it — approving affirms the text as it stands, while
withdrawing takes back something a named person vouched for, and the next reader is owed the reason.

### Editing an approved protocol resets it — the rule the chain rests on

A correction after approval is text nobody has reviewed. Leaving the flag set would mean the
approval vouches for words the approver never read, which is precisely the failure this ADR's
original refusal of editing was worried about. So an edit sets the protocol back to UNAPPROVED,
unconditionally.

Unconditionally, because "only reset if the text really changed" makes the guarantee depend on a
diff, and a whitespace or encoding difference deciding whether a review still counts is not a rule
anyone can reason about. Every edit already forces a re-index for the same reason. The protocol
stays searchable throughout; it simply stops claiming to be reviewed, and returns to the queue.

The edit writes **two** ledger rows when it withdraws an approval — the `EDIT` and an `UNAPPROVE` —
so a reader does not have to know the reset rule to see that the approval ended there. Both carry
the same timestamp, because `now()` in Postgres is the transaction's: they are one atomic act, and
presenting them as simultaneous is accurate rather than lossy.

### What this revision does not do

No interface (PR 2), no duplicate detection on approval (still deferred, unchanged from the note
above), and no change to who may delete. The approval queue has no assignment, no due date and no
reminder: it is a filter on a list, which is the smallest thing that makes the state actionable.

---

## Revision — 2026-08-13: Option B, and the interface's obligations

Two things changed after the revision above was implemented and reviewed. One is a permission
Carlos reversed; the other is the interface that PR 1 deliberately left for PR 2.

### The administrator stops editing — the role split is the rule, the ledger check is the belt

The revision above left the admin the edit they had held since #39 and closed the resulting hole
with a check on the act: `ProtocolApprovalService` refuses an approval by whoever filed the protocol
or last corrected it. That works, and it describes the chain with two rules of different kinds — a
role split for two of the three steps and a ledger lookup for the third.

**Carlos chose the clean chain: Techniker writes, Schichtleiter corrects, Admin approves, and nobody
holds two of those jobs.** `PUT /api/moderation/protocols/{id}` is now SCHICHTLEITER-only, and an
administrator's attempt is refused 403 before the service is reached. It is the same guarantee said
once, in the place a reader of the controller sees it.

**The ledger check stays, deliberately, and its comment now says why rather than describing the
decision that was reversed.** It is the only guard that can express "the same *human* filed and
approved this", which a role cannot; it is the only one that survives a future role widening; and a
rule enforced in exactly one place is one annotation edit away from being gone. It costs one query
on an act that happens rarely. Its corrector branch is unreachable through the API as it stands —
stated where the branch is, so the next reader does not have to work it out.

Every comment that explained why the admin *kept* edit is gone rather than amended. A comment
describing a decision that was reversed is the failure mode this ADR itself demonstrated once.

**REPORTED, NOT FIXED: the corrector cannot reach the correction screen.** `/moderation` is guarded
by `roleGuard('admin')` in `app.routes.ts`, and the Bearbeiten button lives on that view. So the
correction path currently has no interface for anybody: the admin has the screen and not the
permission, the Schichtleiter has the permission and not the screen. The API is correct and the
button is rendered for the right role; widening the route is a permission decision that belongs to
Carlos and was not taken inside a UI pull request. It is named in PROJECT-PHASES so it cannot be
inherited as a mystery.

### What the interface must say, and why it is not optional

Decision 1 of 2026-08-11 keeps an unapproved protocol searchable and citable and accepts the cost:
it is less reliable to troubleshoot from. **The whole of what is given in return is that the state is
visible.** NFR-2's citation discipline — every claim names its source — is what makes a Mode A answer
checkable, and it has the side effect of making any cited claim *look* checked. An unmarked
unreviewed source would be that discipline working against itself.

So the obligations are:

- **Every source card carries its state**, in a word and an icon as well as a colour. Approved is
  deliberately quiet; unapproved is a chip that can be found in a list of five. A state told only in
  colour is a state some readers do not get, which is the rule the two answer modes and the footer
  health dot already follow.
- **The answer says it once, at answer level, when any cited source is unapproved.** A technician
  acts on the answer, and on a tablet the sources may not be on screen at all — having to audit five
  cards to discover that one is unreviewed is not being told. It is a LINE, not a banner: an
  unapproved source is an ordinary decided state, and a warning panel would say the answer is
  defective.
- **The viewer repeats it in its head**, before the text rather than after it. A reader who works
  through a protocol and only then learns nobody reviewed it has been told too late to act on it.
- **The Verwaltung table carries who approved and when**, on every row and not only on the queue —
  it is where an administrator checks that something they approved is *still* approved, and after a
  correction it will not be.

The approve control has no confirmation and no comment: approving affirms the text as it stands, and
the reviewer has just read it. Withdrawing has both, because it takes back what a named person
vouched for. **The four-eyes refusal is answered with the rule in plain language, beside the row it
applies to** — a disabled button, or a generic "the request failed", leaves an administrator staring
at a control that does not work for a reason they cannot discover.

The approved-only search facet defaults to OFF, which is decision 1 rather than a convenience.
Narrowing retrieval can leave nothing above the threshold, and the backend then answers Mode B
exactly as it would for a genuine gap in the corpus — so the interface says which of the two
happened and offers the way back. Without that line a reader concludes the plant has no protocol on
the fault, when what happened is that theirs is not signed off yet.

### Still not done

Duplicate detection on approval (PR 3, unchanged from the note above). No assignment, no due date and
no reminder on the queue: it is a filter on a list, which is the smallest thing that makes the state
actionable.

---

## Revision — 2026-08-14: the corrector gets a door, and what it does not open

**For one release the correction path had no interface for anybody, and that is worth writing down
rather than quietly closing.** The 2026-08-13 revision made `PUT /api/moderation/protocols/{id}`
SCHICHTLEITER-only, which was the right rule. But the only screen with a Bearbeiten button is
`/moderation`, and that route was `roleGuard('admin')` — so the administrator held the screen without
the permission and the Schichtleiter held the permission without the screen.

**The role split landed before the route did.** It was named in that pull request rather than
discovered later, and left for Carlos because widening who may reach a view is a permission decision
and not a UI change. He has ruled: open it to the Schichtleiter. The honest version of this history
is more useful than a clean-looking one — a permission and its path are one change, and splitting
them across two releases is how a feature ships that nobody can use.

### The endpoint × role matrix, which is now written in the controller too

| Endpoint | ADMIN | SCHICHTLEITER | Why |
|---|---|---|---|
| `GET /` | ✅ | ✅ | you cannot correct what you cannot find |
| `GET /{id}/document` | ✅ | ✅ | the edit dialog loads the stored text, not the row |
| `PUT /{id}` | ❌ | ✅ | the approver is never the corrector (2026-08-13) |
| `DELETE /{id}` | ✅ | ❌ | removing from the corpus is moderation |
| `PUT /{id}/approval` | ✅ | ❌ | the corrector is never the approver |
| `GET /deleted` | ✅ | ❌ | the archive holds what somebody judged unfit to be read |
| `GET /deleted/{id}/document` | ✅ | ❌ | same |

Operator and Techniker reach none of it. **Two reads were opened so that one write could be reached,
and nothing else was**: a Schichtleiter who could suddenly delete would be a worse defect than the
unreachable endpoint this closes, so that fence is a parameterised refusal test over all four
admin-only acts rather than the absence of a change.

The document read deserves its own line, because the obvious objection is that a Schichtleiter can
already fetch the same bytes from `/api/protocols/{id}/document`, which is open to the shop floor.
True — and the alternative is a client that picks one of two document paths by role. The reason
`/api/moderation/**` has its own document endpoint is that the two differ in **who may take them**,
which is exactly the kind of difference that should stay visible at the call site.

### Correcting is not moderating, and the screen has to say so

The route is shared; the view is not. A Schichtleiter sees the records list, Öffnen and Bearbeiten.
They do not see Löschen, Freigeben/Zurückziehen or the archive tab — **absent, not disabled**. A
destructive control that exists only to refuse is noise on a shop-floor tablet and, worse, tells the
reader their role includes a power it does not.

The wording follows the same rule. "Protokollverwaltung" describes reviewing and removing, so the
heading, the intro line and the navigation entry are chosen by role — the corrector gets "Protokolle
korrigieren" and "Korrekturen". A corrector who clicks "Verwaltung" and finds no delete button has
been told the wrong thing about their own role, and the trust chain is only as legible as the screen
that presents it.

**One sentence was added to the correction dialog**: when the protocol is currently APPROVED, it says
that saving withdraws that approval. An edit resets approval unconditionally, so a correction is how
an approved protocol quietly stops being approved — and the person about to cause it should read that
before they save rather than find it in the list afterwards. In the dialog that is already open, not
a second confirmation: the ordinary case for a corrector is text nobody has reviewed, and it must not
pay for the rarer one.

### What this closes in the test suite

`correctAsSchichtleiter` — the e2e helper that performed the correction as an authenticated `PUT`
because no browser could take that step — is **deleted**. It was written to be, and the pattern is
worth keeping even though the helper is not: when a gap is somebody else's decision, stand in for it
in a way that names itself, and make the stand-in cheap to remove.

---

## Revision — 2026-08-14: duplicate detection, and the number that had to be measured

The 2026-08-10 note deferred this with one non-negotiable condition: **it warns, it never blocks.**
This revision implements it, and the measurement it required changed *which* protocols the argument
rests on.

### Similarity warns. Nothing refuses an approval on a score.

There is no branch in `ProtocolSimilarityService`, in `ProtocolApprovalService` or in
`ModerationController` that can turn a similarity into a refusal, the approve button in the dialog is
never disabled, and a *failed* similarity call approves anyway. `ProtocolDuplicateIT` asserts it with
a verbatim copy — the strongest signal the feature can produce — still approving.

**Why it must be this way is a fact about this corpus rather than a philosophy.** Four protocols on
PR-03 carry the fault code E-47: a worn piston seal, a pressure-relief valve sticking, a programme
change, and a slow build-up. Four different root causes, four legitimate protocols, all four
correctly cited together in the demo answer. A threshold sensitive enough to catch a genuine
duplicate would report all six of their pairs, and a system that had refused the fourth of them would
have removed exactly the knowledge that makes the demo answer good.

### What is compared: the protocol, as the mean of its chunk vectors

Each protocol is reduced to one vector — the centroid of its chunk embeddings — and two protocols are
compared by the cosine similarity of their centroids. The obvious alternative, the best
chunk-to-chunk match, was measured alongside it and rejected:

- **A maximum over a set that grows with document length is not a comparison of documents.** A
  six-chunk protocol gets six chances to score high on one of them, and under chunk-max, adding a
  paragraph the other document does not contain *cannot lower* the score. The statistic is monotone
  in length, which is precisely the "two long protocols overlapping in one paragraph" false positive.
- **The centroid moves the right way.** Material the other document lacks pulls the mean away from
  it, which is what "these two say different things" should do to a number.
- **Measured in this corpus**: E-47 #1 (two chunks) against #13 (one chunk) scores **0.8205** by
  chunk-max and **0.7698** by centroid. The case where chunk-max reads distinctly higher is exactly
  the shape of the false positive — one section of a longer document matching a shorter one whole.

The cost is stated rather than hidden: a genuine duplicate buried inside a long protocol is *diluted*
by the centroid and can fall below the threshold. That is the right side to err on for a feature
whose premise is not crying wolf. A missed warning costs a second protocol in a corpus that tolerates
several accounts of one fault by design; a false one costs the approver's trust in every warning
after it.

### The threshold: measured, a config property, and NOT ADR-002's 0.55

`maintenance.duplicates.similarity-threshold`, never a literal. ADR-002's 0.55 is a **different
question** and the numbers do not transfer: that one is question-to-chunk, a short interrogative
sentence against a paragraph of a maintenance report. This is document-to-document, between two texts
of the same genre, on the same machine, written from the same template, both carrying the chunker's
`PR-03 · E-47 · <title>` context prefix. Everything here starts high — **the mean over all 1,393
same-machine pairs in the corpus is 0.5547**, which is where the query path's *threshold* sits.
Carrying 0.55 over would flag roughly half the corpus.

Measured 2026-08-14 by `DuplicateSimilarityCalibrationIT`, against the real provider (`bge-m3`) and
the real 165-protocol corpus:

| What | Centroid similarity |
|---|---|
| mean over all 1,393 same-machine pairs | 0.5547 |
| the four E-47 protocols against each other | 0.7594 – **0.8329** |
| **highest legitimate pair anywhere in the corpus** | **0.9151** |
| one incident re-narrated by a second person — the *realistic* duplicate | **0.9305** |
| a verbatim re-file under a different title | 0.9778 |

**Chosen: 0.92.** It clears the highest legitimate pair by 0.0049 and sits 0.0105 below the realistic
duplicate.

### THE FINDING: the E-47 four are not the constraint

The brief for this work assumed the threshold had to clear the E-47 spread. It does, by 0.09 — and
that turned out not to be the binding condition. **The highest-scoring legitimate pair in the corpus
is not an E-47 pair at all**: it is PR-07's "Halbjahreswartung Presse 7" and "Jahreswartung Presse 7"
at **0.9151** — the 4000-hour and 8000-hour services, same machine, same technician, same template,
genuinely different work. Scheduled maintenance runs closer than fault reports do, because a
checklist is a form and two filled-in forms resemble each other more than two incidents do.

So the usable window is **0.9151 … 0.9305 — 0.0154 wide**, and that is narrower than anyone would
choose. It is reported rather than smoothed over, and it is survivable for exactly one reason: **this
feature warns and never blocks.** A false positive costs one card in a dialog that an approver
dismisses in a second; a false negative costs the warning entirely. 0.92 sits low in the window on
purpose, to buy sensitivity with the cheaper kind of error. *If this feature blocked, this margin
would not be shippable* — which is the strongest argument yet for the rule the 2026-08-10 note set.

Any change to the embedding model, to the chunker, or to the corpus invalidates every number above.
`DuplicateSimilarityCalibrationIT` is how they are re-taken; it is skipped unless `LLM_API_KEY` is
set, so CI never runs it and it never spends money by accident.

### Cost, and where it runs

One SQL statement, no provider call, nothing written. The candidate's chunks already exist — it was
indexed when it was filed — so this asks the vectors a question rather than computing new ones.
Measured with `EXPLAIN ANALYZE` against the 165-protocol corpus (182 chunks, 25 protocols on the
busiest machine): **1.7 ms execution, 4.8 ms planning**.

It is a sequential scan and deliberately does **not** use the HNSW index: that index answers "nearest
neighbours of this vector", and this asks for an aggregate per protocol, which is a different
question. The cost is therefore linear in the corpus rather than logarithmic. At this size that is
1.7 ms; a hundredfold corpus would be a fifth of a second on an admin's click, which is still the
right trade for a query nobody runs in a loop. If it ever stops being, the fix is a stored
per-protocol centroid, not an index this shape of query cannot use.

**Archived protocols are excluded**, and the comparison is scoped to one machine. The machine scope
is a domain rule and not an optimisation: a seal replacement on PR-03 and a seal replacement on PR-07
are two maintenance events, not one written twice.

A protocol with no vectors yet reports `comparable: false` rather than an empty candidate list. Those
are different facts and only one of them is a statement about the corpus.

### What the approver sees, and what the wording is for

Up to **three** candidates, most similar first, each with its title, incident date, similarity as a
whole percentage — and **its own approval state**, which is the field the decision turns on. "Nearly
the same as a protocol an administrator already vouched for" is a merge-or-reject question; "nearly
the same as one nobody has reviewed" is often two honest accounts of one fault, and this corpus wants
both. The count of everything above the threshold is reported separately, so a long tail is visible
rather than cut silently to three. Each candidate opens in the existing read-only viewer, because a
percentage is not a comparison.

**The dialog opens only when there is something in it.** Nothing similar means no dialog and one
click, which is the 2026-08-13 behaviour unchanged: a box that opened every time to announce "nothing
found" would tax the common case for the rare one, and a permanently empty section is one readers
learn to skip — including on the day it fills.

The wording carries the rule. Review palette (`--c-review-*`), never danger; an information glyph
rather than the triangle the "this edit resets approval" notice uses; no sentence that says
"warning" or implies wrongdoing; and the approve button enabled throughout. **Being similar is normal
here.** A sentence implying a problem beside a button that plainly works teaches a reader to distrust
one of the two.

Contrast was measured on the new surface in both palettes, all above AA: the review notice 8.49:1
light / 9.17:1 dark, a card title 13.57 / 13.51, the percentage 5.47 / 7.52, the method line
5.85 / 5.65.

### The ledger records an informed approval

When an administrator approves a protocol that had candidates, the `APPROVE` row's comment carries
`approved despite N similar protocol(s) on this machine: <ids>` — every id above the threshold, not
only the three shown. **No schema change**, and no second event: the ledger is a record of changes to
the corpus's trust, and one approval is one change however much context it carries.

The reasoning is ADR-006's own delete-with-reason argument applied to the moment a protocol becomes
vouched-for. A plant auditor asking "did anybody notice that these two say the same thing?" should
find the answer in the ledger rather than in somebody's memory of a screen — and an informed decision
and an uninformed one look identical afterwards unless one of them is written down.

**The similarity is recomputed by the approval rather than taken from the request.** If the client
sent the ids it happened to display, an approval made through `curl` — or by a client that never
asked — would record "nothing similar", which is the one answer the ledger must never give wrongly.
The check belongs to the act, not to the screen.

**No justification text is required, deliberately.** A mandatory field on every approval would make
the common case — nothing similar, nothing to say — expensive, and a field people are forced to fill
fills up with "ok". The missing accountability was the *record that the approver was informed and
proceeded*; a sentence about it is not.

### What this does NOT do, and will not

- **No merge.** Two protocols are two events; combining their text would produce a document that
  describes neither, authored by nobody, with two `uploaded_by` values and one row to put them in.
- **No "supersedes" link between protocols, and this was considered and rejected rather than
  forgotten.** It would make the corpus a **graph**, and every answer would then have to explain
  which version it cites — a technician reading a Mode A answer would need to understand a
  superseding relationship before trusting a citation, which is a worse burden than the one it
  removes. Sources are already date-ordered and carry their incident date, which tells a technician
  what is recent using a fact the protocol already has.
- **No automatic action of any kind**: nothing is hidden, demoted, merged or unapproved because of a
  similarity score.

### A note on the four-eyes refusal, found while looking for a way to demonstrate it

The act-based check in `ProtocolApprovalService` refuses an approval by whoever filed or last
corrected the protocol. **Neither branch is reachable through the API as it stands, and that is the
role split working rather than dead code.** Approval is admin-only; upload is Techniker and
Schichtleiter; correction has been Schichtleiter-only since 2026-08-13. An administrator can
therefore never be the author and never be the corrector.

Adding a second demo administrator was considered as a way to make the refusal clickable for a
visitor. **It would not**: the rule fires on the *same human doing two acts*, and no administrator
can perform the first one. Making it demonstrable would mean giving a demo account two of the three
jobs, which contradicts the decision the chain rests on and would misrepresent the design on the one
screen a visitor looks at. So it was not done, and the reasoning is here rather than in a commit
message nobody will find. The check stays as the belt to the split's braces — it is the only guard
that could express "the same human did both", the only one that survives a future widening of any of
those three annotations, and a rule enforced in exactly one place is one edit away from being gone.
It is covered by `ProtocolApprovalIT` against a real database.

---

## Note — 2026-08-14: the table shows state, the record shows provenance

Carlos drilled #56 in production and found the Verwaltung table's FREIGABE column truncated:
`Freigegeben system:corpus-seed · 12.08.2…` ran under the action buttons. His ruling, and the design
this note records: **the list shows the approval STATE; who approved it and when move into the
protocol viewer as a history section.**

### The defect, measured

The column carried the label, the approver and the date inside `max-width: 11rem`, while the badge
sets `white-space: nowrap`. So the text did not wrap — it **overflowed**. Measured in the live page
at the badge's own font: the cap is **176 px**, the seeded string renders at **293 px** (a 117 px
overflow, two-thirds of the cell spilling out), and even the ordinary `admin · 12.08.2026` case
renders at 218 px (42 px over). The `max-width` was treating a content problem as a layout problem.

The fix is content, not CSS: with the state alone the widest the badge can ever be is **139 px**
("Nicht freigegeben"), so the cap is gone entirely and the cell is sized by what it holds. Measured
after the change, at 1920 px and at 1280 px: **0 px overflow in the approval cell, 0 px in the
actions cell, 0 buttons painted outside their own row.**

`showActor` was **deleted rather than defaulted off**, and its `language` input with it. The badge
renders in a table cell, a source card and a dialog head, and provenance repeated in every list that
mentions a protocol is what produced the defect in the first place. A flag would have left the
overflowing form one binding away.

### Why the actor is worth keeping at all

Carlos asked, and the answer belongs on the record rather than in a commit message. For the 150
seeded protocols `system:corpus-seed` says something true and load-bearing: **no human reviewed
this — it was born approved by a migration.** That is the honest record of decision 2 of 2026-08-11,
and inventing a person's name there would fabricate exactly the unearned trust the approval flag
exists to make visible. It is useless repeated on every table row and valuable once, in the record.

### The ledger already spelled four verbs — a finding, and no migration

The brief for this work assumed `ck_moderation_event_action` was still V4's `('EDIT','DELETE')` and
asked for a migration widening it. **It was widened by V5 (#53)**, in the same migration that added
the approval columns, to `('EDIT','DELETE','APPROVE','UNAPPROVE')` — and approvals and withdrawals
have been writing proper rows ever since: actor, timestamp, and a non-blank comment the table's own
`ck_moderation_event_comment` requires.

Verified on the development database, which carries #53–#56 data: Flyway at V5, six migrations, all
successful; the constraint in force naming all four verbs; and **52 APPROVE, 31 UNAPPROVE, 51 EDIT
and 105 DELETE rows already present**. No migration was needed and none was added — the number of
Flyway files is unchanged by this pull request.

The stored verb is `UNAPPROVE`, not `WITHDRAW`. The interface says "Freigabe zurückgezogen"; renaming
the stored value would mean a data migration and a sweep of three services to change a word no user
reads. `ProtocolHistoryIT` asserts the vocabulary in force, including that an invented `WITHDRAW` is
**refused by the database** — so whoever extends the set finds the constraint rather than a silent
no-op.

### What happens to approvals recorded before this change: nothing is missing

Counted on the same database: of 163 approved protocols, **13 were approved by `admin` and all 13
have their ledger row**; **150 were approved by `system:corpus-seed` and none of them has one, by
design**. So there is no backfill to do and none was attempted. The only rowless approvals are the
migration's own, and the viewer degrades to exactly them: when a protocol has no acts but does have
an approver, the section names that approver and the day, and adds one sentence when the actor is a
`system:` one. A viewer that showed nothing there would let a reader assume the approval came from
somewhere it did not.

### The history section

The **last three** acts, newest first. Each line carries the verb in plain language (`UNAPPROVE`
never reaches a screen), the actor, and the **date and the time** — two corrections on one afternoon
are a different story from two a month apart, and the day-only format the tables use cannot tell them
apart. The reason comes with every entry, which needs no empty case because the database requires a
non-blank comment on every row.

**THE THREE-ENTRY CAP IS A PRODUCT DECISION, NOT A TECHNICAL LIMIT**, and it is written down in two
places in the code so nobody "fixes" it. The viewer exists to let somebody read a protocol; three
lines answer "what has been done to this recently" without pushing the document below the fold. A
full change history is a **report** — its own screen, with paging and filters — and Carlos has
deferred it deliberately. There is no "show all" control here and there is not meant to be one. What
there is instead is one quiet line saying older entries exist, which is what stops the truncation
being silent.

Nothing to say renders **no section**: no heading, no empty box, no "no history yet". An unapproved
protocol nobody has touched has neither acts nor provenance, and a heading over nothing is furniture.

`GET /api/moderation/protocols/{id}/history`, limited server-side with the total reported beside it —
a protocol edited weekly for a year has fifty rows, and sending all of them so a dialog can drop
forty-seven is a payload that grows with the corpus's age.

| Endpoint | ADMIN | SCHICHTLEITER | Why |
|---|---|---|---|
| `GET /{id}/history` | ✅ | ✅ | you cannot judge a correction without knowing what was already done |

The corrector gets it for the reason they got the document read in the 2026-08-14 widening: **an edit
that silently repeats a correction somebody made last week is what it prevents.** The shop floor is
refused, and that is deliberate — who corrected what, who approved it and who took an approval back
names colleagues in connection with mistakes, which is the same reason the delete comment travels in
a request body rather than a query string. A technician checking a citation needs the protocol's text
and its approval STATE, and both already reach them.

A failed history call leaves the section **absent** rather than putting an error beside it. The dialog
exists to show a protocol; a red box about an unreachable audit trail next to a document that loaded
perfectly would read as though the document were the problem.

### A finding about the visual check, recorded because it nearly hid this change

`maxDiffPixelRatio` is 0.002 — roughly a thousand pixels on a dialog-sized shot, which is **enough to
absorb one line of text disappearing.** When the badge stopped rendering its actor, the
duplicate-detection baselines still passed, and `--update-snapshots` therefore refreshed nothing: it
only rewrites what fails. A baseline that survives a deliberate content change has quietly stopped
recording what the application looks like, which is the one property ADR-007 asks of it.

The practice that follows, now written into `visual.e2e.ts` and `e2e/README.md`: **after an
intentional change, delete the affected baselines and regenerate rather than trusting
`--update-snapshots` to notice.** Regeneration is byte-deterministic in the pinned container, so
`git status` afterwards is an exact list of what really moved. Doing that here showed exactly two
files; the other fourteen were byte-identical, which is the check saying the records table itself did
not shift.
