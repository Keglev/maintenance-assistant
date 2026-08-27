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

> **Note added 2026-08-27** — the sentence above is the decision as taken and stays as written. The
> fallback half of it is **superseded**: Nebius retires the model this ADR names on 2026-08-31, and
> the documented fallback is now IONOS + `openai/gpt-oss-120b`. See *REVISION 2026-08-27* at the end
> of this file. The primary is unchanged.

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

> **Note added 2026-08-27** — there are now **five**. Two more were measured on 2026-08-26 and are
> stated in *REVISION 2026-08-27* §6: `openai/gpt-oss-120b` rejects `reasoning_effort` with an
> HTTP 400, and `Qwen/Qwen3.5-397B-A17B` returns HTTP 500 when `reasoning_effort` and a strict
> `json_schema` are sent together. The count "three" above is the count as of 2026-08-06.

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
   *Superseded 2026-08-26 as to the cap, not as to the reasoning: 481 was measured over a
   ten-protocol corpus, and the re-measurement of 2026-08-26 (PROJECT-PHASES, diagnostics wave A3)
   puts the deployed model's 95th percentile at 575 and its maximum at 586 over 21 samples. The cap
   is 2100 since #112 and is sized for the documented fallback, not for the incumbent. The sentence
   above stays because the argument it makes — a cap measured on the wrong prompt does not
   transfer — is exactly the argument that moved the number again.*

**Spring AI, revisited as promised and declined again.** The note above said the chat path was where
advisors, chat memory and tool calling might earn their weight. Built, they do not: there is no
conversation to remember, no tool to call, and the one advisor-shaped concern — citation enforcement
— must be checked against *this query's* retrieved chunks, which no framework can know. The chat
call is one POST behind a narrow interface, as the embedding call is.

**Cost, measured:** the day's counter after the Llama verification stood at **20 calls, 24,321 input
and 8,774 output tokens, ~EUR 0.0215** at Llama list price — about EUR 0.001 per answer. Extrapolated
to the 6000 queries/month the ADR assumes, ~EUR 6.50/month, the same order as §5's EUR 4.94 estimate
and still a rounding error against the VPS.

### Note, 2026-08-07 — latency from the production host, and the residual risk closed

