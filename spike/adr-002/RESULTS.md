# ADR-002 spike results — EU-hosted LLM provider

**Run date:** 2026-08-06 · **Scripts:** [`spike.py`](spike.py) (round 1), [`round2.py`](round2.py) (round 2)
**Raw output:** [`results.json`](results.json), [`round2.json`](round2.json)
**Status of this document: DRAFT.** It reports what was measured and argues a
recommendation. The ADR decision itself is the project owner's.

Both providers are now measured end to end. The spike ran in two rounds:

- **Round 1** (`spike.py`) — discovery, retrieval, threshold, citations,
  refusal, cost and latency, per provider, with the original strict prompt.
- **Round 2** (`round2.py`) — the three defects round 1 found (citation
  discipline, refusal language, latency) attacked with prompt engineering and
  an output cap, re-measured against **all four chat models on both
  providers**. See [§7](#7-round-2--fixing-what-round-1-broke).

## Comparison table

| | **Nebius Token Factory** | **IONOS AI Model Hub** |
|---|---|---|
| Evaluated | yes, live API | **yes, live API** |
| Endpoint / region | `api.tokenfactory.nebius.com`, model pages say `eu-north1` (Finland) | `openai.inference.de-txl.ionos.com` — **`de-txl` = Berlin** |
| Billing currency | USD | EUR |
| Models offered (`GET /v1/models`) | 27 | 20 (+3 deprecated `*-migration` aliases) |
| Embedding model used | `Qwen/Qwen3-Embedding-8B`, **4096 dims** | `BAAI/bge-m3`, **1024 dims** |
| Multilingual embedding choice | **exactly one** — Qwen3-Embedding-8B is the only embedding model publicly served | **three** — `BAAI/bge-m3`, `sentence-transformers/paraphrase-multilingual-mpnet-base-v2`, `Qwen/Qwen3-VL-Embedding-8B` (plus EN-only `bge-large-en-v1.5`) |
| DE→DE retrieval (query a) | correct, rank 1, 0.6934 | correct, rank 1, **0.7101** |
| **DE→EN retrieval (query b)** | **correct, rank 1, 0.6603** | **correct, rank 1, 0.6989** |
| No-match query (query c) | best 0.4231, correctly nothing relevant | best 0.4677, correctly nothing relevant |
| Cross-lang penalty vs same-lang baseline | −0.033 | **−0.011** |
| Margin over the best same-language distractor (query b) | +0.211 | +0.162 |
| Threshold separation | clean, margin **0.2372** → proposed **0.54** | clean, margin **0.2312** → proposed **0.583** |
| Corpus embedding | 1943 tokens (194.3/protocol), 2.56 s | 1636 tokens (163.6/protocol), **1.27 s** |
| Query embedding latency | 2.4–5.6 s | 1.1–6.4 s |
| API compatibility | clean OpenAI SDK defaults | **two deviations**, see [§1](#1-model-discovery-and-api-compatibility) |
| Chat models measured | `Llama-3.3-70B-Instruct`, `Qwen3-30B-A3B-Instruct-2507` | `Llama-3.3-70B-Instruct`, `Qwen3.5-9B` |
| **Llama-3.3-70B latency** (round 2, ×3, candidate prompt) | **24.1 / 49.6 / 101.5 s — fails NFR-4 badly** | **7.1 / 10.9 / 13.0 s — meets the 30 s ceiling, misses the 10 s target** |
| Small model latency (round 2, ×3) | Qwen3-30B-A3B: **2.4 / 3.3 / 3.4 s** | Qwen3.5-9B: **2.9 / 3.0 / 5.6 s** |
| Citation compliance, round 1 prompt | Llama 3/3 · Qwen3-30B **1 cite / 5 sentences** | Llama 3/3 · Qwen3.5-9B 2/2 |
| **Citation compliance, round 2 prompt** | Llama **4/4** · Qwen3-30B **4/4** | Llama **4/4** · Qwen3.5-9B **7/7** |
| Refusal correctness (nothing invented) | both models, always | both models, always |
| Refusal language, round 1 prompt | **Llama refused in English** · Qwen German | **Llama refused in English** · Qwen German |
| **Refusal language, round 2 prompt** | **German on both** | **German on both** |
| Structured JSON (`json_schema`, strict) | supported, 100 % parseable, every claim sourced | supported, 100 % parseable, every claim sourced |
| Cost per query, round 2 prompt | **$0.000209** (Llama) / **$0.000171** (Qwen3-30B) | **€0.000823** (Llama) / **€0.000137** (Qwen3.5-9B) |
| Cost at demo volume (6000 q/month) | $1.25 / $1.03 | €4.94 / **€0.82** |
| Embedding 150 protocols (one-off) | $0.00029 | €0.00049 |
| Price transparency | chat prices readable from the API (`?verbose=true`); embedding price console-only | full public price list in EUR; **no** pricing in the API |

**Bottom line of the measurements:** ADR-002's hard requirement — multilingual
retrieval — **passes on both providers**, and IONOS is slightly better at it.
Round 1's two blocking defects (citation discipline, refusal language) turned
out to be **prompt bugs, not model limits**: round 2 fixed both on all four
models. What does *not* fix is Llama-3.3-70B's latency on Nebius, and that is
now the sharpest difference between the two providers — **the identical model
is 5–10× faster on IONOS**.

## 1. Model discovery and API compatibility

**Nebius: 27 models.** Exactly one embedding model is publicly served,
`Qwen/Qwen3-Embedding-8B` (4096 dims). `bge-multilingual-gemma2` and
`bge-en-icl` appear in the catalogue but have no public endpoint (dedicated
deployment only), and `BAAI/bge-m3` — the model class ADR-002 was written
around — **is not offered at all**. On the chat side the catalogue is broad
(Llama-3.3-70B, several Qwen3 sizes, GLM, Nemotron, Kimi, gpt-oss-120b …). No
latency-optimised serving tier exists, so the Llama latency below cannot be
bought away.

**IONOS: 20 models.** Three of the ids are deprecated compatibility aliases
(`bge-m3-migration`, `bge-large-en-v1.5-migration`,
`paraphrase-multilingual-mpnet-base-v2-migration`); the spike filters them and
uses the plain ids. Embedding side: **`BAAI/bge-m3` (1024 dims)** plus a
multilingual MPNet and a VL embedding model — three usable multilingual
options against Nebius' one. Chat: `Llama-3.3-70B-Instruct`,
`Qwen3.5-9B`, `Qwen3.5-397B-A17B`, `Mistral-Small-24B-Instruct`,
`gpt-oss-120b`, `Meta-Llama-3.1-405B-FP8` and others (full list in Appendix C).

**Two IONOS API deviations had to be worked around** — relevant to ADR-002's
"a provider swap is a base-URL change" claim, because they are exactly the
kind of thing that claim assumes away:

1. **Embeddings.** The OpenAI SDK requests `encoding_format="base64"` by
   default. The IONOS gateway cannot decode that and answers **HTTP 500**
   (`cannot unmarshal string into Go struct field Embedding.data.embedding of
   type []float32`). Pinning `encoding_format="float"` fixes it; Nebius
   accepts the same call, so one code path still covers both. A Java client
   built on `RestClient` would send JSON floats anyway and never hit this —
   but a Spring AI or OpenAI-SDK-based client can.
2. **Deprecated aliases in `/v1/models`.** Any code that picks a model by
   substring can land on a `*-migration` id.

Neither is a blocker. Both mean the swap is a *small patch*, not a *config
change* — worth one honest sentence in the ADR's Consequences section.

## 2. Retrieval — the DE→EN case

Corpus: 10 protocols, 7 German / 3 English, one machine and fault each. The
**only** protocol about hydraulic press error E-47 is English (P-08). Two
German protocols about hydraulic presses exist (P-01 oil leak, P-07 planned
hydraulic service) as same-language, same-machine-type noise.

**Nebius — `Qwen/Qwen3-Embedding-8B` (4096 dims)**

| Query | Top-1 | Top-2 | Top-3 | Verdict |
|---|---|---|---|---|
| **(a)** Kompressor / Netzdruck | **P-05** (de) 0.6934 | P-08 (en) 0.4351 | P-01 (de) 0.4029 | hit, rank 1 |
| **(b)** Presse Fehler E-47 | **P-08** (en) 0.6603 | P-01 (de) 0.4491 | P-05 (de) 0.4469 | **hit, rank 1, cross-language** |
| **(c)** Lackierroboter | P-03 (de) 0.4231 | P-10 (en) 0.3905 | P-06 (de) 0.3256 | correctly nothing relevant |

**IONOS — `BAAI/bge-m3` (1024 dims)**

| Query | Top-1 | Top-2 | Top-3 | Verdict |
|---|---|---|---|---|
| **(a)** Kompressor / Netzdruck | **P-05** (de) 0.7101 | P-08 (en) 0.5467 | P-02 (de) 0.5338 | hit, rank 1 |
| **(b)** Presse Fehler E-47 | **P-08** (en) 0.6989 | P-01 (de) 0.5367 | P-07 (de) 0.5314 | **hit, rank 1, cross-language** |
| **(c)** Lackierroboter | P-03 (de) 0.4677 | P-10 (en) 0.4442 | P-05 (de) 0.4073 | correctly nothing relevant |

Both models do the thing that matters: the English protocol outranks both
German press protocols on a German query, so the match is on symptom
semantics, not on language or on the token "Presse"/"press". That is the
behaviour DECISIONS.txt assumes when it rules out translation anywhere in the
pipeline.

**bge-m3 is the better of the two, on two counts.** It scores higher on the
cross-language hit (0.6989 vs 0.6603) and, more meaningfully, it loses almost
nothing crossing the language boundary: **−0.011** below its same-language
baseline, against **−0.033** for Qwen3-Embedding-8B. Nebius has the wider
*absolute* gap to the distractors (+0.211 vs +0.162), because bge-m3 scores
everything in a narrower, higher band — see §3.

Nebius scores were identical across three round-1 runs, so these numbers are
not sampling noise on either side.

## 3. Threshold — the Mode A / Mode B switch

| | **Nebius** (Qwen3-Embedding-8B) | **IONOS** (bge-m3) |
|---|---|---|
| Lowest top-1 among the hit queries | 0.6603 | 0.6989 |
| Highest top-1 among the no-match query | 0.4231 | 0.4677 |
| Margin | **0.2372** | **0.2312** |
| Separates cleanly | **yes** | **yes** |
| **Proposed threshold** (midpoint) | **0.54** | **0.583** |

One threshold separates hits from misses on both providers, with effectively
the same margin. NFR-2's Mode A / Mode B routing is implementable as a
similarity cut-off, as DOMAIN-MODEL.md assumes — and that conclusion is
**provider-independent**, which is the more valuable result.

The **threshold value is not**. 0.54 and 0.583 differ by 8 %, and bge-m3's
whole score distribution sits higher and tighter (its irrelevant top-1 is
0.4677 where Qwen3's is 0.4231). Absolute cosine values are not portable
across embedding models: a provider or model swap invalidates the constant and
requires re-running this script. The "provider swap is a config change" claim
in ADR-002 holds for the *code* but not for this number — **it must be a
configuration property, not a literal**, and the ADR should say so.

Both values are still measured on 3 queries against 10 protocols. With the
real ~150-protocol corpus the highest irrelevant score will rise — more
documents, more chances at a coincidental match — so both must be re-validated
in Phase 2.

## 4. Citation compliance and refusal behaviour (round 1 prompt)

Same strict system prompt everywhere (answer only from sources, cite `[P-nn]`
after every claim, answer in the question's language, say so if the sources do
not cover it). Sources: the top-3 hits each provider itself retrieved.

| | Nebius `Llama-3.3-70B` | Nebius `Qwen3-30B-A3B` | IONOS `Llama-3.3-70B` | IONOS `Qwen3.5-9B` |
|---|---|---|---|---|
| (b) correct source cited | yes, P-08 | yes, P-08 | yes, P-08 | yes, P-08 |
| (b) claims cited | 3 of 3 | **1 of 5** | 3 of 3 | 2 of 2 |
| (b) answered in German | yes | yes | yes | yes |
| (c) refused, invented nothing | yes | yes | yes | yes |
| (c) refusal language | **English — wrong** | German | **English — wrong** | German |

Two failures, both reproduced **identically on both providers**, which is the
useful part: they are model behaviours, not provider behaviours.

- **Llama-3.3-70B refuses in English** to a German question, on Nebius *and*
  IONOS. The Mode B path would show a German operator an English message — a
  visible defect in the exact night-shift scenario the demo is built around.
- **Qwen3-30B-A3B summarises and footnotes**: four uncited sentences and one
  `[P-08]` at the end. That is a grounded answer with a footnote, not "every
  claim cited". It also mistranslated "reference gauge" as *Referenzwaage*
  (a weighing scale) instead of *Referenzmanometer* — harmless here, but it is
  the model paraphrasing source content rather than quoting it.

Nothing was invented by any model on the no-match query. The anti-hallucination
design holds on both providers.

**`Qwen/Qwen3.5-9B` on IONOS failed round 1 outright, for a different reason.**
It is a **reasoning model**: it emits a long `reasoning` field and, at
`max_tokens=500`, never reached the answer — `finish_reason: "length"` with
**empty content**, twice. The fix is `reasoning_effort="none"`; that switch is
the *only* one that worked. Measured on the same model, same 400-token cap:

| Setting | Result |
|---|---|
| default | empty content, `finish_reason: length`, 1626 reasoning chars |
| `chat_template_kwargs={"enable_thinking": false}` | empty content, unchanged |
| `reasoning_effort="low"` | empty content, unchanged |
| `"/no_think"` in the prompt | empty content, unchanged |
| **`reasoning_effort="none"`** | **normal answer, `finish_reason: stop`, 36 tokens** |

All Qwen3.5-9B numbers in §7 use `reasoning_effort="none"`. This is a genuine
integration trap: with the NFR-7 output cap in place and no `reasoning_effort`,
this model returns **nothing at all** and the failure looks like a truncation
bug rather than a configuration one.

## 5. Cost

Computed from the `usage` fields of the real responses. Nebius chat prices are
read from the provider's own API (`GET /v1/models?verbose=true`); its embedding
price is from the Token Factory console page. IONOS prices are from the public
EUR price list. Demo volume = 200 queries/day × 30 = 6000/month (DECISIONS.txt).

| | Nebius Llama | Nebius Qwen3-30B | IONOS Llama | IONOS Qwen3.5-9B |
|---|---|---|---|---|
| Round-1 prompt, per query | $0.000160 | $0.000135 | €0.000587 | €0.000101 |
| **Round-2 prompt, per query** | **$0.000209** | **$0.000171** | **€0.000823** | **€0.000137** |
| per 1000 queries | $0.209 | $0.171 | €0.823 | €0.137 |
| **at 6000 queries/month** | **$1.25** | **$1.03** | **€4.94** | **€0.82** |
| Embedding 150 protocols (one-off) | $0.00029 | | €0.00049 | |

Three things worth noting:

1. **Every combination lands in the €1–5/month band**, and the cheapest of the
   four is IONOS. The headline "IONOS Llama is 3–5× the Nebius price per token"
   is true and still decides nothing: the absolute difference is under €4/month.
2. **The round-2 prompt costs ~30 % more per query** — the few-shot example adds
   ~320 prompt tokens on every call. At this volume that is cents. If it ever
   matters, the system prompt is a fixed prefix and both providers' upstream
   engines support prompt caching; not measured here.
3. **Embedding the corpus is a rounding error** on both (three to five
   hundredths of a cent, one-off). The "keep 150 protocols, do not shrink the
   corpus to save money" decision is correct with a wide margin on either
   provider. The cost risk in this project is the VPS, not the tokens.

## 6. Latency against NFR-4

NFR-4: **≤10 s target, 30 s acceptable ceiling.** All round-2 numbers use
`max_tokens=400` (the NFR-7 cap) and the candidate prompt, repeated 3×.

| | Nebius Llama | Nebius Qwen3-30B | IONOS Llama | IONOS Qwen3.5-9B |
|---|---|---|---|---|
| Generation, 3 runs (s) | 24.1 / 49.6 / **101.5** | 2.4 / 3.3 / 3.4 | 7.1 / 10.9 / 13.0 | 2.9 / 3.0 / 5.6 |
| Median | 49.6 | **3.3** | 10.9 | **3.0** |
| Meets 10 s target | **no** | **yes** | no (median 10.9) | **yes** |
| Meets 30 s ceiling | **no** | yes | **yes** | yes |
| Query embedding, add | 2.4–5.6 s | | 1.1–6.4 s | |

**The same model is 5–10× faster on IONOS.** `meta-llama/Llama-3.3-70B-Instruct`
is byte-for-byte the same weights; the difference is entirely serving capacity.
Across the eight round-2 variant calls plus three repeats, Nebius' Llama
exceeded 20 s on eight of eleven and exceeded 49 s on five; IONOS' Llama exceeded
20 s on **one** (a single 72 s outlier on an 11-token refusal) and its worst
answer-producing call was 13 s.

**The output cap is not the constraint.** No answer in round 2 came close to
400 tokens — the longest was 195, and every single call finished with
`finish_reason: "stop"`. NFR-7's cap costs nothing in answer quality here; it
is pure runaway protection. (The one exception is the Qwen3.5-9B reasoning trap
in §4, where the cap is consumed *before* the answer starts.)

**Honest caveat: this latency is queue time, not compute.** The per-call
numbers are wildly inconsistent in a way token counts cannot explain — Nebius
Llama produced 96 tokens in 2.1 s in one call and 18 tokens in 70.6 s in
another; IONOS Llama produced 11 tokens in 72.0 s once and 17 tokens in 4.1 s
minutes later. Only the repeated ×3 series above should be read as a signal,
and even it is wide. Both providers are shared serverless capacity with no SLA
at this tier; **this is a risk for the live demo on both**, and the mitigation
is a client-side timeout plus a "still working" state in the UI, not a provider
choice. Worth re-measuring from the Hetzner host in Phase 2 — this run went out
over a home connection through a Docker container.

## 7. Round 2 — fixing what round 1 broke

Round 1 left three defects. Round 2 tested each in isolation so the effect is
attributable, against **all four chat models on both providers**, at
`max_tokens=400`:

| Variant | Prompt |
|---|---|
| **V0** | the round-1 prompt (control) |
| **V1** | V0 + one **few-shot worked example** showing `[P-nn]` after every sentence |
| **V2** | V0 + an explicit **language pin** ("ALL output, including refusals, in the question's language") |
| **V3** | V0 + both — the **candidate Phase-3 prompt** |

Full prompts in `round2.json`; the two added blocks are in Appendix B.

### (a) Few-shot citation example — works, decisively

Citations per sentence on query (b), `[cites / sentences]`:

| Variant | Nebius Llama | Nebius Qwen3-30B | IONOS Llama | IONOS Qwen3.5-9B |
|---|---|---|---|---|
| V0 baseline | 3/3 | **1/5** | 3/3 | 2/2 |
| **V1 few-shot** | 3/3 | **4/4** | 3/3 | **4/4** |
| V2 language pin only | 3/3 | **1/5** | 3/3 | 2/2 |
| **V3 both** | **4/4** | **4/4** | **4/4** | **7/7** |

**Every model reached 100 % per-claim citation under V1/V3, and V2 alone
changed nothing** — so the improvement is attributable to the example, not to
prompt length or to chance. The most striking case is the model that failed
round 1 worst: Nebius `Qwen3-30B-A3B` went from one trailing footnote over five
sentences to a citation on every one of four sentences. IONOS `Qwen3.5-9B` went
further and decomposed its answer into seven separately-cited claims.

Round 1's open question — *"is Qwen's citation weakness a prompt problem or a
model problem?"* — is answered: **prompt problem.** One worked example fixed it
on every model tested. ADR-002 open question 2 ("does a mid-size chat model
follow the citation format reliably enough for Mode A?") is a **yes**, with the
caveat that it takes a few-shot prompt, not a rule.

**One regression, and it should be in the ADR.** Under V3, Nebius
`Qwen3-30B-A3B` appended `[P-03]` to its *refusal* — rule 4 says cite nothing
when refusing. The citation drill leaked into the path where citations are
forbidden. It happened on one model in one variant, it is cosmetic rather than
a hallucination (the refusal itself was correct), but it shows the two rules
compete. The application should strip citation markers from Mode B output
regardless of what the model does.

### (b) Language pinning — works, completely

Refusal language on query (c), the Mode B path:

| Variant | Nebius Llama | Nebius Qwen3-30B | IONOS Llama | IONOS Qwen3.5-9B |
|---|---|---|---|---|
| V0 baseline | **English** | German | **English** | German |
| V1 few-shot only | **English** | German | **English** | German |
| **V2 language pin** | **German** | German | **German** | German |
| **V3 both** | **German** | German | **German** | German |

Both Llama deployments flipped from English to German the moment the rule was
made explicit, and stayed German. V1 alone did not fix it, so again the effect
is attributable to the change under test.

The diagnosis is that the baseline rule 3 ("answer in the same language the
question is written in") reads as being about *answers*, and Llama does not
treat "no protocol covers this" as an answer. The pin closes that reading by
saying **all output**, naming the refusal case explicitly, and telling the model
to decide the language first. Every model refused correctly and invented
nothing in all four variants — only the language was ever wrong.

### (c) Latency at the NFR-7 cap — no help for Nebius Llama

Capping at 400 tokens did not move the numbers, because the cap never binds
(§6: longest answer 195 tokens, every call `finish_reason: stop`). The
candidate-prompt ×3 series:

| | 3 runs (s) | median | ≤10 s | ≤30 s |
|---|---|---|---|---|
| Nebius `Llama-3.3-70B` | 24.1 / 49.6 / 101.5 | 49.6 | no | **no** |
| Nebius `Qwen3-30B-A3B` | 2.4 / 3.3 / 3.4 | 3.3 | **yes** | yes |
| IONOS `Llama-3.3-70B` | 7.1 / 10.9 / 13.0 | 10.9 | no | **yes** |
| IONOS `Qwen3.5-9B` | 2.9 / 3.0 / 5.6 | 3.0 | **yes** | yes |

**This is the headline of round 2.** Llama-3.3-70B on IONOS is *borderline
usable* — one of three runs under the 10 s target, all three inside the 30 s
ceiling. On Nebius the same model breaches even the ceiling on two of three
repeats, at 49.6 s and 101.5 s. Round 1's conclusion "Llama-3.3-70B is
disqualified by latency" was **a Nebius conclusion mistaken for a model
conclusion**; with a second provider measured, it does not generalise.

Adding query embedding (1–6 s) on top, the honest end-to-end picture for the
demo is: **~4–9 s with either small model, ~9–19 s with Llama on IONOS, 26 s+
with Llama on Nebius.**

### (d) Structured JSON — the app-side enforcement path

Both providers accept `response_format={"type": "json_schema", strict: true}`
on the **first** attempt — no fallback to `json_object` or prompt-only coaxing
was needed on any of the four models. Asking for
`{answer_language, claims:[{text, source}]}`:

| | Nebius Llama | Nebius Qwen3-30B | IONOS Llama | IONOS Qwen3.5-9B |
|---|---|---|---|---|
| Query (b): parsed | yes | yes | yes | yes |
| Query (b): claims **with** a source | **2 / 2** | **3 / 3** | **2 / 2** | **8 / 8** |
| Query (b): `answer_language` | `de` | `de` | `de` | `de` |
| Query (c): refusal → `claims: []` | yes | yes | yes | yes |
| Query (c) latency | 0.5 s | 0.3 s | 1.4 s | 3.2 s |

Every claim from every model carried a source id, because in this schema an
uncited claim **cannot be represented** — `source` is required. The refusal case
degrades exactly right: an empty `claims` array, which the application renders
as Mode B without needing to parse prose or trust a refusal phrase. And
`answer_language` gives the app the model's own answer to the language question
instead of a regex guess.

This is strictly stronger than prompt discipline: prose citation compliance is
a behaviour that can regress on a bad day, while a schema violation is a parse
error the application can catch and retry. **Recommendation for Phase 3: build
Mode A on the JSON path and render the prose application-side**, keeping the
few-shot prose prompt as the fallback if a future model rejects `json_schema`.

## DRAFT recommendation

**Switch the ADR-002 primary to IONOS AI Model Hub, with Nebius Token Factory
as the documented fallback — the reverse of the current proposal.** Chat model:
a small model (`Qwen3.5-9B` with `reasoning_effort="none"`), with
`Llama-3.3-70B-Instruct` as a quality option that IONOS latency now makes
defensible and Nebius latency does not.

Weighing the factors the ADR has to weigh:

1. **Retrieval quality — IONOS, narrowly.** Both pass the hard requirement
   convincingly. bge-m3 scores higher on the cross-language hit (0.6989 vs
   0.6603) and loses almost nothing crossing languages (−0.011 vs −0.033).
   Qwen3-Embedding-8B has the wider absolute margin over distractors. This is a
   real but small edge, and on its own it would not decide anything. Both
   produce a clean, comparable threshold separation (§3).

2. **Single-embedding-model risk — decisive for IONOS.** Nebius publicly serves
   **exactly one** embedding model. The hard requirement of ADR-002 rests on a
   single SKU staying available at a single provider, with no second option
   inside that provider, and re-embedding the corpus plus re-deriving the
   threshold as the only recovery. IONOS serves **three** multilingual
   candidates, so the same failure is a model-name change and a re-run of this
   script. ADR-002 chose Nebius partly *because* it expected BGE-M3-class
   embeddings there; the spike found the opposite — bge-m3 is on IONOS and
   absent from Nebius. That premise of the decision is simply wrong and the
   decision should follow the correction.

3. **Data residency — IONOS, and it is the portfolio story.** IONOS is a German
   company, the endpoint is `de-txl` (**Berlin**), ISO 27001. Nebius' EU region
   is Finland (`eu-north1`, Helsinki) under a Netherlands-headquartered
   company. Both are EU and both satisfy NFR-1 on paper. But NFR-1 exists for a
   DSGVO *narrative* aimed at German industrial users, and "your maintenance
   protocols are processed in a Berlin data centre by a German provider" is a
   materially stronger sentence than "processed in Finland". Now that the
   measurements no longer argue for Nebius, this stops being a tiebreaker and
   starts being a reason.

4. **Billing — Nebius is friendlier, IONOS is more realistic, neither is
   verified.** Nebius' USD prepaid trial credit is a natural hard ceiling: the
   credit runs out and spending stops, which is precisely what NFR-7 layer 1
   asks for. IONOS' EUR postpaid contract credit bills against an account and
   needs an explicit limit to become a ceiling. Against that: EUR billing
   removes FX noise from a €-denominated cost story, and postpaid does not
   expire mid-demo the way trial credit does. **Neither was tested — this spike
   is API-only and ADR-002 open question 4 remains open.** It is the one factor
   here that could still overturn this recommendation, and it needs 20 minutes
   in each billing console, not another script. If IONOS turns out to offer no
   hard spending cap, NFR-7 layer 1 has to be carried entirely by the
   application (rate limit + token cap + monitoring), and that trade should be
   made explicitly rather than discovered later.

5. **Chat latency and citations after round 2 — IONOS.** Citations and refusal
   language are now solved on both providers by prompt alone (§7a, §7b), so
   they no longer separate the two. Latency does, sharply: the identical
   Llama-3.3-70B is 7–13 s on IONOS and 24–101 s on Nebius. Both providers have
   a small model that meets NFR-4 comfortably (Qwen3-30B-A3B at ~3 s,
   Qwen3.5-9B at ~3 s), so the demo is shippable either way — but only IONOS
   leaves the 70B model as a live option, and only IONOS' cheapest working
   configuration (€0.82/month) is also the cheapest overall.

**What would reverse this:** a hard spending cap available on Nebius and not on
IONOS (factor 4), or the latency gap turning out to be a transient capacity
problem on Nebius' side rather than a standing one. The latency numbers here
are six calls per model over one evening from a home connection; they are
consistent enough to act on and thin enough to re-check from the Hetzner host
before the ADR is accepted.

**Not in doubt either way:** cost (§5 — every combination is €1–5/month), the
threshold mechanism (§3 — works on both, value must be configurable), and the
anti-hallucination behaviour (§4 — no model on either provider invented
anything, in any of the 40+ generation calls across both rounds).

## What this spike does not answer

Of the five open questions in ADR-002, rounds 1–2 close **1, 2 and 3 for both
providers**. Still open:

- **Question 4, billing controls** — not testable through the API; it needs a
  look at each provider's billing console. NFR-7 layer 1 depends on it, and the
  ADR calls the demo "not shippable as designed" without a provider-side
  ceiling. See factor 4 above; this is now the **only** blocker between this
  spike and an ADR decision.
- **Question 5, Spring AI on Spring Boot 4.1** — untouched; this spike is
  Python, chosen so it evaluates the *provider*, not the Java integration. Note
  §1: the `encoding_format` deviation is exactly the kind of thing a Spring AI
  client could trip over, and the `RestClient` fallback would not.
- **Threshold on the real corpus** — 0.54 / 0.583 are measured on 10
  protocols and must be re-validated against ~150 in Phase 2.
- **Latency from the Hetzner host** — all numbers here are from a home
  connection; the network share of the 1–6 s embedding round trip is unknown.

---

## Appendix A — raw model answers

### Query (b) — "Presse zeigt Fehler E-47, Druck schwankt"

Round-1 sources: Nebius P-08/P-01/P-05, IONOS P-08/P-01/P-07 (each provider's
own top-3).

**Nebius `Llama-3.3-70B` — V0 baseline**, 786+143 tokens

> Das Symptom des Fehlers E-47 mit schwankendem Druck auf der Presse entspricht
> dem in Protokoll P-08 beschriebenen Fall [P-08]. Die Ursache dafür ist ein
> Drift auf dem Hauptdrucksensor, der um etwa 12 Prozent gegenüber dem
> Referenzmanometer verschoben ist [P-08]. Die empfohlene Maßnahme besteht
> darin, den Drucksensor gegen ein zertifiziertes Referenzmanometer zu
> kalibrieren, den Offset in den PLC-Parametern zu korrigieren und mehrere
> Testzyklen durchzuführen [P-08].

3 citations / 3 sentences, 0 uncited.

**Nebius `Qwen3-30B-A3B` — V0 baseline**, 810+181 tokens — *the round-1 failure*

> Der Fehler E-47 tritt auf, wenn der Druck während des Pressvorgangs zwischen
> 180 und 240 bar schwankt, obwohl der Sollwert 210 bar beträgt. Die Ursache ist
> ein Drift des Hauptdrucksensors, der um etwa 12 Prozent gegenüber der
> Referenzwaage abweicht. Die Hydraulikschaltung selbst ist intakt. Die Maßnahme
> besteht in der Nachkalibrierung des Drucksensors anhand einer zertifizierten
> Referenzwaage, der Korrektur des Offset-Werts im PLC-Parameter-Set sowie dem
> Durchführen von zehn Testzyklen. Danach ist der Druck stabil bei 210 bar und
> der Fehler E-47 tritt nicht mehr auf [P-08].

1 citation / 5 sentences, **4 uncited**. Note *Referenzwaage* (weighing scale)
for "reference gauge".

**Nebius `Qwen3-30B-A3B` — V3 candidate**, 1142+189 tokens, 5.1 s — *the fix*

> Die Presse zeigt Fehler E-47, wobei der Druck während des Pressvorgangs
> zwischen 180 und 240 bar schwankt, obwohl der Sollwert 210 bar beträgt [P-08].
> Ursache ist eine Drift des Hauptdrucksensors, der gegenüber dem
> Referenzmanometer um etwa 12 Prozent abweicht; die Hydraulikschaltung selbst
> ist einwandfrei [P-08]. Als Maßnahme wurde der Drucksensor an einem
> zertifizierten Referenzmanometer nachkalibriert, der Offset im
> PLC-Parameter-Set korrigiert und zehn Testzyklen durchgeführt [P-08]. Der
> Druck ist nun stabil bei 210 bar, und der Fehler E-47 trat nicht erneut auf
> [P-08].

4 citations / 4 sentences, 0 uncited. The few-shot example also corrected
*Referenzwaage* → *Referenzmanometer*.

**IONOS `Llama-3.3-70B` — V0 baseline**, 774+129 tokens, **3.7 s**

> Das Symptom "Fehler E-47" und "Druck schwankt" entspricht dem in Protokoll
> P-08 beschriebenen Fall [P-08]. Die Ursache dafür ist ein Drift auf dem
> Hauptdrucksensor, der um etwa 12 Prozent gegenüber dem Referenzmanometer
> verschoben war [P-08]. Die empfohlene Maßnahme ist die Neukalibrierung des
> Drucksensors gegen ein zertifiziertes Referenzmanometer und die Korrektur des
> Offsets in den PLC-Parametern [P-08].

3 citations / 3 sentences. Same model, same prompt, **3.7 s on IONOS against
53.1 s on Nebius for the equivalent call**.

**IONOS `Qwen3.5-9B` — V3 candidate**, 1120+162 tokens, 8.8 s

> Die Presse stoppt mitten im Zyklus und das HMI zeigt den Fehler E-47 an
> [P-08]. Der Druck schwankt zwischen 180 und 240 bar, obwohl der Sollwert
> 210 bar beträgt [P-08]. Die Ursache ist ein Drift des Hauptdrucksensors
> [P-08]. Als Massnahme wurde der Drucksensor gegen ein zertifiziertes
> Referenzmessgerät kalibriert [P-08]. Der Offset wurde im PLC-Parameter-Satz
> korrigiert [P-08]. Anschließend wurden zehn Testzyklen durchgeführt [P-08].
> Der Druck ist nun stabil bei 210 bar und der Fehler E-47 trat nicht wieder
> auf [P-08].

**7 citations / 7 sentences.** The few-shot example pushed it into one claim per
sentence — the closest of all four models to the Mode A ideal.

### Query (c) — "Lackierroboter traegt die Farbe ungleichmaessig auf …"

**Both Llama deployments, V0 baseline** — refusal correct, **language wrong**:

> No protocol in the knowledge base covers this case.

**Both Llama deployments, V3 candidate** — refusal correct, language correct:

> Kein Protokoll im Wissensbasis deckt diesen Fall ab.  *(IONOS)*
> Kein Protokoll im Wissensspeicher deckt diesen Fall ab.  *(Nebius)*

(The article in *"im Wissensbasis"* is wrong German, but the language rule is
respected.)

**Nebius `Qwen3-30B-A3B`, V3** — the citation regression noted in §7a:

> Kein Protokoll im Wissensbestand behandelt den Fall eines Lackierroboters mit
> ungleichmäßiger Farbauftragung und Farbnebel in der Kabine **[P-03]**.

Correct refusal, correct language, but rule 4 says cite nothing when refusing.

### Structured JSON, query (b) — IONOS `Qwen3.5-9B`

```json
{"answer_language": "de",
 "claims": [
  {"text": "Der Fehler E-47 tritt auf, wenn die Hydraulikpresse mitten im Zyklus stoppt und der HMI diesen Fehler anzeigt.", "source": "P-08"},
  {"text": "Der Druck schwankt zwischen 180 und 240 bar, obwohl der Sollwert 210 bar beträgt.", "source": "P-08"},
  {"text": "Die Ursache für diesen Fehler ist ein Drift am Hauptdrucksensor.", "source": "P-08"},
  {"text": "Das Sensorsignal hatte sich gegenüber dem Referenzmanometer um etwa 12 Prozent verschoben.", "source": "P-08"},
  {"text": "Die Maßnahme bestand darin, den Drucksensor gegen ein zertifiziertes Referenzmanometer zu kalibrieren.", "source": "P-08"},
  {"text": "Der Offset wurde im PLC-Parameter-Satz korrigiert.", "source": "P-08"},
  {"text": "Es wurden zehn Testzyklen durchgeführt.", "source": "P-08"},
  {"text": "Der Druck ist nun stabil bei 210 bar und der Fehler E-47 ist nicht erneut aufgetreten.", "source": "P-08"}]}
```

Query (c) on all four models: `{"answer_language": "de", "claims": []}`.

## Appendix B — prompts

The round-1 / V0 system prompt (`temperature=0.0`; round 1 `max_tokens=500`,
round 2 `max_tokens=400`). The user message is the top-3 protocols verbatim,
each prefixed with `[P-nn]`, then the question.

```
You are a maintenance assistant for an industrial plant. You answer questions
from shop-floor staff about maintenance protocols.
Rules:
1. Answer ONLY from the sources provided in the user message. Never add
knowledge from anywhere else.
2. Cite the source protocol after every factual claim, in the format [P-nn].
A claim without a citation is not allowed.
3. Answer in the SAME LANGUAGE the question is written in, even if the sources
are written in another language.
4. If the sources do not contain an answer to the question, say in one sentence
that no protocol in the knowledge base covers this case, and cite nothing. Do
not guess and do not offer a repair procedure.
```

**V1 appends the few-shot block.** It uses a protocol id (P-99) and a machine
that are not in the corpus, so it cannot be mistaken for a source:

```
Format example (this is NOT a source, do not cite P-99 in your answer):

  Sources:
  [P-99]
  Protocol P-99 | Pump 7 (PU-07) | fault E-11
  Symptom: The pump loses suction after ten minutes.
  Cause: Blocked inlet strainer.
  Action: Cleaned the strainer, refilled the system, ran a 30 minute test.

  Question: Pumpe 7 verliert Saugleistung, was tun?

  Correct answer:
  Die Pumpe verliert nach etwa zehn Minuten die Saugleistung [P-99]. Ursache ist
  ein verstopfter Saugkorb [P-99]. Als Massnahme wurde der Saugkorb gereinigt,
  die Anlage neu befuellt und 30 Minuten im Testlauf geprueft [P-99].

Note what makes it correct: EVERY sentence carries its own [P-nn] directly at
the end of that sentence. A single citation at the end of the whole answer is
WRONG, even if the answer is otherwise grounded.
```

**V2 appends the language pin:**

```
LANGUAGE RULE (overrides everything else): ALL text you output must be in the
language of the QUESTION, never the language of the sources. This includes the
"no protocol covers this" case from rule 4: if the question is German, the
refusal must be German. There is no exception. Decide the language of the
question first, then write every word of your output in that language.
```

**V3 = V0 + few-shot + language pin.** The structured-JSON prompt is V0 + the
language pin + the schema instruction; see `round2.json`.

## Appendix C — model catalogues (2026-08-06)

**Nebius, 27 models** — only `Qwen/Qwen3-Embedding-8B` is an embedding model:

```
MiniMaxAI/MiniMax-M2.5              nvidia/Cosmos3-Super-Reasoner
MiniMaxAI/MiniMax-M3                nvidia/Llama-3_1-Nemotron-Ultra-253B-v1
NousResearch/Hermes-4-405B          nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B
NousResearch/Hermes-4-70B           nvidia/Nemotron-3-Nano-Omni
Qwen/Qwen2.5-VL-72B-Instruct        nvidia/Nemotron-3-Ultra-550b-a55b
Qwen/Qwen3-235B-A22B-Instruct-2507  nvidia/nemotron-3-super-120b-a12b
Qwen/Qwen3-30B-A3B-Instruct-2507    openai/gpt-oss-120b
Qwen/Qwen3-32B                      openbmb/MiniCPM-V-4_5
Qwen/Qwen3-Embedding-8B             zai-org/GLM-5.1
Qwen/Qwen3-Next-80B-A3B-Thinking    zai-org/GLM-5.2
Qwen/Qwen3.5-397B-A17B              deepseek-ai/DeepSeek-V4-Pro
google/gemma-3-27b-it               meta-llama/Llama-3.3-70B-Instruct
moonshotai/Kimi-K2.6                moonshotai/Kimi-K2.7-Code
moonshotai/Kimi-K3
```

**IONOS, 20 models** — four embedding models, three of them multilingual;
the `*-migration` ids are deprecated aliases and are filtered by the spike:

```
BAAI/bge-large-en-v1.5                                bge-large-en-v1.5-migration
BAAI/bge-m3                                           bge-m3-migration
Qwen/Qwen3-Coder-Next                                 paraphrase-multilingual-mpnet-base-v2-migration
Qwen/Qwen3-VL-Embedding-8B                            black-forest-labs/FLUX.1-schnell
Qwen/Qwen3-VL-Reranker-8B                             black-forest-labs/FLUX.2-klein-4B
Qwen/Qwen3.5-397B-A17B                                lightonai/LightOnOCR-2-1B
Qwen/Qwen3.5-9B                                       meta-llama/Llama-3.3-70B-Instruct
mistralai/Mistral-Nemo-Instruct-2407                  meta-llama/Meta-Llama-3.1-405B-Instruct-FP8
mistralai/Mistral-Small-24B-Instruct                  meta-llama/Meta-Llama-3.1-8B-Instruct
openai/gpt-oss-120b                                   sentence-transformers/paraphrase-multilingual-mpnet-base-v2
```

IONOS' `/v1/models` returns **no pricing information** — unlike Nebius'
`?verbose=true` — so IONOS prices come from the published price list.

## Appendix D — prices used

**Nebius** (USD per 1M tokens). Chat prices read live from
`GET /v1/models?verbose=true` — the `pricing` object of each model
([docs](https://docs.tokenfactory.nebius.com/api-reference/examples/list-of-models)):

| Model | Input | Output |
|---|---|---|
| `meta-llama/Llama-3.3-70B-Instruct` | $0.13 | $0.40 |
| `Qwen/Qwen3-30B-A3B-Instruct-2507` | $0.10 | $0.30 |
| `Qwen/Qwen3-Embedding-8B` | $0.01 | — (embeddings bill input only) |

The embedding price is not exposed in the verbose model list; it comes from the
Token Factory console model page (4096 dims, eu-north1), inspected 2026-08-06.

**IONOS** (EUR per 1M tokens), from the official
[IONOS Cloud EUR price list](https://docs.ionos.com/cloud/support/general-information/price-list/ionos-cloud-eur-en.md),
AI Model Hub section, retrieved 2026-08-06:

| Model | Input | Output |
|---|---|---|
| `meta-llama/Llama-3.3-70B-Instruct` | €0.65 | €0.65 |
| `Qwen/Qwen3.5-9B` | €0.10 | €0.15 |
| `Qwen/Qwen3.5-397B-A17B` | €0.60 | €3.60 |
| `BAAI/bge-m3` (embedding) | €0.02 | — |
| `paraphrase-multilingual-mpnet-base-v2` | €0.01 | — |
| `BAAI/bge-large-en-v1.5` | €0.015 | — |

IONOS lists Llama-3.3-70B at €0.65/€0.65 against Nebius' $0.13/$0.40 — roughly
3–5× more per answer. At this project's volume that is a difference of under
€4/month (§5), so price does not decide ADR-002 either way. The cheapest
working configuration measured is IONOS `Qwen3.5-9B` at **€0.82/month**.

## Appendix E — how to reproduce this run

Everything below can be re-run from a clean checkout. That is the point of committing this
directory: a measurement nobody else can repeat is an assertion.

### Prerequisites

- **Docker**, and nothing else — the commands below run the spike inside `python:3.12-slim`, so no
  local Python installation is involved and no virtual environment has to be managed. Python **3.12**
  is what the recorded runs used. Running it with a locally installed Python works too
  (`pip install -r requirements.txt`, then `python spike.py`); 3.11 or newer is sufficient for the
  two dependencies, which are pinned in `requirements.txt` (`openai==2.9.0`, `numpy==2.3.4`).
- **Two provider API keys**, in a `.env` file created from `.env.example`:
  - `NEBIUS_API_KEY` — Nebius Token Factory, <https://tokenfactory.nebius.com/>. The recorded runs
    used a trial key.
  - `IONOS_API_KEY` — IONOS AI Model Hub, created as a token in the IONOS Cloud console (DCD).
  Both are **gitignored and appear nowhere in this repository, in the committed JSON, or in this
  document** — the run output stores model ids, scores, token counts and latencies, never
  credentials. Use your own keys; the ones behind the recorded numbers are not shared and the
  production token is a different one again, held only on the server and in a password manager.
  A provider whose key is missing is skipped with a message and the other one is still evaluated,
  so a single-provider reproduction is possible.
- **Budget.** A full re-run of both rounds costs a few cents at the prices in Appendix D. There is
  no hard spending cap at either provider — only cost alerts — so set one before running anything
  in a loop.

### The commands

```powershell
cd spike/adr-002
cp .env.example .env    # paste NEBIUS_API_KEY and IONOS_API_KEY

# round 1 — both providers (or `python spike.py ionos` for one; results.json
# is merged, so a single-provider re-run keeps the other provider's record)
docker run --rm -v "${PWD}:/spike" -w /spike --env-file .env python:3.12-slim `
  sh -c "pip install -q -r requirements.txt && python spike.py"

# round 2 — reads the top-3 hits out of results.json, so run round 1 first
docker run --rm -v "${PWD}:/spike" -w /spike --env-file .env python:3.12-slim `
  sh -c "pip install -q -r requirements.txt && python round2.py"
```

Round 2 takes 15–25 minutes: 44 generation calls, and Nebius' Llama-3.3-70B
alone accounts for most of the wall clock.

Retrieval scores are deterministic and reproduced exactly across three round-1
runs on Nebius. Chat answers and latency vary — `results.json` holds the third
Nebius round-1 run and the first IONOS one; `round2.json` holds every round-2
call including all three latency repeats.

### What will differ from the numbers above, and what should not

Reproducing a measurement is only useful if you know in advance which parts are allowed to move.

**Expect these to differ:**

- **Latency, by a lot.** Both providers serve this tier from shared serverless capacity with no SLA,
  so the spread is queueing rather than compute. Section 6's figures come from three repeats and are
  still only a sample; production measurement later found a median around 15 s from the host with
  single calls ranging 8.4–29.7 s. A single slow run proves nothing, and a single fast one proves
  less.
- **Cost per query,** if list prices have moved. Appendix D records the prices used and the date they
  were read, so a difference is checkable rather than mysterious.
- **The exact wording of every generated answer.** Nothing here pins a decoding seed; the answers in
  Appendix A are one sample each.
- **Model availability.** Both catalogues in Appendix C are dated. Models are added, renamed and
  retired — the `*-migration` aliases at IONOS were already a trap at the time of writing.

**Expect these to hold, and treat a difference as a finding worth chasing:**

- **The retrieval scores**, to the digit. Embeddings are deterministic for a fixed model and input;
  the DE→EN case reproduced exactly across three runs. A changed score means a changed model behind
  the same name.
- **The DE→EN result itself** — a German query retrieving the English E-47 protocol. This is the
  reason the spike reversed its own proposal, and it rests on the multilingual embedding model being
  available at the provider, not on any prompt.
- **The reversal rationale.** The recommendation flipped to IONOS on multilingual model availability
  and data residency, and those are structural facts about the two catalogues rather than
  measurements of a given day. If IONOS ever drops `bge-m3`, that is not noise — it invalidates the
  decision, and ADR-002 is where the consequence gets written down.
- **The citation and refusal behaviour** under the round-2 prompts: few-shot citations comply,
  language pinning holds, and structured JSON is what catches an ungrounded answer. These were
  stable across models and are the reason the query path enforces them app-side.
