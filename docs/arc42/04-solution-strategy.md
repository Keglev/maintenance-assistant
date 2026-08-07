# 4. Solution Strategy

| Quality goal (§1.2) | Solution approach | Decision |
|---|---|---|
| Verifiable answers | Retrieval-augmented generation over an indexed protocol corpus. Retrieval hits above a similarity threshold produce **Mode A** with a citation per claim; below the threshold the system states that nothing matched and may offer a labelled **Mode B** suggestion. The mode is runtime behaviour, not a stored attribute. | DOMAIN-MODEL §2, NFR-2 |
| Data residency | Every component is self-hosted on one EU VPS; the only outbound call is to an EU-hosted, OpenAI-compatible LLM endpoint — **IONOS AI Model Hub**, Berlin (`de-txl`), with Nebius Token Factory as the documented fallback. | [ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md) |
| Role-correct answers | Keycloak issues JWTs carrying realm roles; the backend validates them as an OAuth2 Resource Server and applies role-based **answer filtering** server-side — the role shapes the generated content, not just page access. | [ADR-003](../adr/ADR-003-keycloak-for-iam.md), NFR-3 |
| Operability & solo delivery | One Spring Boot application, internally modularised into `ingestion` / `query` / `web`, with in-process Spring events for the asynchronous indexing flow. Three containers total. | [ADR-001](../adr/ADR-001-modular-monolith-first.md) |
| Simple, consistent storage | One PostgreSQL instance with pgvector: relational filter and cosine ranking in a single query, one backup, no cross-store consistency problem. | [ADR-004](../adr/ADR-004-pgvector-for-vector-search.md) |
| Cost control | Application-side per-user rate limiting + global daily budget + capped answer length + query cache, plus daily token-usage logging. Exhaustion produces a friendly message, never an error page. The provider-side layer originally planned **does not exist**: IONOS offers cost alerts, not a hard cap (a €7 alert is configured), so the application layer carries NFR-7 alone — see ADR-002's residual risks. | NFR-7, [ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md) |

## 4.1 Decomposition

Three internal modules, drawn so that the Phase 1 boundary is the Phase 2 extraction seam:

- **`ingestion`** — accepts protocols (form or file), stores the original on a volume, extracts
  text, chunks it, requests embeddings, writes chunks, drives the status lifecycle
  `RECEIVED → INDEXED / FAILED`.
- **`query`** — embeds the question, runs the filtered vector search, applies the similarity
  threshold to pick Mode A or B, builds the prompt including the role constraint, and assembles the
  answer with its citations.
- **`web`** — REST endpoints, security configuration, JWT-to-authority mapping, request validation.

`ingestion` and `query` communicate with the rest of the system through Spring application events;
in Phase 2 that transport becomes Kafka topics without a change to the domain model.

## 4.2 Key technology decisions

Java 21 · Spring Boot 4.1 (3.x EOL since 2026-06-30) · Angular + TypeScript, thin · PostgreSQL +
pgvector · Keycloak · EU-hosted LLM via an OpenAI-compatible API · Docker Compose · GitHub Actions ·
Hetzner CX33 (x86_64, 8 GB; revised from CAX/arm64, see DECISIONS.txt). Inference runs on the
**IONOS AI Model Hub**: `BAAI/bge-m3` for embeddings (**1024 dimensions**, which fixes the pgvector
column width) and `meta-llama/Llama-3.3-70B-Instruct` / `Qwen/Qwen3.5-9B` for generation, with the
per-role choice made in Phase 3 ([ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md)). Spring AI is
adopted only if it is compatible with Boot 4.1; that is still unverified — the ADR-002 spike was
written in Python so it measured the provider, not the Java integration — and the fallback is a
plain `RestClient`. Spring Modulith is an optional later upgrade to enforce the ADR-001 module
boundaries with tests.

## 4.3 Approach to the data model

Three entities — `MACHINE` → `PROTOCOL` → `CHUNK` — where the chunk is the *search* unit, the
protocol is the *citation* unit and the machine is the *filter* unit. Users are deliberately not in
the database: identity and roles live in Keycloak and the application only reads token claims.
`CHUNK` carries a denormalised `machine_id` and `error_code` so a filtered vector search is one
query. Details and rationale: [DOMAIN-MODEL.md](../DOMAIN-MODEL.md).

## 4.4 Delivery strategy

A walking skeleton first: Phase 1 ends when hello-world *with login* runs on the VPS through the
pipeline. Data and ingestion follow in Phase 2, the RAG query and answer modes in Phase 3, tests,
OpenAPI, deployment and the demo walkthrough in Phase 4. Full plan:
[PROJECT-PHASES.txt](../PROJECT-PHASES.txt).
