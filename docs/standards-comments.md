# Comments in code

What a comment in source code may contain, and in which characters. Part of the
[refactor standards](refactor-standards.md). Adopted by the owner on 2026-11-19 for every language
in the repository: Java, TypeScript, HTML and CSS, YAML, shell, Dockerfiles and SQL. Documentation
pages follow [Docs](standards-docs.md) instead.

The rules are language- and project-neutral. Sources: Ousterhout, *A Philosophy of Software Design*,
chapter 13; Martin, *Clean Code*, chapter 4; Spertus, "Best practices for writing code comments"
(Stack Overflow blog); Google Java Style, section 7, and Google's engineering practices.

## What a comment contains

- **C1** A comment states what the code cannot show: the reason for a decision, a constraint, an
  invariant, a unit, a boundary (inclusive or exclusive), a side effect, an ordering dependency.
- **C2** A comment a reader could write from the code next to it is deleted (Ousterhout's test). So
  is one that the name of the thing already says.
- **C3** Interface comments (file and class headers, Javadoc, TSDoc, the banner of a CI asset)
  describe the contract and how to use the unit, never its implementation.
- **C4** Implementation comments sit above the block they explain. A long method may carry one line
  per major block, saying what the block achieves at a higher level than the code.
- **C5** A workaround or a library quirk names the library and version, the source that shows the
  behaviour (release note, issue, source file) and the condition under which it can be dropped.
- **C6** Code that looks redundant, unidiomatic or simplifiable carries a comment, so nobody
  simplifies it away. A bug fix whose reason is not visible in the code says what it guards
  against.
- **C7** No history narrative ("was X", "added in", "changed because the old version..."): version
  control holds history. A reference is allowed when it points to the reason: an ADR, a section of
  these standards, a decision record, an external issue or document. Pull-request numbers and the
  internal ids of surveys and session records are not used: a reader of the repository cannot
  resolve them, so the reason is written out or the ADR is cited instead.
- **C8** No commented-out code, no closing-brace comments, no position or section-marker banners
  inside code, no bylines or authors. In list-type files (.gitignore, .dockerignore, a pom
  dependency list, nginx location blocks) a one-line label that sorts the entries below it is
  allowed; decorative rule lines are not.
- **C9** An inline comment is at most three lines. A longer explanation belongs in the header, the
  Javadoc, or an ADR or docs page the comment cites. Configuration files follow the same limit.
- **C10** A wrong or stale comment is a defect: fixed in the change that touches the code, or
  recorded as a finding by a survey.
- **C11** Tests: the test name carries the what. A comment explains only a non-obvious arrange
  step, why a value or a fixture is chosen, or why an assertion pair (absence plus presence)
  exists.

**Deciding whether a line needs a comment.** It needs one when C1, C5 or C6 applies, that is when a
reader would ask "why is this here, why this way, why this value". Otherwise no comment is the
correct state, and a missing comment is not a finding. A survey records the trigger (C1, C5 or C6)
for every comment it proposes to add, and the rule (C2, C7, C8 or C9) for every comment it proposes
to delete.

## Characters: ASCII only

- Comments in code are written in English and in ASCII only. Arrows become `->`, the
  multiplication sign `*`, em and en dashes `-`, ellipses `...`.
- Not affected: string literals, user-visible text (templates, i18n files), test data, console
  output that a script prints, and the documentation pages.
- Measured per file with a classifier that separates comments from strings and template text; a
  plain grep for non-ASCII bytes also counts the German product texts and is no measurement.
- Existing files are brought in line by the comment survey of the refactor wave, not by
  unrelated changes.

[Back to the refactor standards](refactor-standards.md)
