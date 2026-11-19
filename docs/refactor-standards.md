# Refactor standards

The size, style and refactor standards of maintenance-assistant. These pages are **normative**:
where a review, a survey or a pull request disagrees with them, they win until the owner rules
otherwise. They are the single place a band, a rule or a waiver is edited.

Adapted 2026-08-20 from the portable template distilled from the StockEase standards (rev 14 /
template v2). The bands are adjusted to this project's stacks; the mechanisms are kept in
substance, because the mechanisms are the part that earned their keep. Confirmed 2026-11-13 by the
owner as the project's own standards; the workflow rules moved to the claimant model that day.
Written as linked pages since 2026-11-18 ([ADR-013](adr/ADR-013-markdown-only-docs.md)).

## The parts

| Page | Covers |
|---|---|
| This page | Cross-cutting principles, counting methods, coverage targets |
| [Size bands](standards-sizes.md) | Backend and frontend bands per file type, documentation and naming rules per stack |
| [Scripts and workflows](standards-ci.md) | Script and workflow sizes, the claimant model, image scans, dependency groups |
| [Comments in code](standards-comments.md) | What a comment may contain (C1-C11), ASCII-only comments |
| [Test conduct](standards-test-conduct.md) | Spec moves, vacuity proofs, state isolation, fixtures, message assertions |
| [Docs](standards-docs.md) | Docs pages, diagrams, decision records, naming |
| [Working practices](standards-practices.md) | The process rules and the two ops rules that generalise |
| [Waivers](standards-waivers.md) | The waiver register and the accept / watch ledger |

## Cross-cutting principles

- Size limits count **code lines only**: comments, doc comments and blanks excluded. A shebang
  counts as code — it is functional, not explanatory. A file under its code-line target is WITHIN
  regardless of how much documentation it carries; the counting rule excludes prose precisely so
  documentation never creates split pressure.
- **Line count is the last reason to split.** Split on: more than one responsibility, more than one
  abstraction level, unrelated change-reasons, more than ~3 injected dependencies, 3+ repeated
  patterns. Parallel operations (CRUD symmetry, one case per endpoint) are NOT a split reason —
  cases that read alike because the operations are genuinely parallel belong together.
- Every band has a target range and an alarm. Above target, below alarm = **WATCHED** (recorded, no
  action). Above alarm = a finding: split, or waive.
- **Waiver mechanism:** an above-alarm file that survives the split criteria is waived with an
  in-file comment stating why the length is the rule working, plus an entry in the
  [waiver register](standards-waivers.md) (file, figure, band, date, reason). **Waivers are granted
  by the owner, never assumed.**
- Comments explain WHY, never WHAT. Never comment self-evident code. A header that restates obvious
  markup or code is a defect, not documentation. The full rules are on
  [Comments in code](standards-comments.md).
- Deferred work: a TODO carries its backlog id at the site, tracked in a backlog; the marker is
  removed by the PR that closes its entry. Untracked bare TODOs are a finding.
- Hard cap for NEW code: ~300 code lines per file. Retrofit old files only when a pass already
  touches them.

## Counting methods (load-bearing)

- Counters are **validated before they are trusted**: run every measuring script against a known
  anchor whose answer is already established, and report the validation next to the figure.
  Classic false counters: substring greps that match a shorter token inside a longer one,
  multi-line comments counted as code, comment markers inside strings, regex literals read as
  strings.
- Counts use exact tokens, not substrings; every stated total is recomputed from its own parts; an
  empty grep also describes a command that did not run — show the command.
- Survey figures are provisional. **The PR that acts on a survey row re-measures that row first.**
- For coverage specifically, the anchor is the report's own headline: a recomputation from the CSV
  or the lcov file is reported only after it matches what index.html states.

## Coverage targets

- **85% on statements, branches (including missed branches) and functions, both stacks.** Set by
  Carlos for the refactor phase, 2026-08-20.
- "Statements" maps to istanbul statements on the frontend and to JaCoCo **instructions and lines**
  on the backend, both stated — JaCoCo has no statement counter, and quoting only the friendlier of
  the two is how a figure becomes a claim.
- **The figure that counts is the one CI publishes to gh-pages.** A local run is a working tool, not
  evidence.
- Live provider tests (`@LiveProviderTest`, JUnit tag `llm-live`) **do not run in CI** and therefore
  cover nothing as far as this target is concerned. Code whose only tests are those is UNCOVERED,
  and saying otherwise is the counter lying about what it measured. See
  [live provider tests](live-provider-tests.md).
- Enforcement is a **ratchet**, armed only once the targets are met: jacoco check and vitest
  thresholds, so a regression fails the build. Arming it early would only break CI. The floors are
  the targets (85%), not the achieved figures, so deleting a covered line that is no longer needed
  does not turn a refactor red; the margin above them is working headroom (`backend/pom.xml`,
  `frontend/angular.json`).

[Back to the docs home](index.html)
