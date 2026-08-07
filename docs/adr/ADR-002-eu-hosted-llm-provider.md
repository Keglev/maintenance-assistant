# ADR-002: EU-Hosted LLM & Embedding Provider

| | |
|---|---|
| **Status** | **Accepted** — decided on the measured spike, see *Evidence* |
| **Date** | 2026-08-06 (proposed 2026-08-05) |
| **Deciders** | Project owner (solo) |
| **Related** | Implements NFR-1 (data residency) and NFR-7 (cost control); constrains NFR-2 (answer modes) and NFR-4 (latency). Evidence: [`spike/adr-002/RESULTS.md`](https://github.com/Keglev/maintenance-assistant/blob/main/spike/adr-002/RESULTS.md), [PR #14](https://github.com/Keglev/maintenance-assistant/pull/14) |

## Context

NFR-1 requires all processing on EU infrastructure with pay-as-you-go pricing and no training on
submitted data (DSGVO positioning). The system needs two model types: an **embedding model** that
MUST be multilingual — the corpus is mixed DE/EN and a German query must retrieve English
protocols, and nothing is translated anywhere — and a **chat/completion model** for answer
generation with citations. Both providers under consideration expose OpenAI-compatible APIs, so
the integration code is largely identical either way.

A multilingual embedding model is a **hard requirement**, not a preference. A provider that cannot
serve one is disqualified regardless of price.

The original proposal named Nebius Token Factory as primary, on the expectation that it served
BGE-M3-class multilingual embeddings. That expectation was wrong, and the spike found it out. The
decision below reverses the proposal on measured evidence rather than on vendor claims.

## Decision

**Primary: IONOS AI Model Hub. Documented fallback: Nebius Token Factory.**

**Models**

- **Embeddings: `BAAI/bge-m3`, 1024 dimensions.** The pgvector column is sized accordingly
  (`vector(1024)`) — this is a schema commitment for the Phase 2 Flyway migration, not a runtime
  setting.
- **Chat: `meta-llama/Llama-3.3-70B-Instruct`** as the quality default, with
  **`Qwen/Qwen3.5-9B`** as the fast alternative. The final per-role choice is made in **Phase 3**
  against the production prompt, not here — both are measured as viable and the trade between them
  depends on prompt shape.

**Similarity threshold (the NFR-2 Mode A / Mode B switch)**

The threshold is a **configuration property, not a literal**. Initial value **0.583**, to be
revalidated against the real ~150-protocol corpus in Phase 2.

**Provider swap**

A provider swap remains a configuration change (base URL + model names, OpenAI-compatible APIs),
with three measured caveats that the code must carry:

1. IONOS requires an **explicit `encoding_format`** on embedding calls — its gateway returns
   HTTP 500 on the OpenAI SDK's default (`base64`).
2. The `*-migration` model ids in IONOS' `/v1/models` are deprecated aliases and must be ignored
   by any model-selection logic.
3. `Qwen/Qwen3.5-9B` is a reasoning model and needs **`reasoning_effort="none"`**; without it, it
   spends the entire output budget on its reasoning field and returns empty content.

## Evidence

Measured in [`spike/adr-002/`](https://github.com/Keglev/maintenance-assistant/tree/main/spike/adr-002)
over two rounds against both live APIs; full numbers in
[`RESULTS.md`](https://github.com/Keglev/maintenance-assistant/blob/main/spike/adr-002/RESULTS.md),
review discussion in [PR #14](https://github.com/Keglev/maintenance-assistant/pull/14).

**IONOS won retrieval on every query.** Its cross-language penalty — the score a German query
loses when the matching protocol is English — is **−0.011** against Nebius' **−0.033**, and it
ranked the correct protocol first on all three queries. Cross-language retrieval, the blocking
question of this ADR, is answered *yes* on both providers; IONOS is simply better at it.

**Embedding-model concentration risk decided it.** IONOS serves **three** multilingual embedding
models; Nebius publicly serves **exactly one**, which makes the hard requirement of this ADR a
single point of failure with no in-provider recovery. `BAAI/bge-m3` — the model class this ADR was
originally written around — is **not offered by Nebius at all**.

**Data residency is stronger, and it is the point of NFR-1.** IONOS is a German company and the
endpoint is `de-txl`, **Berlin**; Nebius' EU region is Helsinki. Both satisfy NFR-1, but NFR-1
exists for a DSGVO narrative aimed at German industrial users, and a Berlin data centre states it
better than a Finnish one. IONOS also prices in **EUR**, which removes FX noise from a
€-denominated cost story.

**Latency separated the two on an identical model.** `meta-llama/Llama-3.3-70B-Instruct` — the
same weights — stayed inside NFR-4's 30 s ceiling on IONOS (median ~11 s) while Nebius breached it
on two of three repeats (up to 101 s). Both providers offer a small model comfortably under the
10 s target (~3 s), so the demo was shippable either way, but only IONOS leaves the 70B model as a
live option.

**Citation compliance and answer language are prompt and application concerns, not provider
concerns — and they are verifiably enforceable.** Round 2 showed a few-shot worked example takes
every tested model to **100 % per-claim citation**, and an explicit language pin fixes the English
refusals both Llama deployments produced. A structured JSON output shape
(`{answer_language, claims:[{text, source}]}`) works first-try on both providers and makes an
uncited claim *unrepresentable* — that is the application-side enforcement path for Phase 3.

**Cost decided nothing.** Every measured provider/model combination lands between €1 and €5 per
month at demo volume, against a VPS that costs an order of magnitude more.

## Consequences

**Positive**

- The hard requirement is met with **margin and redundancy**: three multilingual embedding models
  at the primary provider, and a fallback provider already measured end to end.
- A provider swap stays a configuration change (OpenAI-compatible APIs) — no vendor lock-in — with
  the three caveats above written down rather than discovered later.
- The Mode A / Mode B threshold mechanism is proven to work, with a comparable clean separation on
  both providers, so NFR-2's routing is implementable as designed.
- Demo cost is low single-digit €/month, ~5 % of total running cost (the VPS is ~95 %).

**Negative / residual risks**

- **No provider-side hard spending cap.** IONOS billing was checked manually: only **cost alerts**
  exist, not a hard ceiling. A **€7 cost alert is configured**. NFR-7 layer 1 therefore does not
  exist as originally designed, and **cost control rests on the application layer** — per-user rate
  limiting, a global daily budget counter, the `max_tokens` cap and the query cache. This is
  acceptable because the measured worst case is small: abuse is bounded in cents per thousand
  queries, and the alert catches a runaway before it becomes money. It is a deliberate, documented
  deviation, not an oversight.
- **Latency figures are indicative, not conclusive.** Small sample (a handful of calls per model),
  taken over a residential connection, on shared serverless capacity with no SLA at this tier.
  Individual calls varied far more than token counts explain, so the numbers are queue-dominated.
  **Revalidate from the production host in Phase 3** before the chat model is fixed.
- **The threshold 0.583 is `bge-m3`-specific.** Absolute cosine values are not portable across
  embedding models. Any change of embedding model invalidates it and requires re-running the spike
  script to re-tune. It must stay configurable.
- **The few-shot citation prompt must exclude the Mode-B / refusal path.** Leakage was observed:
  a model appended a citation to a refusal, where the rules require citing nothing. The prompt or
  the application must keep the two paths separate.
- Open-source models are weaker than frontier models; acceptable because RAG narrows the task to
  "summarise retrieved protocols with citations", which mid-size models handle well.
- IONOS lists per-token prices only in its public price list, not through the API (Nebius exposes
  them via `GET /v1/models?verbose=true`), so cost reporting cannot be derived from the API alone.

## Open questions closed by the spike

1. **DE↔EN retrieval** — **yes**, on both providers; IONOS by a margin. §2 of RESULTS.md.
2. **Citation prompt** — **yes**, with a few-shot example rather than a rule; 100 % per-claim
   citation on every model tested. §7 of RESULTS.md.
3. **Cost per query** — measured: €0.0001–0.0008 per query, €1–5/month at demo volume. §5.
4. **Billing controls** — **answered outside the spike, and the answer is no.** IONOS offers cost
   alerts only, no hard cap; a €7 alert is configured. See the residual risk above.
5. **Spring AI compatibility with Spring Boot 4.1** — **still open**, deliberately: the spike was
   written in Python so it evaluated the *provider*, not the Java integration. The fallback (a
   plain `RestClient` against the OpenAI-compatible endpoint, ~50 lines) keeps this ADR valid
   either way and is unaffected by anything measured here. Note caveat 1 above — the
   `encoding_format` deviation is exactly the kind of thing a Spring AI client can trip over and a
   `RestClient` will not. Resolve it in Phase 3 when the query module is built.

### Note, 2026-08-07 — the Java integration, measured

This ADR left two things open that only running the Java side could settle. Both are now measured;
the evidence is in [`spike/spring-ai-boot4/RESULTS.md`](https://github.com/Keglev/maintenance-assistant/blob/main/spike/spring-ai-boot4/RESULTS.md).

**Spring AI vs a plain `RestClient` → `RestClient`.** Not for incompatibility: Spring AI 2.0.0, the
first generation on Spring Framework 7, auto-configures cleanly on Boot 4.1 and returned correct
1024-dimension vectors from IONOS. It is declined on proportion — the starter brings the OpenAI
Java SDK, OkHttp, the Kotlin stdlib, `azure-identity`, a template engine and three unused
auto-configurations, for one `POST /v1/embeddings`. The embedding call sits behind a narrow
interface so the Phase 3 chat path can revisit this without a rewrite.

**The `encoding_format` caveat reproduced exactly, in Java.** This ADR wrote: *"a Spring AI client
can trip over it, a plain RestClient sending JSON floats will not."* It does:

```
com.openai.errors.InternalServerException: 500: Network error: json: cannot unmarshal string
into Go struct field Embedding.data.embedding of type []float32
```

— byte for byte the error a raw `curl` produces with `"encoding_format":"base64"`. One property
(`spring.ai.openai.embedding.options.encoding-format: float`) fixes it, and the base URL must
include `/v1`. Both are recorded for whoever revisits this.

**The similarity threshold needs lowering.** This ADR set 0.583 from the Python spike and asked for
re-validation against the real corpus. Measured against all 151 indexed protocols:

| Demo scenario | Best similarity | Wanted |
|---|---:|---|
| E-47 on Presse 3 (German query, German protocols) | **0.695** | Mode A |
| DE query → EN protocol, belt mistracking on FB-04 | **0.577** | Mode A |
| Mode B gap: dosing on AB-02, no protocol covers it | **0.502** | Mode B |

At 0.583 the cross-language case falls **below** the threshold and would be refused as ungrounded —
the demo it exists for would break. The separation the corpus actually shows is between 0.577 and
0.502, so the threshold belongs around **0.54–0.55**. Not changed here: the threshold is a query-path
property and this PR ships no query path. Recorded so Phase 3 tunes it against numbers rather than
against the Python figure.

**Cost, measured rather than estimated.** Embedding all 150 corpus protocols: 150 provider calls,
27,713 input tokens, **EUR 0.00055**. The estimate was "cents"; the measurement is hundredths of one.

### Note, 2026-08-07 — the query path, measured against the production prompt

This ADR deferred three things to Phase 3: the per-role chat model, the threshold value, and the
Java chat integration. All three are now measured, against the real 150-protocol corpus and the
prompts the application actually ships, through the query path itself rather than a script that
approximates it. It is reproducible: `QueryDemoVerificationIT`, skipped unless `LLM_API_KEY` is set.

**Threshold: 0.55, and the three demo cases clear it.**

| Demo scenario | Best similarity | Wanted | Result |
|---|---:|---|---|
| E-47 on Presse 3 (German question, German protocols) | **0.6896** | Mode A | Mode A ✅ |
| DE query → EN protocol, belt mistracking on FB-04 | **0.5566** | Mode A | Mode A ✅ |
| Mode B gap: dosing on AB-02, no protocol covers it | **0.4563** | Mode B | Mode B ✅ |

The figures differ slightly from the note above because the questions are the demo's own wording
rather than the tuning script's, which is the more honest test — 0.583 would still have refused the
cross-language case. **The margin on that case is thin: 0.0066.** It is the number to re-measure
first after any change to chunking, to the corpus, or to the embedding model, and it is the reason
the threshold stays a property.

**Chat model: `meta-llama/Llama-3.3-70B-Instruct` for every role.** No per-role split, because the
evidence does not ask for one and inventing one would be complexity with nothing behind it.

| | Llama-3.3-70B | Qwen3.5-9B |
|---|---|---|
| E-47, Mode A | 11 claims / 3 sources, 12.9–24.5 s | **48 claims** / 3 sources, 23.3 s |
| DE→EN, Mode A | 3 claims / 1 source, 4.9 s | **failed to ground → fell through to Mode B**, 9.0 s |
| Mode B gap | 5 steps, correct, 9.7 s | 4 steps, correct, 4.5 s |
| Operator, Mode A | 4 claims, repair withheld, escalation given, 18.4 s | 20 claims, 19.9 s |
| Citation validity | every cited label was retrieved | every cited label was retrieved |
| Answer language | German throughout, incl. Mode B | German throughout, incl. Mode B |

Two findings decide it, and the first one is disqualifying: **Qwen3.5-9B did not produce a groundable
answer for the cross-language case.** It retrieved correctly — retrieval is the embedding model's
job and identical for both — then returned 14 completion tokens with no usable claim, and the
application's citation validation did what it is there for and fell through to Mode B. The demo that
exists to show a German question reaching an English protocol would have displayed "no source in the
corpus". Second, Qwen fragments: 48 claims for an answer Llama expresses in 11, which is worse to
read and, at these sizes, **not even faster** — the spike's ~3 s figure was measured on a one-line
refusal, and under the production prompt Qwen's verbosity cancels its speed advantage entirely.

Qwen3.5-9B remains a supported configuration swap (`LLM_CHAT_MODEL`), with the
`reasoning_effort="none"` caveat handled by the client from the model id rather than by a flag.

**Latency, from a residential connection: median ~13 s end to end (13 measured queries, 4.9 s to
38.9 s).** Inside NFR-4's 30 s ceiling at the median, above the 10 s target, and **one of the 13
breached the ceiling** at 38.9 s. The variance is queue time, exactly as this ADR predicted — the
identical question returned in 12.9 s and in 24.5 s minutes apart, with the same token counts.
Re-measuring from the Hetzner host remains open and is now the more urgent half of that residual
risk, because the answer is no longer comfortably inside the ceiling.

**Two integration traps, neither of which any fake could have surfaced.**

1. **Constrained decoding runs away without a worked example.** On the Mode B path Llama-3.3-70B
   emitted `{ "answer_language": "de",` and then spent its *entire* output budget on whitespace —
   twice, at two different caps. Raising the cap could not help; the answer was never getting
   longer, only later. Mode A never did it, and the only structural difference was that Mode A
   carries a worked example of compact JSON. Adding one to Mode B — with no source, label or
   protocol in it, so the separation this ADR requires is untouched — fixed it, and `minItems` /
   `maxItems` bound the list.
2. **The spike's `max_tokens=400` does not transfer.** It was measured on a one-line refusal. A
   claim-per-statement answer over four protocols is 481 tokens, so the cap is 1200. The cap is
   still runaway protection rather than a quality constraint, but the number was carried over
   unexamined and would have failed the E-47 demo.

**Spring AI, revisited as promised and declined again.** The note above said the chat path was where
advisors, chat memory and tool calling might earn their weight. Built, they do not: there is no
conversation to remember, no tool to call, and the one advisor-shaped concern — citation enforcement
— must be checked against *this query's* retrieved chunks, which no framework can know. The chat
call is one POST behind a narrow interface, as the embedding call is.

**Cost, measured:** the day's counter after the Llama verification stood at **20 calls, 24,321 input
and 8,774 output tokens, ~EUR 0.0215** at Llama list price — about EUR 0.001 per answer. Extrapolated
to the 6000 queries/month the ADR assumes, ~EUR 6.50/month, the same order as §5's EUR 4.94 estimate
and still a rounding error against the VPS.

## Alternatives considered

- **Nebius Token Factory as primary** (the original proposal) — publicly visible per-token pricing,
  the broader chat catalogue, and USD prepaid trial credit that would have given NFR-7 a natural
  hard ceiling. It lost on the measurements: one public embedding model against three, no `bge-m3`,
  weaker cross-language retrieval, Helsinki rather than Berlin, and a 5–10× latency penalty on an
  identical 70B model. *Retained as the documented fallback* — it is measured, it works, and the
  swap is a configuration change.
- **OpenAI / Anthropic APIs** — best model quality, but US processing contradicts the project's
  core DSGVO claim. *Rejected.*
- **Self-hosted model on a GPU server** — full data control, but GPU hosting costs (≥ ~€100/month)
  and ops effort are disproportionate for a portfolio, and contradict the pay-as-you-go story.
  *Rejected.*
- **Spring AI as a mandatory abstraction** — desirable, but compatibility with Spring Boot 4.1 is
  still unverified; see open question 5. *Conditional.*
