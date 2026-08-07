# Spike: Spring AI on Boot 4.1 against IONOS — 2026-08-07

Closes the question left open by ADR-002 and DECISIONS.txt: *"Spring AI: use IF compatible with
Boot 4.1; fallback plain RestClient."* The ADR-002 spike was Python on purpose, so it measured the
provider and not the Java integration. This measures the Java integration.

**Outcome: plain `RestClient`.** Spring AI is *not* incompatible — it was made to work — and the
reasoning for not using it is below, along with what it would take to change the decision back.

## What was run

`spike/spring-ai-boot4/` — a throwaway Boot 4.1 project with `spring-ai-starter-model-openai`,
pointed at the IONOS gateway, embedding two strings with `BAAI/bge-m3`.

| | |
|---|---|
| Boot | 4.1.0 (the version the backend runs) |
| Spring AI | 2.0.0 — the first generation on Spring Framework 7, which is what Boot 4.1 ships. 1.1.x is Framework 6 / Boot 3.5 and would not have resolved. |
| Endpoint | `https://openai.inference.de-txl.ionos.com/v1` |

## Findings, in the order they happened

**1. Boot 4.1 compatibility is not the problem.** The starter resolved, the context started, and
`OpenAiEmbeddingModel` was auto-configured. The compatibility question DECISIONS.txt raised is
answered: **compatible**.

```
Started SpringAiBoot4Spike in 3.493 seconds
EmbeddingModel implementation: org.springframework.ai.openai.OpenAiEmbeddingModel
```

**2. First call: `404`.** Spring AI 2.0 delegates to the official `openai-java` SDK (4.39.1), which
expects the API version in the configured base URL. Spring AI 1.x appended `/v1` itself. Setting
`base-url: …ionos.com/v1` fixed it. Not a defect — a contract that changed between Spring AI
generations and is easy to get wrong against a non-OpenAI host.

**3. Second call: `500` — the exact caveat ADR-002 predicted.**

```
com.openai.errors.InternalServerException: 500: Network error: json: cannot unmarshal string
into Go struct field Embedding.data.embedding of type []float32
```

Byte-for-byte the same error a raw `curl` produces with `"encoding_format":"base64"`:

```console
$ curl … -d '{"model":"BAAI/bge-m3","input":["…"],"encoding_format":"float"}'    -> http 200
$ curl … -d '{"model":"BAAI/bge-m3","input":["…"],"encoding_format":"base64"}'  -> http 500
  {"error":{"httpStatus":500,…,"message":"Network error: json: cannot unmarshal string into
   Go struct field Embedding.data.embedding of type []float32"}}
```

So Spring AI 2.0 sends base64 by default, inherited from the OpenAI Java SDK, and the IONOS gateway
cannot parse its own provider's default encoding. ADR-002 wrote: *"a Spring AI client can trip over
it, a plain RestClient sending JSON floats will not."* That prediction was correct, and this is the
measurement of it.

**4. It can be fixed, with one line.** `spring.ai.openai.embedding.options.encoding-format: float`:

```
vectors returned : 2
dimensions       : 1024
latency ms       : 4284
usage            : DefaultUsage{promptTokens=24, completionTokens=0, totalTokens=24}
first 5 floats   : [-0.04107782, -0.0011919903, -0.0315419, -0.01784929, 0.0051347273]
=== probe finished without error ===
```

1024 dimensions, as ADR-002 requires.

## The decision, and why

Spring AI **works**. It is not being rejected for incompatibility, and saying otherwise would
misrepresent this spike. It is being declined on proportion:

- **What we actually need is one HTTP call.** `POST /v1/embeddings` with a list of strings and a
  fixed encoding, returning arrays of floats. That is ~40 lines over `RestClient`.
- **What it costs.** `spring-ai-starter-model-openai` pulls in the OpenAI Java SDK, OkHttp, the
  Kotlin stdlib, `azure-identity`, a template engine, chat-client, chat-memory and tool-calling
  auto-configuration — none of which this application uses. That is a large transitive surface,
  and a security-update surface, for one POST.
- **The abstraction leaks exactly where we are non-standard.** Both failures above happened
  *because* the client assumes it is talking to OpenAI. Our provider is OpenAI-*compatible*, not
  OpenAI, and that is the permanent condition of this project (ADR-002 also documents Nebius as the
  fallback). Where the abstraction helps least is precisely where we live.
- **We write the surrounding logic either way.** Dimension assertion, batching, retry with backoff,
  the daily budget counter and the token log all sit outside the embedding call regardless of who
  makes it.
- **Provider swap stays a config change either way** — base URL, key and model name are properties
  in both designs.

The portfolio argument is the recorded decision and its evidence, not which dependency won.

## What would change it back

Phase 3 introduces the chat path — prompting, citation enforcement, possibly tool calls and
advisors. That is where Spring AI's abstractions actually earn their weight, and this spike says
they are available on Boot 4.1 when that decision is taken. The embedding call in the application
therefore sits behind a narrow interface (`EmbeddingClient`), so adopting Spring AI later is a new
implementation of that interface and not a rewrite.

Two things to carry forward if that happens: the base URL must include `/v1`, and
`encoding-format: float` must be set explicitly or IONOS returns 500.

## Reproducing

```bash
cd spike/spring-ai-boot4
IONOS_API_KEY=… mvn -q spring-boot:run
```

Remove `encoding-format: float` from `application.yml` to see finding 3.
