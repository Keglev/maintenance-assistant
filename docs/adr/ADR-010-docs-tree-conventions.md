# ADR-010: Name docs pages by what they are, and freeze citations that a checksum guards

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-24 |
| **Deciders** | Carlos Keglevich |
| **Related** | PR #88 (Part 2 code structure survey, findings F2 and F3), `docs/REFACTOR-STANDARDS.txt` DOCS section (decision records are immutable; published prose states only what the checkout shows), Flyway migrations `V1__baseline_schema.sql` and `R__seed_machines.sql` |

## Context

The Part 2 structure survey (#88) audited file names and directory placement across the repository
and left exactly two rows unresolved, both because **no rule was written down and two readings were
each defensible**. A survey that picks one anyway is a survey writing policy by fiat, so both were
recorded as RULING NEEDED. This is the ruling.

### F2 — the casing of docs pages

`docs/` holds seven top-level markdown pages. Five are lowercase-kebab
(`frontend-architecture.md`, `llm-approach.md`, `overview.md`, `overview-de.md`,
`refactor-standards.md`) and two are SCREAMING-KEBAB (`DOMAIN-MODEL.md`, `REQUIREMENTS.md`). The
three `.txt` ledgers are SCREAMING, three of three, so only the markdown pages are split.

Both readings survived the evidence:

- the two are **accidental outliers** and should match the other five; or
- the casing **means something** — that these two are normative specifications while the lowercase
  five are explanatory prose.

Nothing in the repository decides it. A search for a stated naming convention across `docs/*.txt`
and `docs/*.md` returns nothing, so whichever reading a future contributor picked would be a guess.

The casing is not invisible: it reaches the published site as `/DOMAIN-MODEL.html` and
`/REQUIREMENTS.html` beside `/overview.html`, so a reader sees the inconsistency without opening the
repository.

**The cost of renaming was measured before the decision, not after.** Excluding each page's own
file, `DOMAIN-MODEL` is cited in **22** tracked files and `REQUIREMENTS` in **10**. (A naive grep
today returns 23 and 11; the extra hit in each is the #88 survey entry itself, which quotes the
names. The figures above are the citing files that would actually need editing.)

Two of those citations are the reason this ADR exists rather than a one-line convention:

```
V1__baseline_schema.sql:3    -- Source of truth: docs/DOMAIN-MODEL.md and docs/DECISIONS.txt …
V1__baseline_schema.sql:105  -- Not in DOMAIN-MODEL.md: position of the chunk within its protocol …
R__seed_machines.sql:1       -- Repeatable seed — the 10 machines of the demo plant (docs/DOMAIN-MODEL.md §3).
```

**Flyway checksums cover the whole file, comments included.** `V1` is a versioned migration that has
been applied to production, and `spring.flyway` here sets neither `validate-on-migrate: false` nor
`clean-disabled`, so the default validation is in force: editing that file — even one comment
character — makes the checksum disagree with the row in `flyway_schema_history`, and the application
refuses to start until somebody runs a manual `flyway repair` against production.

`R__seed_machines.sql` is a repeatable migration, so a changed checksum does not fail; it
**re-executes on the next migrate**. That seed is idempotent by construction (fixed UUIDs plus
`ON CONFLICT (machine_no) DO UPDATE`), so re-running it is safe rather than destructive — but it
would overwrite any hand-edit made to a machine row in production, and re-running a production seed
to correct a comment is disproportionate whatever the blast radius.

### F3 — where the site's landing pages live

`docs/_theme/index.html` and `index-de.html` are the published site's two landing pages, and they sit
in the theme tree beside `css/`, `js/` and `templates/`. They are hand-written HTML, not generated
from markdown, and `copy_landing_pages()` in `build-docs.sh` copies them verbatim to the site root.

The competing readings: they are **content** and content lives in `docs/`; or they are **part of the
theme** and belong with the assets they reference. Either way the build needs a special case for
them, because everything else under `docs/` is markdown that goes through the converter.

## Decision

**1. Docs file names follow what the file IS, not what it is about.**

- Explanatory markdown pages under `docs/` are **lowercase-kebab**: `overview.md`,
  `frontend-architecture.md`, and from the follow-up rename, `domain-model.md` and
  `requirements.md`.
- The plain-text ledgers (`DECISIONS.txt`, `PROJECT-PHASES.txt`, `REFACTOR-STANDARDS.txt`), every
  `ADR-NNN-*.md` and `ADR-TEMPLATE.md` stay **SCREAMING**. They are normative or immutable records
  rather than explanatory pages, and the standards already treat decision records as immutable once
  merged.

`DOMAIN-MODEL.md` and `REQUIREMENTS.md` are explanatory pages by this rule and **will be renamed** to
`domain-model.md` and `requirements.md` in a follow-up pull request. This ADR merges first; nothing
is renamed here.

**2. Citations inside Flyway migrations are FROZEN and are deliberately not updated by that rename.**

The three comment lines quoted above keep saying `DOMAIN-MODEL.md` after the file is called
`domain-model.md`. That is not an oversight and must not be "tidied":

- editing `V1__baseline_schema.sql` breaks its checksum against applied production history and stops
  the application starting until a manual repair;
- editing `R__seed_machines.sql` re-runs a seed against production.

**A stale comment in an applied migration is the checksum rule working.** Anyone who finds one and
reaches for a fix should read this ADR first. If a migration comment ever must change, it changes in
a NEW migration, never in an applied one.

**3. Hand-written HTML that is part of the site theme lives in `docs/_theme/`; markdown content lives
in `docs/`.** The two landing pages stay where they are. `copy_landing_pages()` is the *consequence*
of that split, not evidence against it: a theme that owns a hand-written page needs a copy step
whichever directory the page sits in.

## Consequences

**Positive**

- A contributor adding a docs page no longer has to guess: prose is lowercase, records are
  SCREAMING, and the rule is one line in the standards with this ADR behind it.
- The migration comments stop being a recurring question. The next person to notice
  `DOMAIN-MODEL.md` in `V1` finds a written answer instead of an apparent inconsistency.
- F2 and F3 leave the survey's open list, so Part 2's remaining findings are all actionable.

**Negative**

- **Two published URLs change**: `/DOMAIN-MODEL.html` and `/REQUIREMENTS.html` become
  `/domain-model.html` and `/requirements.html`. Any external bookmark breaks; no redirect exists
  because GitHub Pages here serves a static tree with no rewrite layer.
- **The rename touches 32 citing files** (22 + 10), including `nav-docs.html`, Java javadoc and
  arc42 pages — a large mechanical diff for a cosmetic gain, and one that has to be complete or it
  leaves dead links.
- **Two migrations will be knowingly stale.** The repository will contain comments that name a file
  which no longer exists under that name. That is a deliberate, documented inconsistency, and
  deliberate inconsistencies cost every reader who meets one for the first time.
- The `_theme` ruling means `docs/_theme/` holds both assets and two content pages, so "everything
  in `_theme` is an asset" is not quite true either.

## Alternatives considered

- **Rename everything, including the migration comments, and run `flyway repair` on production.**
  Attractive because it leaves no inconsistency at all and the repair command is one line. Rejected:
  it trades a comment's accuracy for a production migration-history edit, and `flyway repair`
  rewrites the checksum of an applied migration — a real operation on the live database, done to fix
  a filename in a comment. The risk is small and entirely unnecessary; the reward is cosmetic.
  *Rejected.*
- **Rename nothing, and declare SCREAMING to mean "normative".** Attractive because it costs nothing
  today, keeps two published URLs stable, and can be defended after the fact. Rejected because
  **two pages out of seven carrying meaning by casing is a convention nobody would guess** — the
  survey could not distinguish it from an accident, and a convention that has to be explained to be
  seen is not doing the work a convention exists to do. *Rejected.*
- **Move the landing pages into `docs/` for consistency with the other content.** Attractive because
  `docs/` would then hold all content and `_theme/` only assets. Rejected: the pages are hand-written
  HTML referencing theme assets, they would sit among markdown sources that the converter walks, and
  the build would still need the same special case — the same exception, in a less obvious place.
  *Rejected.*
