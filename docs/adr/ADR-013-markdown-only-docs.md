# ADR-013: Write every document as linked markdown pages, never as plain text

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-18 |
| **Deciders** | Carlos Keglevich |
| **Related** | Amends [ADR-010](ADR-010-docs-tree-conventions.md) decision 1 (the plain-text ledgers); GitHub issue #203 (organizing the scattered documentation files); `.github/scripts/docs/build-docs.sh` (copies `docs/*.txt` to the site) |

## Context

[ADR-010](ADR-010-docs-tree-conventions.md) kept three plain-text ledgers in `docs/`
(`DECISIONS.txt`, `PROJECT-PHASES.txt`, `REFACTOR-STANDARDS.txt`) as SCREAMING `.txt` files,
because they were normative records read by search and grown by append, not explanatory pages.
`PROJECT-PHASES.txt` left the repository on 2026-11-17. The two that remain are still the only
documents a reader of the published site meets as raw text: the build copies them verbatim, so a
link to them opens a page with no theme, no navigation and no way back to where the reader came
from.

The 2026-11-17 survey of scattered documentation (issue #203) found the same split in a second
form: `REFACTOR-STANDARDS.txt` (normative, about 520 lines) and `refactor-standards.md` (the
published page that summarises it) are two files for one topic, and a reader has to know which of
the two decides.

The forces:

- **Navigation.** A site page carries the sidebar and its links; a `.txt` file does not. Every
  link into a `.txt` file is a dead end in the site.
- **Size.** The docs band is 150-250 lines per page, one topic. ADR-010 exempted the ledgers from
  it because they were plain text; converted as they are, they would exceed it two to four times.
- **One source per topic.** A normative file plus a page explaining it drift apart.

## Decision

**1. No document in this repository is a `.txt` file, and no page links to one.** Documentation is
markdown: prose, rules, records and evidence alike. This includes `spike/adr-002/requirements.txt`,
which becomes `requirements.md` (pip reads a requirements file by path, not by extension). Data is
not documentation: text files that are inputs or outputs of the application are not covered.

**2. A document too large for the docs band becomes a small set of linked pages**, not one page
with a waiver: an index page that says what each part covers and links to it, and part pages that
each cover one topic. A normative document and a page explaining it are merged into that one set.

**3. Every page is reachable through a link from another page.** A new page is linked from its
parent (the index of its section, or the page that introduces its topic) and links back to it, so
a reader moves through the docs as HTML pages and never lands on a page with no way in or out.

`REFACTOR-STANDARDS.txt` and `refactor-standards.md` are the first pair converted under this
decision; `DECISIONS.txt` follows once its shape is ruled.

## Consequences

**Positive**

- Every document a reader can reach carries the site's navigation, theme and back links.
- One source per topic: the standards are no longer a normative file and a summary of it.
- The size band applies to every page again; no exemption class remains.

**Negative**

- Code comments, ADRs and pages that cite the `.txt` paths have to be updated, in the change that
  converts each file; links in ADRs are re-pointed under the link-target exception the standards
  allow, their wording untouched.
- Splitting a long document means a reader who searched one file now searches several pages.
- The published URLs of the two `.txt` files stop answering once both are converted.

## Alternatives considered

- **Keep the `.txt` ledgers and state the split in both headers.** Cheapest; the issue #203 survey
  proposed it as one option. Rejected by the owner: it keeps the dead ends in the site and two files
  for one topic. *Rejected.*
- **Convert each ledger to one large markdown page with a size waiver.** One page to search, no
  new links. Rejected: pages of 500 to 1000 lines are the shape the docs band exists to prevent,
  and a waiver would be an exception for the two most-read documents. *Rejected.*
