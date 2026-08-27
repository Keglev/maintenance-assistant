# ADR-011: Show the user a question that works

| | |
|---|---|
| **Status** | **Accepted** |
| **Date** | 2026-08-27 |
| **Deciders** | Project owner (solo) |
| **Related** | Serves US-2, US-3, US-4 and the new **US-9**; constrained by NFR-2 (answer modes) and NFR-3 (role-based answer filtering). Builds on [ADR-006](ADR-006-insider-threat-and-protocol-moderation.md) (role filtering), [ADR-008](ADR-008-retrieval-measurement.md) (the golden set and the threshold) and [ADR-007](ADR-007-end-to-end-testing-strategy.md) (visual baselines) |

## Context

A first-time user types two words — *"sensor fehler"*, *"maschine nicht einschalten"* — and gets the
ungrounded card. **The card is correct and the product still looks broken.** No protocol matches a
two-word question with no machine context, so Mode B is exactly what NFR-2 asks for; but to a reader
who has never stood in front of a press, an answer that begins "no source in the corpus" reads as
*this thing does not work*, not as *ask me something more specific*.

This was found by using it, on 2026-08-26 and 2026-08-27, and it is sharpest for the reader the demo
exists for: **a recruiter, who has ninety seconds and no plant experience.** They cannot invent
*"Presse kommt nicht auf Druck, Fehler E-47"* because they do not know that E-47 exists.

Three things make it a decision rather than an obvious fix:

- **The corpus is not the problem.** 165 live protocols sit behind those machines. The user has no
  way to discover a question that reaches one, and the system has no way to tell them.
- **The help panel already explains this** — and only to someone who reads it. A panel that answers
  a question the user has not thought to ask is documentation, not a product.
- **The obvious fix is the wrong one.** Lowering the similarity threshold would turn some of these
  into Mode A answers, and would do it by loosening the one number NFR-2's honesty rests on. That
  is a measurement question, and it has its own open row (see *Not decided here*).

The failure is **discoverability**, not retrieval. Retrieval is doing what it was measured to do.

## Decision

### 1. Example questions are data, owned by the backend

A versioned resource file, **`backend/src/main/resources/examples/example-questions.json`**, served
by **`GET /api/machines/{machineNo}/examples`** — read-only, any authenticated role.

- **Three to four entries per machine, in German and English**, each written against a protocol that
  exists, so **every example yields Mode A**. An example that lands in Mode B would teach the
  opposite of the lesson.
- Addressed by `machineNo` and not by the UUID the query path uses: this file is authored by hand
  against plant identifiers, and `MachineCatalog.Machine` already carries both, so the frontend has
  the key either way. A hand-edited file keyed by `0f9c5b01-…-0009` is a file nobody can review.
- **A machine with no protocols gets an empty list**, and the frontend shows no chips. Not an error,
  not a placeholder: there is no question that works, so the honest answer is to offer none.

**Why the backend and not the bundle:** the examples must track *the corpus*, which the backend
owns and which changes when a protocol is uploaded or deleted. An example that stopped matching
because its protocol was removed is a broken demo, and finding that out needs the two to live on the
same side of the wire. The same list then feeds the e2e suite and a future demo script, so one edit
fixes all three.

### 2. The golden questions are the seed, and they are COPIED, not moved

`backend/src/test/resources/retrieval/golden-questions.json` **stays exactly where it is and keeps
its meaning.** It is ADR-008's ratified measurement set: 19 questions over 10 machines, ratified by
the owner on 2026-08-20, and **two of them are deliberately Mode B** (the dosing gap on AB-02). A
set whose job includes questions that must *not* find anything cannot be the set shown to a user as
questions that will.

So the Mode A entries are **copied** into the new file as its seed, and this ADR names the
duplication rather than pretending it away:

| File | Canonical for | Changing it is |
|---|---|---|
| `test/resources/retrieval/golden-questions.json` | retrieval measurement (ADR-008) | a measurement change — re-ratify |
| `main/resources/examples/example-questions.json` | what a user is offered | a **product** change |

They will drift, and that is correct: the measurement set must keep its Mode B cases, and the
example set must never have any. **What must not drift is a question's expected mode**, and the e2e
case in §*Consequences* is what holds that.

The golden file gains a header note saying its Mode A entries are now user-visible through a copy,
so an editor knows an edit there is a measurement change and an edit in the other file is a product
change.

### 3. Chips under the question box, filled but not submitted

Once a machine is selected, the frontend shows the examples as clickable chips under the question
box. **Clicking fills the box. It does not submit.**

