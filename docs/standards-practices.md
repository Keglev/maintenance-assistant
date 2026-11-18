# Working practices

The process rules that paid for themselves, and the two ops rules that generalise beyond this
host. Part of the [refactor standards](refactor-standards.md).

## Process

1. **Read first:** verify every premise against the checkout before designing a change. A prompt or
   a plan states what its author verified and marks what it did not.
2. One mission in flight at a time; no manual edits alongside an automated one.
3. Surveys deliver findings tables BEFORE any fix is designed; findings carry evidence and proposed
   severity (FIX / WATCH / ACCEPT).
4. Main is confirmed by CONTENT, not commit titles; re-fetch before concluding a merge failed.
5. **Stop-and-report beats guessing:** when a member resists classification or a rule conflicts,
   the executor stops with evidence and options; the owner rules.
6. One logical change per PR; squash merges; commit messages explain WHY to a future reader, never
   internal ticket codes.
7. **The gate is CI, not local runs:** run only what you touched locally; the full suite gates the
   merge. CI-shaped proofs need CI-shaped environments, with provenance stated. This project has the
   lesson on record: local green is not commit green — a package-private constructor passed from the
   working tree and failed in CI (#39).
8. **No predicted outcome numbers in instructions** — measure, then report what you observed; never
   tune a result to meet an expectation.
9. Errors in the record get corrected in the record (PR bodies, comments, ADRs, these pages), not
   silently left because the code moved on.
10. **Robots never tag**, and robots never grant themselves a waiver.
11. **A bulk reformat is its own commit**, and its SHA goes in `.git-blame-ignore-revs` at the
    repository root. Reviewing a gate must not mean reading a thousand lines of re-indentation, and
    blame must keep answering "who last changed what this line MEANS" rather than "who ran the
    formatter". The file is not automatic: git reads it only for a reader who has set it, once per
    clone — `git config blame.ignoreRevsFile .git-blame-ignore-revs` — and unset, blame simply
    behaves as it always did. GitHub's blame view applies it without being asked. Added 2026-08-25
    with the prettier gate, which is the first reformat this rule had to cover.
    **Under squash merge, the entry is always the merge commit on main, added in the PR after the
    reformat lands.** A branch commit is unreachable from main once the pull request is squashed,
    and git ignores an entry it cannot resolve WITHOUT SAYING SO — the file still exists, still
    looks right, and does nothing. Learned 2026-08-26, one pull request after the rule was written.
12. Two rules from the diagnosis of 2026-08-26, both learned when a reachable provider was reported
    as an outage: **log redaction redacts in place and never drops a whole line**, because a dropped
    line takes the timestamp, the level and the cause with the secret and leaves an incident with a
    hole where its evidence was. **Every incident time carries a zone**, and the host is UTC — a
    bare clock time is two different moments to the two people reading it.

## Ops

Two rules, both bought on 2026-08-27 by the shared-host hand-deploy. The operational log of that
session is in the project handover archived under git tag `archive/handover-2026-11`; what is here
is only the part that generalises. The rules for this host are in the
[deployment view](architecture/07-deployment-view.html).

- **A secrets file is validated by byte checks before it is deployed, never by eye:** line count,
  CR/LF, BOM, and one KEY=VALUE per line, each asserted by a command rather than looked at. Learned
  from an export pipeline on Windows that produced a 44,081-byte file with EVERY NEWLINE STRIPPED —
  12 keys concatenated onto the ends of the preceding values, on one line. It opened in an editor,
  it was the right size, and it was undeployable; a bare regex found 3 of its 12 keys, because a
  key preceded by an alphanumeric character is invisible to one. **The failure is silent on both
  sides:** nothing about the file announces it, and a container started from it fails later and
  elsewhere. The checks cost one command; `file` alone is not enough, because "ASCII text, with
  very long lines" is also what a correct wallet line looks like.
  **A value is never printed to check it.** Names, lengths and counts answer every question above,
  and a length that matches a previously measured length is a stronger check than a value somebody
  read.
- **Before a hand-deploy, `ls -l` the target; ownership is part of the precondition**, not something
  to discover from a permission error halfway through. The deploy account has no sudo by
  provisioning design, so a root-owned file on the host cannot be replaced from it. **Root creates
  and hands over; deploy operates** — a file or directory that root creates is `chown`ed to deploy
  in the same session, or it becomes a wall the next operator meets instead. Learned when
  docker-compose.prod.yml was found root:root 644, sixteen days after a root session created it and
  did not hand it over, and only because a deploy that day finally needed to overwrite it.

[Back to the refactor standards](refactor-standards.md)
