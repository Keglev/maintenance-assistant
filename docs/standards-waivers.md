# Waivers

The waiver register and the accept / watch ledger. Part of the
[refactor standards](refactor-standards.md); the waiver mechanism itself is defined there.

The register was created empty ON PURPOSE rather than after the first waiver, so that the first one
had somewhere to go and could not become a comment nobody collected. First entries below,
2026-08-22.

**Every waived site also carries a comment at the site** saying why the miss is the rule working.
The register says which; the comment says why. A waiver that exists in only one of the two places
is half a waiver: the register alone leaves the next reader deleting a guard to win a branch, and
the comment alone is the thing this register exists to stop.

## Coverage waivers (branch coverage)

| # | Site | Figure | Date | Granted by |
|---|---|---|---|---|
| 1 | `ProtocolChunker.splitOversized` (U1) | 1 branch | 2026-08-22 | Carlos |
| 2 | `ProtocolChunker.lastSentenceEnd`, `i + 1 < text.length()` (U2) | 1 branch | 2026-08-22 | Carlos |
| 3 | `IonosChatClient.preview`, null/blank guard (#81 F2) | 2 branches | 2026-08-22 | Carlos |
| 4 | `IonosChatClient.firstLine` and `IonosEmbeddingClient.firstLine`, the `body == null` side (#81 F3) | 1 branch each, 2 total | 2026-08-22 | Carlos |

**1. ProtocolChunker.splitOversized.** The loop's false exit is unreachable — every iteration leaves
`from` strictly inside the section, so the only way out is the break. Reaching it would need an
empty section, which the sole caller prevents by calling this only for sections longer than
MAX_CHARS. Verified independently of the class: the two methods were copied into a standalone
harness and driven with 60,008 sections honouring the caller's contract, with the branch never
taken; a control run passing an empty section DID take it, proving the harness could observe it.

**2. ProtocolChunker.lastSentenceEnd.** The sole caller passes a window that stops short of the end,
so `i` can never be the last character. The guard is a bounds check on a method that reads one
character past `i`; the guarantee lives in the caller, and a second caller written without it would
read off the end. Same harness and same control as U1.

**3. IonosChatClient.preview.** `complete()` throws on null or blank content BEFORE the truncation
branch that calls preview, and that ordering is deliberate: an empty answer from a reasoning model
is a configuration diagnosis (ADR-002 caveat 3) and must not be reported as a truncation. The guard
stays because a formatting helper must not throw while an exception message is being built. The
uncovered branch is that ordering working.

**4. firstLine, `body == null`.** The only argument ever passed is `getResponseBodyAsString()`,
which builds a String over a byte array that defaults to empty: it returns "" and never null. The
BLANK side is covered, by the empty-body rejection tests (#80, #81) — so the guard is half exercised
and half unreachable, and the unreachable half is a null-safety check on a helper that runs while a
failure_reason is being assembled.

## Coverage waivers (whole class, 0%)

| # | Class | Figure | Date | Granted by |
|---|---|---|---|---|
| 5 | `EmbeddingProvenanceRunner` | 69 instructions, 4 branches | 2026-08-22 | Carlos |
| 6 | `IngestionBacklogRunner` | 23 instructions, 0 branches | 2026-08-22 | Carlos |

**5. EmbeddingProvenanceRunner.** Flag-gated operational wiring — read a report, log it, choose an
exit code. The mechanism it invokes is covered by EmbeddingProvenanceVerifierIT, so the part that
can be wrong about vectors IS tested. Covering the runner would mean standing up a context to assert
on console output, which tests the harness rather than the tool. Exercised operationally instead:
run against production 2026-08-19.

**6. IngestionBacklogRunner.** A conditional bean that calls enqueue once and logs the count; it
carries no branches of its own. The work is covered where the work lives (IngestionBacklogService,
IngestionPipelineIT) and the same operation is reachable through the admin endpoint, which is
covered as a web slice. A test here could only assert that a startup hook calls a method.

## Accept / watch ledger (not waivers)

These are NOT waivers: the code is reachable and testable, the targets are met without it, and the
cost of covering it now is judged higher than the risk of leaving it. A ledger row is a decision to
revisit, not permission to stop counting. Anything here still counts as uncovered in every
published figure.

**A. The 4a residue — 51 branches needing integration-test harnesses.** Status ACCEPT / WATCH,
2026-08-22, decided by Carlos. Classes: EmbeddingProvenanceVerifier, ProtocolUploadController,
ProtocolIntakeService, ProtocolIndexer, ProtocolDocumentService, ModerationController,
CorpusProtocol, IngestionAdminController, HealthController (full list carried in #79's pull request
body). Each needs an IT harness that does not exist yet rather than a unit seam. Both stacks meet
the 85% targets without them, so building nine harnesses now would be coverage work for its own
sake. Revisit when one of these classes is next changed for a feature — the harness is then paid for
by work that needed it. Tracked as GitHub issue #191.

**B. ChatBudget.checkHeadroom over-limit branch (#81 F4).** CLOSED, covered 2026-08-22 — recorded
here because #81 proposed it as a ledger row and it turned out to be cheap: it went into
QueryPipelineIT, which already owns a real ChatBudget and the chat_budget table, so it cost no new
Spring context.

[Back to the refactor standards](refactor-standards.md)
