# Scripts and workflows

Part of the [refactor standards](refactor-standards.md).

## Sizes

- Shell scripts ~70 code lines; build-and-gate scripts (Node or shell, invoked by CI, living inside
  the project they serve) 70-150 (>200); the header states what it produces, how CI invokes it,
  and its failure mode.
- Workflow YAML ~150 code lines. Inline shell in a workflow longer than five lines becomes a
  script. WAIVED 2026-08-28 (Part 5 ruling, K7) for run blocks whose content is a single remote
  command over ssh/scp and for e2e invocation blocks: a script file would be a second place to read
  one command. A block that computes, loops or branches is not covered by the waiver.

## Every path is gated by the workflow that exercises it

AMENDED 2026-11-13: the mechanism is **claimants**, not paired path lists.

- Three claimant workflows (backend-ci, frontend-ci, docs-pr-check) each own one required check
  (build-and-test-backend, -frontend, -docs) and run on EVERY pull request with NO pull_request
  path filter — a workflow skipped by a path filter never reports its required check and the PR
  can never merge.
- Each claimant's first job calls the reusable claimant-gate.yml (claimant-gate.sh), which decides
  from the PR's diff whether the family is touched; push triggers on main keep their path filters.
- The partition lives in claimant-gate.sh alone: no path belongs to two families, and **backend is
  the catch-all**, so a new path is gated by default rather than stranded.
- **The aggregator** is each claimant's last job, carries the required check name, and runs under
  `if: always()`, because a skipped job reports success to branch protection. Its verdict is a
  shared script (aggregate-required-check.sh): fail when the claimant gate itself did not succeed,
  pass when the gate says the family is untouched, otherwise require the family's build to succeed
  and every conditional suite (e2e, visual) to succeed or be skipped by its own gate.
- A check that more than one claimant needs is a **reusable workflow** the claimants call behind a
  diff gate (frontend-e2e.yml and frontend-e2e-visual.yml behind e2e-relevant.sh), never a copy. A
  gate's deny/skip list enumerates only what the check never reads; an unknown path defaults to
  RUN.

## Paid calls, images, failures and dependencies

- **A test that calls the paid provider never gates a merge.** It carries `@LiveProviderTest`:
  failsafe excludes the `llm-live` tag by default, the `llm-live` Maven profile selects only it,
  and backend-llm-live-it.yml runs it against a freshly indexed corpus monthly and by hand
  (workflow_dispatch) after a model, prompt or embedding change. See
  [live provider tests](live-provider-tests.md).
- **Every image is scanned before it can merge or publish.** backend-ci and frontend-ci build their
  image on every pull request (never pushed), the deploy workflows build it again on main and scan
  it before the registry login and the push. image-scan.sh writes a CycloneDX SBOM (artifact) and
  fails on a CRITICAL or HIGH finding that has a fix, unless .trivyignore accepts it with a reason,
  who and when, and a review date. The image job is REQUIRED in the aggregator: a skip where the
  family runs is red. Trivy is a pinned release with its SHA-256 in the script, never the
  trivy-action or setup-trivy (tags hijacked 2026-03-19, CVE-2026-33634). The frontend image also
  passes its contract (nginx -t, non-root user); its security headers are set by Caddy and checked
  at the public URL after each deploy.
- **Checks must fail loudly.** A check that passes for the wrong reason (wrong directory,
  line-ending-blind comparison, a probe that never ran) is discarded, not read as green.
- **Dependency groups admit only minor and patch; a major is one PR, one decision.** A batch is a
  convenience for changes that need no thought, and a major version is never one of those — it is
  also the update most likely to be constrained by a peer range nobody remembers. Learned
  2026-08-26 (#104): one TypeScript major inside thirteen routine npm updates failed all three
  frontend checks before a test ran.

[Back to the refactor standards](refactor-standards.md)
