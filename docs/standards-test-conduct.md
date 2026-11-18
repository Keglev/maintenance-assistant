# Test conduct

Part of the [refactor standards](refactor-standards.md). Test-file sizes are on
[Size bands](standards-sizes.md).

- **Zero assertion changes on any spec move**; bodies move verbatim. When a split forces threading
  (a fixture parameter), prove identity: extract every test body before and after, compare byte for
  byte, and report the count.
- **New tests need vacuity proofs**: break the mechanism under test and watch the test fail, then
  restore. One break proving two claims is complete; a probe breaking something nothing claims
  proves nothing. An absence assertion is paired with proof the intended path ran, or it can pass
  for the wrong reason.
- Storage and state isolation is **each test file's own responsibility**. Accepted mechanisms: a
  clear registered inside the suite block; a per-test setup helper that clears; a shared fixture
  whose clear counts for all its consumers. Entry clears defend the file that owns them; exit
  clears are the only cleanup the next file gets — a file that writes shared state and never clears
  on exit is a finding even when green. Do NOT trust root-level global hooks under coverage or
  shared workers.
- Fixtures modules: shared helpers earn membership at two or more consuming files; a fixtures
  module may ALSO exist at a single consumer solely to relieve an above-alarm spec of scaffolding
  (stubs, constants, TestBed config) — but never tests, assertions, or helpers that decide what a
  case asserts. Fixtures never depend on their consumers. Shared mutable or uniqueness-bearing
  state (counters, sequences) is mandatory fixtures content when tests commit data.
- Integration-test splits keep **identical context configuration** across the new files so Spring's
  context cache serves them all. On this project that includes the Testcontainers configuration: a
  differing container definition is a second context and a second startup.
- **Message assertions follow layer ownership.** Service-level tests assert the exception type and
  its message (where the error handler passes messages through, the sentence IS the wire contract;
  elsewhere it is the only observable a unit test has). Web-slice tests own the envelope: HTTP
  status, the message exactly as the wire carries it (including any handler prefix), and the
  machine code where one exists — on this project the machine codes are real contract
  (`PROTOCOL_IDENTITY_LOCKED`, `EMPTY_FILE`, `UNSUPPORTED_TYPE`, `NOT_TEXT`) and a test that pins
  only the status is under-asserting. Neither "never assert message text" nor "the message is never
  part of the contract" survives contact with a handler that passes messages through — **read the
  handler before writing the rule.** Where an exception SUBTYPE carries contract weight, at least
  one test pins the subtype, not a parent that a type check would also accept.
- Named or reflective access from tests (helpers reaching members by string) is a SEAM: when
  members move, rewrite the helper as a routing map so every test body stays untouched; the
  invariant that matters is that what every case asserts never changes.
- E2E and visual suites are a **separate ledger** and are deliberately outside the coverage figures.
  Their promotion condition is [ADR-007](adr/ADR-007-end-to-end-testing-strategy.md)'s, not these
  standards'.

[Back to the refactor standards](refactor-standards.md)
