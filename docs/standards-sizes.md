# Size bands

Code-line bands per file type, target (alarm). Counting, the watched band and waivers are defined
on the [refactor standards](refactor-standards.md) page; waived files are in the
[waiver register](standards-waivers.md).

## Backend (Java / Spring)

| File type | Target | Alarm | Note |
|---|---|---|---|
| Controller | 50-150 | >200 | doc every endpoint past 200 |
| Service | 100-250 | >300 | |
| Repository | 20-80 | >150 | native SQL for sanctioned bypasses only; never repo-injects-repo |
| Entity | 30-100 | >150 | |
| Config | 50-150 | >200 | |
| Security | 50-200 | >300 | |
| DTO / record | 20-80 | >120 | mapping resolves in the service |
| Exception advice | 10-50 | >100 | status mapping concentrated in ONE handler |
| Plain exception class | 5-30 | >60 | |
| Validation | 20-80 | >120 | |
| Enum | 10-40 | >80 | |
| Event listener | 10-50 | >80 | |
| Test: controller / integration | 250 | >300 | |
| Test: others | 150 | >200 | test method <=20 code lines |

- **Methods of every kind** (controller, service, repository, configuration, client, record,
  advice): target 20, alarm 30, hard cap 50 code lines. A file type exempt from its file band
  (framework integration) is not exempt from the method band: a long builder or DSL chain takes a
  written waiver. Counted as defined under counting methods on the
  [refactor standards](refactor-standards.md) page. AMENDED 2026-11-19 (ruled by the owner):
  extends the earlier service-only method band to every method.
- Doc every public class and method; omit the author and version tags; pick one exemplary file's
  prose as the register standard and raise others TO it. AMENDED 2026-08-28 (Part 5 ruling, K1):
  exempt are DI constructors, one-line accessors, `@Override` implementations of a documented
  interface, and handlers whose prose is the `@Operation` text — their doc lives on the interface
  or in the published contract. Register standards: ModerationController (controllers),
  ProtocolIndexer (services).
- Test naming: `method_state_expected` OR a camelCase sentence naming state and expectation
  (`aNormalTextFileIsAccepted`). AMENDED 2026-08-28 (Part 5 ruling, K2): mixing both styles within
  one class is the finding. **The name never buys body** — the test-method band above (<=20 code
  lines) stands. No doc comments on test methods; the contract sentence goes on the class.
- Suppressions carry a same-line WHY; zero bare suppressions is the enforced state.

## Frontend (TypeScript / Angular)

| File type | Target | Alarm | Note |
|---|---|---|---|
| Component | 40-100 | >150 | |
| Dialog | 50-120 | >160 | |
| Page (routed view) | 60-140 | >180 | |
| Service | 40-120 | >160 | |
| Signal store | 40-90 | >120 | |
| Pipe / directive | 10-40 | >80 | |
| Guard / interceptor | 10-50 | >80 | |
| Utils | 20-80 | >120 | |
| Template (.html) | soft 150 | >200 | header comment at 40+ code lines |
| Function | aim <40 | >75 | |
| Spec: component / page | 100-350 | >450 | |
| Spec: service | 40-150 | >200 | |
| Fixtures modules | exempt | | |

- Class and service level doc (summary + WHY remark), not file headers, because Compodoc publishes
  from the types.
- Spec files carry a plain block comment naming the contract under test, an out-of-scope note, and
  their SIBLING files when a suite is split.
- Shell/card division: the shell owns fetches, derivations and error surfacing; cards take inputs
  and emit only outputs that map to a shell decision.
- When a view grows past its band with per-domain state: extract per-domain collaborators
  (component-provided injectables that die with the view) rather than global stores — same
  lifecycle, no refresh-semantics change.
- i18n: one namespace per file per language, with a CI parity gate on membership AND ordering.
  German is the review reference. NO IN-CODE FALLBACKS.

Script and workflow sizes are on [Scripts and workflows](standards-ci.md); docs page sizes on
[Docs](standards-docs.md).

[Back to the refactor standards](refactor-standards.md)
