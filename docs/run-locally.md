# Run it locally

How to run the whole application on a workstation: the database and Keycloak in compose, the
backend from Maven or the IDE, the frontend on the Angular dev server. The end-to-end suite runs
against exactly this stack; its own rules are in
[`frontend/e2e/README.md`](https://github.com/Keglev/maintenance-assistant/blob/main/frontend/e2e/README.md).

## The pieces

The compose file has no application service: the backend and the frontend run outside it.

| Piece | How | Port |
|---|---|---|
| PostgreSQL + pgvector | `docker compose -f docker/docker-compose.yml up -d postgres` | 5432 by default; 5433 if `POSTGRES_PORT=5433` is set in `docker/.env` |
| Keycloak, realm `maintenance`, themed | `docker compose -f docker/docker-compose.yml up -d keycloak` | 8081 |
| Backend | `cd backend && mvn spring-boot:run` | 8080 |
| Frontend | `cd frontend && npm start` (the e2e suite starts it itself when none is running) | 4200 |

Before the first start, copy `docker/.env.example` to `docker/.env`. What a healthy start looks
like, the demo users and how to change the realm are in
[`docker/README.md`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/README.md).

The backend reads nothing from `docker/.env`. Every setting has a working default in
`application.yml`; to override one, export it in the shell that starts the backend.

## No LLM key is required

Indexing needs an embedding and a query needs an answer, but neither has to be a *paid* one: point
the backend at the stub in
[`frontend/e2e/provider-stub/`](https://github.com/Keglev/maintenance-assistant/blob/main/frontend/e2e/provider-stub/server.mjs)
and the whole application, the end-to-end suite included, runs for free.

```bash
# one terminal, from frontend/
node e2e/provider-stub/server.mjs --port 8099

# the backend, against the stub — see the stub's header for what is and is not faked
LLM_BASE_URL=http://127.0.0.1:8099/v1 LLM_API_KEY=stub \
INGESTION_BACKLOG_ON_STARTUP=true CORPUS_SEED_ENABLED=true \
QUERY_SIMILARITY_THRESHOLD=0.30 mvn spring-boot:run

# and the e2e suite, from frontend/, with the re-index test switched on
E2E_LLM=1 npm run e2e
```

Against a **real** provider instead, set `LLM_API_KEY` and leave `LLM_BASE_URL` alone. Both were
exercised; the re-index test passes in ~33 s against IONOS and ~11 s against the stub.

> **WARNING — running against the stub WRITES STUB VECTORS INTO THE DEVELOPMENT DATABASE.**
> `CORPUS_SEED_ENABLED=true INGESTION_BACKLOG_ON_STARTUP=true` with `LLM_BASE_URL` pointed at the
> stub indexes protocols with hashed trigrams instead of bge-m3. Those vectors are orthogonal to
> every real question, so the affected protocols become **unretrievable** while every row still
> reads `INDEXED` — the defect ADR-008 found and #62 repaired, which reached the database exactly
> this way. After a stub run, run the provenance check and re-index anything it names:
>
> ```bash
> MAINTENANCE_OPS_VERIFY_EMBEDDINGS=true mvn spring-boot:run    # exits 1 when the index is mixed
> ```
>
> CI is unaffected: `.github/actions/e2e-stack` brings up a throwaway database per run.

> **The threshold override is not a cheat.** `application.yml` records that 0.55 is *bge-m3-specific*
> and that any embedding-model change invalidates it. The stub is a different embedding model, so it
> gets its own calibration — measured at 0.42–0.45 for a relevant question and 0.10–0.13 for an
> irrelevant one, which leaves 0.30 comfortably inside the gap. The Mode A / Mode B distinction is
> preserved, not disabled.

## Related

- [Live provider tests](live-provider-tests.md) — the tests that call the paid provider, and how
  to run them by hand.
- [Deployment view](architecture/07-deployment-view.html) — the production stack, where all the
  pieces run together in compose.

[Back to the docs home](index.html)
