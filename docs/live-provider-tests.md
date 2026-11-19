# Live provider tests

Most of the backend's tests run against stubs and a throwaway database, on every pull request. A
small group does not: the **live provider tests** call the real, paid LLM provider against the
fully indexed corpus. They re-take the measurements this project quotes, so that a number in a
decision record stays something anyone can reproduce rather than a claim.

| Test class | What it measures |
|---|---|
| `QueryDemoVerificationIT` | the three demo cases and their similarity scores ([ADR-002](adr/ADR-002-eu-hosted-llm-provider.html)) |
| `RetrievalBaselineIT` | retrieval over the full question set; writes `backend/src/test/resources/retrieval/baseline.md` ([ADR-008](adr/ADR-008-retrieval-measurement.html)) |
| `DuplicateSimilarityCalibrationIT` | where the duplicate-detection threshold comes from ([ADR-006](adr/ADR-006-insider-threat-and-protocol-moderation.html)) |

## Why they are not part of CI

- **Every run costs money.** The provider bills per call, and a pull request would pay again on
  every push.
- **They depend on an outside service.** A provider outage or rate limit would turn a merge gate
  red for a reason that has nothing to do with the change under review.
- **They need an indexed corpus.** A normal build starts from an empty database; these tests need
  every protocol chunked and embedded through the real provider first.
- **Dependabot pull requests cannot read repository secrets.** Without the key the tests would
  skip there, so a gate built on them would not even run on every pull request.

What every pull request does test is the same code paths against the local provider stub: the unit
and integration tests, and the end-to-end suite, which uses a stub of the provider API.

## How they are kept out

Each class carries the `@LiveProviderTest` annotation (`backend/src/test/java/.../support`), which
combines two guards:

1. **The JUnit tag `llm-live`.** Failsafe excludes it by default (`backend/pom.xml`,
   `failsafe.excludedGroups`), so a normal build does not run, count or report these tests at all.
2. **A condition on `LLM_API_KEY`.** Without the key the tests skip, so running the profile on a
   machine without a key spends nothing.

The Maven profile `llm-live` reverses the filter: it runs only the tagged tests, and turns JaCoCo
off for that run, since a run of these tests alone is a measurement, not a coverage report.

## Running them in GitHub

The workflow **backend-llm-live-it** runs monthly and whenever it is started by hand (Actions,
backend-llm-live-it, Run workflow; see [When they run](#when-they-run)). It brings up a fresh
pgvector database, seeds the corpus from the checked-out commit,
indexes it through the provider (`.github/scripts/index-live-corpus.sh`) and then runs
`mvn verify -P llm-live`. It reads the repository secret `LLM_API_KEY` and fails at once if the
secret is missing, so a run can never be green while measuring nothing.

A run started by hand can measure another chat model: the input `chat-model` (for example a
fallback candidate under [ADR-002](adr/ADR-002-eu-hosted-llm-provider.html)) replaces
`LLM_CHAT_MODEL` for that run only. Left empty, and always on the monthly schedule, the run
measures the configured model.

Each run uploads the artifact `llm-live-report-<run>`: the retrieval baseline report (also shown on
the run summary page, including its cost line), the failsafe reports and the application logs of
the indexing phases.

## Running them locally

From `backend/`, with the development stack running and the corpus indexed (`ops/README.md`
in the repository):

```powershell
$env:LLM_API_KEY = '<your provider key>'
mvn verify -P llm-live                                      # all live provider tests
mvn verify -P llm-live "-Dit.test=QueryDemoVerificationIT"  # one class
```

Without `-P llm-live` an `-Dit.test=` naming one of these classes finds nothing to run, because the
default build excludes the tag.

## Adding a live provider test

Annotate the class with `@LiveProviderTest` and name it `*IT`. Nothing else changes: the default
build ignores it, and the profile and the workflow pick it up. A test that needs data beyond the
seeded corpus has to create it and remove it again, as `DuplicateSimilarityCalibrationIT` does.

## When they run

Once a month, on the 2nd at 03:40 UTC, and by hand after any change to the chat model, the prompt
or the embedding model. One run costs roughly EUR 0.03 (measured on 2026-11-16: the embedding of
the test queries is priced in the run's report; the indexing and the chat answers follow from the
unit costs in [ADR-002](adr/ADR-002-eu-hosted-llm-provider.html)). The schedule is the `schedule:`
entry under `on:` in `.github/workflows/backend-llm-live-it.yml`.

[Back to the overview](overview.html)