Latency was measured three ways — from a residential connection through the query module (PR #24),
from the production host directly against the provider, and end-to-end through the deployed stack.
All three show the same shape, which attributes the variance to provider queueing rather than to any
network path: the identical request returned in 12.9 s and 24.5 s minutes apart with identical token
counts.

That is the finding. The two new measurements:

**(a) The provider leg, from the Hetzner host.** Direct `POST /v1/chat/completions`,
`meta-llama/Llama-3.3-70B-Instruct`, forced ~500-token completions so every call does comparable
work, 8 calls:

| | | | | | | | |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 8.4 s | 11.1 s | 13.7 s | 14.2 s | 15.8 s | 16.8 s | 18.0 s | 29.7 s |

Median **~15 s**, spread 8.4–29.7 s. The same distribution as the residential run — **the datacenter
path adds nothing**, which is the question this ADR left open and the reason it was worth asking.

**(b) End-to-end through production.** `https://maintenance.smartsupply.com.de/api/query`, techniker
token, 5 distinct questions with the cache cold:

| | | | | |
|---:|---:|---:|---:|---:|
| 4.5 s | 4.9 s | 5.5 s | 7.4 s | 17.2 s |

Median **5.5 s**. The 17.2 s case is the E-47 four-protocol Mode A answer — the longest real
completion, exactly as expected.

**Conclusion: NFR-4 is met at the median and at the worst observed value.** The end-to-end numbers
*beat* the provider-leg numbers, and that is not a contradiction: real answers are far shorter than
the forced 500-token probe, so the probe measures a worse case than the application produces. The
earlier 38.9 s breach remains possible on a bad queue day — this tier is shared serverless capacity
with no SLA, which is what makes the tail long rather than anything in our control. **Documented
residual, no action.** The mitigations already in place are the right ones: a client-side timeout,
the "still working" state in the search view, and the `max_tokens` cap that bounds the worst case.

This closes the residual risk the ADR recorded under *Negative / residual risks* ("latency figures
are indicative … revalidate from the production host in Phase 3").

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
## REVISION 2026-08-27 — the documented fallback moves to a second IONOS model

The decision of 2026-08-06 stands: IONOS AI Model Hub remains primary, for the reasons measured in
*Evidence*. What changes is the **fallback**, which had stopped existing without anyone noticing.

Every figure cited here is recorded in `docs/PROJECT-PHASES.txt` under the diagnostics wave, in the
entries named — **A1** (provider catalogue), **A2** (candidates at cap 1200), **A3** (cap
calibration and retest), **R1** (the cap ruling) and **R2** (the fallback ruling). The tables live
there and are deliberately not copied here: one home per fact.

### 1. The Nebius fallback is withdrawn

Nebius Token Factory retires **`meta-llama/Llama-3.3-70B-Instruct` on 2026-08-31** (A1, from its
August 2026 deprecation notice). That is the model this ADR names as the fallback, so the fallback
was invalid as written — and it was announced by email, which is how it nearly went unnoticed.

Three reasons, not one, and each stands on its own:

1. **The model retires.** A documented fallback that no longer exists is worse than none, because
   it is the plan somebody reaches for during an incident.
2. **No Nebius credential exists.** The only LLM-family variables on the production host are
   `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_CHAT_MODEL` and `LLM_EMBEDDING_MODEL` (A1). Nebius's own
   `/v1/models` could not be called, so nothing about it can be verified from here.
3. **The swap would be a code change.** This ADR's central claim about fallbacks is that the change
   is "base URL + model names". Two of the three replacements Nebius names — `nvidia/Nemotron-3_5-
   Lightning` and `MiniMaxAI/MiniMax-M3` — fall outside `isReasoningModel()`, whose predicate is a
   substring match on `qwen3.5`. Adopting one means editing that predicate, and **the sentence no
   longer holds for the model it named.**

`BAAI/bge-m3` appears in no Nebius notice (March, June or August), so the embedding half of the old
fallback was never at risk. It is moot now regardless.

### 2. The documented fallback is IONOS + `openai/gpt-oss-120b`

Ruled 2026-08-26 (**R2**) and effective since **R1** shipped in #112.

**It was chosen by measurement against this application's answer contract, never by name.** It
qualified on 2026-08-26 at a cap of 2100 or more (**A3**: 21 of 21 completions finished with
`finish_reason=stop`, worst latency 9.1 s against NFR-4's 30 s ceiling, 95th percentile 1642
completion tokens) and it **failed at the old cap of 1200** (**A2**: it truncated the grounded case
in two runs of three). That is why the cap ruling and the fallback ruling are one decision: naming
a model that has been measured not to work at the deployed cap would be documenting a plan that
cannot be executed.

**The switch, in full.** It remains a configuration change:

1. `LLM_CHAT_MODEL=openai/gpt-oss-120b` in `/opt/maintenance-assistant/.env.prod`.
2. Restart the backend — a hand-deployed file, so **OPS RULE 3** applies: back the file up first,
   and the change is not live until the container is recreated.
3. **Verify by hand, in the browser, not by running the harness.** `QueryDemoVerificationIT` points
   at a local database and the demo profile; against production it would prove nothing about the
   deployment and would spend real budget doing it. Ask the three demo questions as a Techniker:
   - *"Presse kommt nicht auf Druck, Fehler E-47, was tun?"* on **PR-03** — expect Mode A, with
     citations that open.
   - *"Band läuft nach rechts aus dem Lauf, Material fällt herunter"* on **FB-04** — expect Mode A
     in German over an English protocol.
   - *"Dosierung ist ungenau, die Füllmenge schwankt"* on **AB-02** — expect the labelled Mode B
     card and no citations.

**Cost is close to neutral.** `openai/gpt-oss-120b` is \$0.17 input / \$0.71 output per million
tokens against the incumbent's \$0.71 / \$0.71, but it emits roughly 2.8× the tokens (A3), so the
cheaper input rate is spent on the longer answer rather than saved.

**Reasoning control: the predicate stays exactly as it is.** `gpt-oss-120b` **rejects**
`reasoning_effort` with `HTTP 400 — "Harmony does not support reasoning_effort='none'"`, so it must
NOT match `isReasoningModel()`, and it does not. Widening the predicate to cover it would turn a
working model into a 400. Measured, not assumed (A2).

### 3. The gap this leaves, stated rather than implied

**There is no tested fallback for a full IONOS outage.** Both models now named — primary and
fallback — are served by one provider from one gateway. That is a single point of failure, and it
is written down here because a risk that is recorded is a risk somebody can decide about, while one
implied by an out-of-date fallback is a trap.

Two candidates were measured and rejected on 2026-08-26; neither is a fallback:

- **`Qwen/Qwen3.5-397B-A17B`** — IONOS returns **HTTP 500** for every request that carries both
  `reasoning_effort:"none"` and a strict `json_schema`, which is exactly the body this application
  sends. Deterministic across ~20 requests, with two controls isolating it to the combination. A
  support ticket is drafted (**A5**). Do not re-measure until IONOS answers.
- **`mistralai/Mistral-Small-24B-Instruct`** — **six of fifteen cases breached NFR-4's 30 s**, the
  worst at 81.9 s, and one run hit the client's own 45 s timeout twice. It never came close to the
  token cap; latency alone disqualifies it, and a larger cap cannot make a model faster.

### 4. Pricing correction — the EUR argument is withdrawn

The decision above says IONOS "prices in **EUR**, which removes FX noise from a €-denominated cost
story". **That is not true today.** IONOS quotes AI Model Hub tokens in **USD**, and the page does
not localise: fetched on 2026-08-26 with `Accept-Language: de` and with `en`, it returned
byte-identical content with the same `$` figures (A3, M5).

**The EUR argument is withdrawn. It changes nothing else.** The Berlin (`de-txl`) data centre, the
GDPR and NFR-1 residency argument, the multilingual embedding requirement, the cross-language
retrieval measurement and the NFR-4 latency all stand, and they are what decided this ADR. A reason
that stopped being true has to be corrected where it is written, not left standing because the
conclusion survives it.

### 5. Monitoring — the catalogue is watched now

`.github/workflows/catalogue-watch.yml` runs monthly (06:17 UTC on the 1st) and on demand, and
fails when `meta-llama/Llama-3.3-70B-Instruct`, `BAAI/bge-m3` or `openai/gpt-oss-120b` stops being
listed.

**It watches for an absence because absence is the only signal there is.** Every entry of IONOS'
`/v1/models` carries exactly `created`, `id`, `object` and `owned_by` — no lifecycle field, no
retirement date, and `created` is the response timestamp rather than a per-model date (A1). The
retirement path is a documentation page and an email, so a red run means *read
[the models page](https://docs.ionos.com/cloud/ai/ai-model-hub/models) and open a ledger row*, never
*production is down*.

### 6. The caveats are now five

The *Decision* section lists three measured caveats the code must carry. The measurements of
2026-08-26 add two, both about the same parameter and pulling in opposite directions:

4. **`openai/gpt-oss-120b` rejects `reasoning_effort` entirely**, with `HTTP 400 "Harmony does not
   support reasoning_effort='none'"`. The reasoning-model predicate must not match it.
5. **`Qwen/Qwen3.5-397B-A17B` returns `HTTP 500` when `reasoning_effort` and a strict `json_schema`
   are sent together** on the IONOS gateway, while either alone succeeds. Caveat 3 remains true of
   that model — it *is* a reasoning model and does need the parameter — which is what makes the
   combination unusable rather than merely awkward.

Together they say something worth carrying beyond these two models: **`reasoning_effort` is a
per-model property, measured per candidate, and never inferred from a family name.**
