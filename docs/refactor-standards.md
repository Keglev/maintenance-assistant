# Refactor Standards

What "good" looks like in this repository, and the mechanisms that keep it honest.

The normative document is
[`REFACTOR-STANDARDS.txt`](/maintenance-assistant/REFACTOR-STANDARDS.txt) — it holds the
size bands for every file type, the test-conduct rules and the waiver register, and it is
the single place any of those is edited. This page does not repeat the tables. It explains
the **mechanisms**, which are the transferable part, and records the **measured state** the
refactor phase started from.

The standards were adapted on 2026-08-20 from a portable template distilled from a sibling
project's standards. The bands were retuned for this project's stacks; the mechanisms were
kept, because the mechanisms are what earned their keep.

## Why a standards file at all

A refactor that starts with an edit is a preference. A refactor that starts with a
measurement is maintenance. The difference only survives if "what good looks like" is
written down somewhere reviewable — otherwise every session re-derives it, and the
re-derivation drifts toward whatever the current file happens to look like.

So the phase has a ground rule: **the standards file is in the repository before the first
fix pull request opens.** This page and its normative sibling are that file.

## The mechanisms

### Line count is the last reason to split

Bands exist, but a file over its band is not automatically wrong. The split criteria are
about *reasons*, not length: more than one responsibility, more than one abstraction level,
unrelated change-reasons, more than about three injected dependencies, three or more
repeated patterns.

The corollary matters as much: **parallel operations are not a split reason.** A controller
with one case per endpoint reads repetitively because the operations genuinely are
parallel. Splitting it produces two files that must now be read together.

### The watched band

Every band has a target range and an alarm. Between them a file is **watched** — recorded
in the survey, no action taken. Only above the alarm does a file become a finding.

This is what stops a standards document from generating busywork. Most files that drift
past target are fine; the band records the drift without demanding a pull request for it.

### Waivers are granted, never assumed

An above-alarm file that survives the split criteria is **waived**: an in-file comment
saying why the length is the rule working, plus an entry in the waiver register naming the
file, the figure, the band, the date, the reason and who granted it.

Two properties make this work. A waiver is visible where the code is *and* collected in one
list, so nobody has to grep for the exceptions. And a waiver is granted by the owner — a
robot that waives its own finding has simply deleted the finding.

The register is created empty on purpose, so the first waiver has somewhere to go rather
than becoming a comment nobody collected.

### Counters are validated before they are trusted

Every measuring script runs first against an anchor whose answer is already known, and the
validation is reported next to the figure.

This is not ceremony. The classic failures are all silent: a substring grep matching a
shorter token inside a longer one, multi-line comments counted as code, a comment marker
inside a string literal, a regex read as a string. Each produces a plausible number. For
coverage the anchor is the report's own headline — a recomputation from the CSV or the
lcov file is only reported once it matches what `index.html` states.

An empty grep also describes a command that never ran, which is why the command is shown
alongside the result.

### Survey figures are provisional

Surveys are read-only and produce findings tables with evidence and a proposed severity —
FIX, WATCH or ACCEPT. Nothing is repaired inside a survey.

And the pull request that acts on a survey row **re-measures that row first.** A survey is a
snapshot; the code moves.

### Vacuity proofs

A new test earns its place by failing. Break the mechanism under test, watch the test go
red, restore it. One break proving two claims is complete; a break of something no test
claims proves nothing.

Absence assertions get special treatment — a test asserting that something did *not* happen
is paired with proof that the intended path ran at all, or it passes for the wrong reason
forever.

### Message assertions follow layer ownership

The rule that took the longest to get right, because both simple versions are wrong.

Service-level tests assert the exception type *and* its message: where the error handler
passes messages through, the sentence is the wire contract, and where it does not, the
message is still the only observable a unit test has. Web-slice tests own the envelope —
the HTTP status, the message exactly as the wire carries it including any handler prefix,
and the machine code where one exists.

On this project the machine codes are real contract: `PROTOCOL_IDENTITY_LOCKED`,
`EMPTY_FILE`, `UNSUPPORTED_TYPE`, `NOT_TEXT`. A test that pins only the status is
under-asserting.

Neither "never assert message text" nor "the message is never part of the contract"
survives contact with a handler that passes messages through. Read the handler before
writing the rule.

### Errors in the record get corrected in the record

Not silently dropped because the code moved on. The refactor phase opened with an example:
its own plan text asserted that the deployed Swagger UI lacked navigation back to the
documentation site. It has had that navigation since 2026-08-13. The plan carries a dated
correction rather than a quiet edit.

## Coverage targets

**85% on statements, branches — including missed branches — and functions, on both
stacks.**

Two clarifications the standards insist on, because both are places a figure quietly turns
into a claim:

*Statements* maps to istanbul statements on the frontend, and on the backend to JaCoCo
**instructions and lines, both stated.** JaCoCo has no statement counter, and quoting only
the friendlier of the two would be a choice presented as a measurement.

*The figure that counts is the one CI publishes.* A local run is a working tool, not
evidence. It follows that key-gated integration tests — the ones needing a provider API key,
which CI skips — cover nothing as far as this target is concerned. Code whose only tests are
gated is uncovered, however green it looks on a laptop with the key set.

Enforcement is a **ratchet, armed last**: once the targets are met, `jacoco:check` and
Vitest thresholds are set at the achieved numbers so a regression fails the build. Arming
it before the targets are met would only break CI.

## The measured starting point

Surveyed 2026-08-20 against commit `09d8b57`, from the reports CI published to gh-pages.
The backend report was published for `9dc8d4d`; the backend tree is unchanged between that
commit and `09d8b57`, so it is current. Both recomputations were validated against their
report's own headline before being used.

| Stack | Statements | Lines | Branches | Functions / Methods |
|---|---|---|---|---|
| Frontend (istanbul) | 94.63% | 95.52% | 86.76% | **84.38%** |
| Backend (JaCoCo) | 74.95% instr. | 75.72% | **54.34%** | 83.21% |

The frontend misses the target on functions alone, by three covered functions, and the gap
is concentrated in a single view: the moderation screen and its template hold 32 of the 65
uncovered functions.

The backend headline is two different problems wearing one number. Five classes cannot be
reached by any test CI runs — the two provider clients with their response records, and
three flag-gated startup runners. Excluding them, the same report reads 88.52% instructions,
89.19% lines and 93.33% methods, with branches still the outlier at 66.30%.

So the real campaign underneath the headline is **branches**: 184 missed in code that tests
do reach, 150 of them in fourteen classes. The largest single gap is the protocol chunker at
26 of 42 — the merge, split and overlap rules are the retrieval-critical logic in this
project, and half their branches are unexercised.

Neither stack enforces any threshold today. The reports are published; nothing fails on
them. That is worth stating plainly rather than leaving 85% to read as if it were a gate.

## Where the rest lives

The phase plan, its rulings and the pull-request order are in `PROJECT-PHASES.txt` under
the refactor phase. The bands, the full test-conduct rules and the waiver register are in
[`REFACTOR-STANDARDS.txt`](/maintenance-assistant/REFACTOR-STANDARDS.txt).
