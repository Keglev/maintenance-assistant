# Docs

Part of the [refactor standards](refactor-standards.md).

- 150-250 lines max per markdown page; one page, one topic. Short is fine; the rule flags filler,
  not brevity. A document that would exceed the band becomes an index page plus linked part pages,
  never one page with a waiver ([ADR-013](adr/ADR-013-markdown-only-docs.md)).
- **No `.txt` documents and no links to them.** Documentation is markdown; data files of the
  application are not documentation ([ADR-013](adr/ADR-013-markdown-only-docs.md)).
- **Every page is reachable through a link from another page.** A new page is linked from its
  parent (its section index, or the page that introduces its topic) and ends with a link back to
  it ([ADR-013](adr/ADR-013-markdown-only-docs.md)).
- Mermaid over ASCII art; diagrams at or under 15-20 nodes. A diagram is a `.mmd` file in a
  `diagrams/` directory, rendered to SVG at build time (generate-diagrams.mjs) and embedded as an
  image; pages load no diagram script and no CDN. build-docs.sh fails on an inline mermaid block.
- **Published prose states only what the checkout shows.** Figures carry provenance. No
  aspirational content. A claim needing a survey is omitted, not generalised.
- **Docs state the current state only.** No "was X until", no amendment or adoption dates, no
  withdrawn rules or waivers, no "previously" notes: when and why something changed is in git, the
  pull request and the ADRs.
- **No test counts or coverage percentages in prose.** A page says what is tested and links the
  coverage reports CI publishes; a coverage target is a rule, not a figure, and may be stated.
- **Version tables name the major version only** ("Spring Boot 4", never "4.1"); the build files
  hold the exact versions.
- **No internal tracking ids in docs.** Survey, session, ruling or finding ids (and pull-request
  numbers used as a reason) cannot be resolved by a reader: an open item is described in words
  with a pointer to the file it concerns. Ids already written inside an ADR stay, because ADRs are
  immutable.
- Cite by stable keys (property names, method names), never by line numbers — line citations break
  on the next edit.
- Decision records are **immutable** once merged and their working session closes: corrections go
  in a new record or a dated amendment block; the decision, status and alternatives are never
  edited. Number them, index them, and keep the nav in step.
  AMENDED 2026-11-18 (ruled by the owner): **one exception, for link targets.** When a repository
  file an ADR links to moves or is removed, the link is re-pointed to a permalink pinned to the last
  commit before the change (`blob/<sha>/...` or `tree/<sha>/...`), so it shows exactly the file the
  decision rested on and never breaks again. The link text and every word around it stay as they
  are. check-repo-urls.sh in docs-pr-check fails on a `blob/main` or `tree/main` URL whose path is
  missing from the checkout, so a move cannot leave one behind.
- **Every published surface carries the same anchor home.** A report tree reached from the docs site
  must offer the way back to it.
- Explanatory markdown pages under `docs/` are lowercase-kebab; the ADR files and ADR-TEMPLATE stay
  SCREAMING, because they are immutable records rather than prose — see
  [ADR-010](adr/ADR-010-docs-tree-conventions.md) and its 2026-11-18 note.
- A citation inside an applied Flyway migration is **frozen**: it is not updated when the file it
  names is renamed, because the checksum covers comments and a repair is a production operation —
  see [ADR-010](adr/ADR-010-docs-tree-conventions.md).
- Hand-written HTML that belongs to the site theme (the two landing pages) lives in `docs/_theme/`;
  markdown content lives in `docs/` — see [ADR-010](adr/ADR-010-docs-tree-conventions.md).

[Back to the refactor standards](refactor-standards.md)