The user still reads the question and still presses the button. That is the whole value: the demo
**teaches the shape of a good question** — a machine, a symptom, ideally an error code — instead of
performing a trick the user cannot repeat. A chip that submitted would produce a good answer and no
understanding.

### 4. The ungrounded card says how many protocols exist, and offers one next step

The Mode B response carries **`protocolCount`** for the machine, and the card says, in the user's
language, that *N protocols exist for this machine and none matched*, with **one** hint: an error
code, or one of the examples.

- **This is presentation, not retrieval.** No threshold moves, no query is re-run, nothing is
  re-ranked. The same Mode B answer is returned with a number beside it.
- **The count is live protocols only** (`deleted_at IS NULL`), matching exactly what retrieval sees.
  A count that included soft-deleted rows would promise evidence that cannot be reached — and the
  measurement of 2026-08-26 found 50 such rows locally and 2 in production, so this is a real trap
  rather than a theoretical one.
- One hint, not a list. A dead end becomes a next step; a wall of suggestions becomes a second
  thing to read.

### 5. Operators see the same examples

No role filtering on the example list. **The examples are questions, not answers**, and the answer
path already restricts what an operator may be told (ADR-006, NFR-3) — an operator clicking a chip
gets the operator-safe answer to it. Filtering the questions as well would mean maintaining a second
role matrix for content that carries no protocol text.

### 6. The help panel and `LexicalTerms` are untouched

Stated so the rule is visibly honoured: **`LexicalTerms` is not modified**, so what counts as an
exact term does not change, so the help panel's text stays true. The panel describes retrieval
behaviour; this ADR adds a way in *beside* it and changes nothing it describes.

### Not decided here

- **Any threshold change.** ADR-008's WATCH row — Mode A clearing the gate and then producing no
  attributable citation, seen in 3 of 8 production queries on 2026-08-27 — is measured for a week
  first. Guided questions reduce how often a user *meets* the gap; they say nothing about where the
  gate belongs.
- **An automatic "did you mean".** Rewriting or re-running a user's question is a retrieval change
  and needs the same measurement discipline as the threshold.
- **Examples for machines with no protocols.** The list is empty and the chips are absent. Said
  explicitly so it is a decision and not an oversight.

## Consequences

**Positive**

- The recruiter walkthrough (requirements §6, ≤ 90 seconds) stops depending on the reader inventing
  a good question. That is the acceptance demo working as written.
- The examples are exercisable: an e2e case clicks a chip and asserts Mode A, so "the demo works"
  becomes a check rather than a claim.
- The ungrounded card gains a number that is true — 165 protocols exist, none matched — which is a
  more honest statement of the same outcome than the card makes today.

**Negative**

- **Two files hold questions, and they will diverge.** Named above, with which is canonical for
  what; the cost is a reviewer who has to know that.
- **The example file is hand-maintained content.** A protocol that is deleted can orphan an example,
  and nothing detects it except the e2e case, which covers one chip and not all of them.
- **One more endpoint** on the public API surface, and one more field in the answer DTO. Both are
  additive and read-only, but the OpenAPI document grows and the Angular client gains a call.
- **The search page layout changes**, so visual baselines change — see below.

## Alternatives considered

- **A static list in the Angular bundle** — simplest possible change, no endpoint, no DTO. *Rejected:*
  it drifts from the corpus silently, and changing one question means a frontend build and deploy for
  a fact the backend owns. The examples describe data; they belong with the data.
- **Examples derived at runtime from protocol titles** — always current, zero maintenance. *Rejected*
  on two counts: a title is not a question ("Werkzeugwechsler greift daneben" is a symptom, not
  something a user would type), and it would surface text from **unapproved** protocols to any role
  that can see the box, which is exactly the leak ADR-006 exists to prevent.
- **A demo script in the README only** — free, and it documents the walkthrough properly. *Rejected
  as a substitute:* it helps a reader and not a clicker, and the person this is for is already
  clicking. **It is still written**, as its own item on the landing page — the two are complements,
  and only one of them was ever going to be enough.

## Implementation

Two pull requests, in order, and a release after both:

1. **Backend** — the resource file, the endpoint, `protocolCount` on the Mode B DTO with a
   `@Schema` description, unit and web-slice tests, and the OpenAPI document asserted accurate.
2. **Frontend** — the chips, the hint text in the DE/EN dictionary, spec tests, and one e2e case
   that clicks a chip and asserts Mode A.
   **A visual baseline regeneration is EXPECTED here and must be its own commit** (ADR-007): the
   chips change the search page layout, so the baselines move for a reason, and regenerating them
   in the same commit as the feature would hide whether anything *else* moved.

Then **v1.4.0** — a feature, so a minor bump.
