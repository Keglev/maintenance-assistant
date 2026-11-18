# Architecture and ingestion decisions

Part of the [product decisions](decisions.md). Each point that summarises an ADR links it; the ADR
decides.

## Stack and structure

- **Stack:** Java 21, Spring Boot 4 (3.x is end of life since 2026-06-30), Angular and TypeScript,
  PostgreSQL with pgvector, Keycloak, Docker Compose, GitHub Actions, a Hetzner VPS.
- **Modular monolith first** ([ADR-001](adr/ADR-001-modular-monolith-first.md)): packages
  `ingestion`, `query`, `web`, Spring events for asynchronous work; extracting the ingestion
  service with Kafka is a later step that needs its own ADR.
- **pgvector inside Postgres, no dedicated vector database**
  ([ADR-004](adr/ADR-004-pgvector-for-vector-search.md)): relational filter and vector ranking in
  one query, one backup, no cross-store consistency problem. The scale ceiling is documented.
- **The Java base package stays `com.keglevich.maintenanceassistant`.** A rename would touch every
  file for no functional gain and muddy the history. Robots must not rename it.

## Provider

- **IONOS AI Model Hub** ([ADR-002](adr/ADR-002-eu-hosted-llm-provider.md)), decided on a measured
  two-round spike. A multilingual embedding model is a hard requirement. The documented fallback is
  a second IONOS model, `openai/gpt-oss-120b` (ADR-002, revision of 2026-08-27).
- **Embeddings:** BAAI/bge-m3, 1024 dimensions, so the pgvector column is `vector(1024)`.
- **Chat:** meta-llama/Llama-3.3-70B-Instruct for every role (details on
  [Query path and UI](decisions-query-and-ui.md)).
- **A provider swap stays a configuration change** (OpenAI-compatible API), with three caveats:
  IONOS needs an explicit `encoding_format` (the gateway answers 500 to the SDK's base64 default),
  the `*-migration` model aliases are ignored, and Qwen3.5 models need `reasoning_effort="none"` or
  they return empty content.
- **Plain RestClient, not Spring AI.** Spring AI 2.0 is compatible with Boot 4.1 (measured in the
  [Spring AI spike](https://github.com/Keglev/maintenance-assistant/blob/main/docs/evidence/spring-ai-boot4/RESULTS.md))
  and is declined on proportion: the need is one POST per call, the starter pulls a large
  dependency tree none of which is used, and both failures in the spike came from the client
  assuming real OpenAI. The provider is OpenAI-compatible, not OpenAI. The call sits behind narrow
  `EmbeddingClient` and `ChatClient` interfaces, so adopting a framework later is a second
  implementation, not a rewrite.
- **Typed records for provider responses.** Boot 4.1 ships Jackson 3 as the message-converter
  default while Jackson 2 is still on the classpath, so asking a RestClient for a
  `com.fasterxml` `JsonNode` fails at runtime; typed records bind under either.

## Identity and demo login

- **Keycloak as identity provider** ([ADR-003](adr/ADR-003-keycloak-for-iam.md)): Authorization
  Code Flow with PKCE in the SPA, OAuth2 Resource Server in the backend, realm configuration
  exported and versioned. "Keycloak vs OAuth2" is a category error: OAuth2 and OIDC are protocols,
  Keycloak is an implementation.
- **Demo login uses `login_hint`.** The landing page offers one button per demo user that starts
  the normal Code + PKCE redirect with the username prefilled; the visitor types only the shared
  password. Full one-click login was rejected both ways: the password grant would expose the
  realm's token endpoint to password spraying against every user, and an autofilling login theme
  is a hand-deployed artifact bought for a cosmetic gain. The demo box states the boundary: isolated
  realm, synthetic data only, rate-limited, 15-minute sessions.

## Upload rules

- **No document-language field.** Retrieval is language-agnostic by architecture (bge-m3 embeds
  German and English into one vector space) and the answer language follows the question, never
  the document. A field nobody reads, whose wrong answer would be invisible, is worse than none. If
  a language ever earns a retrieval role, it is detected from the text, not asked for.
- **Four upload guards, all configuration properties:** explicit multipart limits (256 KB file,
  512 KB request), content validation before anything is stored (empty, not `.txt`, NUL byte in
  the first 8 KB), and 10 uploads per minute per user with 429 and Retry-After. A refused upload
  leaves no row and no file. Markdown files are not accepted. The per-user bucket is in memory; the
  real ceiling is the durable daily embedding budget in Postgres. The numbers are in arc42 section
  11.1, "Upload abuse, quantified".
- Measured while building the guards: a controller-local `@ExceptionHandler` never sees
  `MaxUploadSizeExceededException` (it must be `@ControllerAdvice`), its `getMaxUploadSize()`
  returns -1, and an unauthenticated oversized upload answers 401, not 413, because the security
  filter chain runs before multipart parsing.

## Ingestion

- **Pipeline:** upload -> file on the volume -> RECEIVED row -> Spring event -> worker pool ->
  chunk -> embed -> chunks written -> INDEXED. Any failure -> FAILED with a stored reason, row and
  file kept. The event carries only a protocol id: that is the seam for a later Kafka extraction.
- **Upload returns 202, not 201:** the protocol exists but is not searchable yet (NFR-4).
- **The transaction does not span the embedding call;** a slow provider would otherwise hold a
  pooled connection the web layer needs.
- **Chunking is paragraph-based**, not fixed windows: the documents are short and their paragraphs
  are the labelled sections (Symptom / Ursache / Massnahme). Small sections merge up to about 900
  characters, an oversized section splits on sentence ends with 150 characters of overlap, and every
  chunk is prefixed with "machine - error code - title" so it still says what it is about once
  separated from its row.
- **Re-indexing is delete-then-write per protocol,** so the catch-up path and any retry are
  idempotent.
- **The embedding budget is a global daily counter in Postgres** (`embedding_budget`): ingestion
  is background load, so the abuse shape is a re-index storm, not one person clicking. The client
  counts each request as it is served, not the caller on success: the first live run made 150 paid
  calls that a caller-side counter never saw because every one failed while parsing the response.
- Embedding the whole corpus cost hundredths of a cent when measured; cents was the estimate.

[Back to the product decisions](decisions.md)
