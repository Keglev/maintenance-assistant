# ADR-002 spike results — EU-hosted LLM provider

**Run date:** 2026-08-06 · **Script:** [`spike.py`](spike.py) · **Raw output:** [`results.json`](results.json)
**Status of this document: DRAFT.** It reports what was measured and argues a
recommendation. The ADR decision itself is the project owner's.

> **Coverage caveat, stated up front:** only **Nebius Token Factory** was
> evaluated. No IONOS API key was available at run time, so the script skipped
> that provider by design. Every IONOS row below is either "not evaluated" or a
> published price — never a measurement. The head-to-head comparison ADR-002
> asks for is therefore **not yet complete**; see [Open](#what-this-spike-does-not-answer).

## Comparison table

| | **Nebius Token Factory** | **IONOS AI Model Hub** |
|---|---|---|
| Evaluated | yes, live API | **no — no API key at run time** |
| Models offered (`GET /v1/models`) | 27 | not evaluated |
| Embedding model used | `Qwen/Qwen3-Embedding-8B`, 4096 dims | not evaluated (catalogue lists `bge-m3`) |
| Multilingual embedding available at all | yes, but **only one**: Qwen3-Embedding-8B is the sole embedding model in the public catalogue | per price list: `bge-m3`, `paraphrase-multilingual-mpnet-base-v2` |
| DE→DE retrieval (query a) | correct, rank 1, **0.6934** | not evaluated |
| **DE→EN retrieval (query b)** | **correct, rank 1, 0.6603** — the English protocol beat both German press protocols by 0.21 | not evaluated |
| No-match query (query c) | best score **0.4231**, correctly no relevant hit | not evaluated |
| Threshold separation | **clean**, margin **0.237** → proposed **0.54** | not evaluated |
| Citation compliance | Llama-3.3-70B: **3/3 claims cited**, correct source · Qwen3-30B: **1 citation for 5 claims** | not evaluated |
| Answer language (DE question) | both models answered in German on the grounded query | not evaluated |
| Refusal behaviour | both refused correctly, invented nothing · **Llama refused in English** (prompt rule violated) · Qwen refused in German | not evaluated |
| Latency, embedding | 2.6–5.0 s (10 protocols batch: 2.6 s) | not evaluated |
| Latency, generation | Llama-3.3-70B: **24.6 / 33.2 / 48.1 s** — fails NFR-4 · Qwen3-30B: **3.2 s** | not evaluated |
| Cost per query (measured tokens) | **$0.00016** (Llama) / **$0.000135** (Qwen3-30B) | TBD — no measured token counts |
| Cost, embedding 150 protocols | **$0.00029** (one-off) | ≈ €0.0006 at the listed €0.02/1M (arithmetic, not measured) |
| Price transparency | chat prices readable from the API itself (`?verbose=true`); embedding price console-only | full public price list, in EUR |

**Bottom line of the measurements:** the multilingual retrieval requirement —
the one ADR-002 calls a hard requirement — **passes on Nebius, convincingly**.
The problem the spike surfaced is not retrieval and not cost; it is that
**neither chat model satisfies NFR-2 and NFR-4 at the same time**.

## 1. Model discovery

Nebius returned 27 models. Relevant facts:

- **Exactly one embedding model** is publicly served: `Qwen/Qwen3-Embedding-8B`
  (4096 dims). `bge-multilingual-gemma2` and `bge-en-icl` appear in the
  Nebius catalogue but have no public endpoint (dedicated deployment only),
  and `BAAI/bge-m3` — the model ADR-002 names as the reference class — **is
  not offered at all**. This is a genuine concentration risk: on Nebius the
  embedding side of this project has no second option.
- Chat: `meta-llama/Llama-3.3-70B-Instruct` is available and was taken as the
  primary pick per the ADR preference. The catalogue also holds
  `Qwen/Qwen3-30B-A3B-Instruct-2507`, `Qwen/Qwen3-32B`,
  `Qwen/Qwen3-235B-A22B-Instruct-2507`, `google/gemma-3-27b-it` and others
  (full list in `results.json`).
- No latency-optimised ("fast") serving variants exist in this catalogue, so
  the Llama latency below cannot be bought away with a faster endpoint tier.

## 2. Retrieval — the DE→EN case

Corpus: 10 protocols, 7 German / 3 English, one machine and fault each. The
**only** protocol about hydraulic press error E-47 is English (P-08). Two
German protocols about hydraulic presses exist (P-01 oil leak, P-07 planned
hydraulic service) as same-language, same-machine-type noise.

| Query | Top-1 | Top-2 | Top-3 | Expected | Verdict |
|---|---|---|---|---|---|
| **(a)** "Kompressor liefert zu wenig Druck, Netzdruck in der Halle faellt ab" | **P-05** (de) 0.6934 | P-08 (en) 0.4351 | P-01 (de) 0.4029 | P-05 | hit, rank 1 |
| **(b)** "Presse zeigt Fehler E-47, Druck schwankt" | **P-08** (en) 0.6603 | P-01 (de) 0.4491 | P-05 (de) 0.4469 | P-08 | **hit, rank 1, cross-language** |
| **(c)** "Lackierroboter traegt die Farbe ungleichmaessig auf, Farbnebel in der Kabine" | P-03 (de) 0.4231 | P-10 (en) 0.3905 | P-06 (de) 0.3256 | *nothing* | correctly nothing relevant |

The multilingual result is stronger than "it worked": the cross-language hit
(0.6603) is only 0.033 below the same-language baseline (0.6934), and it beat
the closest German press protocol by **0.211**. The embedding model is
matching on the symptom semantics, not on the language or on the word
"Presse"/"press". That is exactly the behaviour DECISIONS.txt assumes when it
rules out translation anywhere in the pipeline.

Scores were **identical across three runs** (0.6934 / 0.6603 / 0.4231–0.4233),
so the retrieval numbers are not noise.

## 3. Threshold — the Mode A / Mode B switch

| | value |
|---|---|
| Lowest top-1 among the hit queries | 0.6603 |
| Highest top-1 among the no-match query | 0.4231 |
| Margin | **0.2372** |
| Separates cleanly | **yes** |
| **Proposed threshold** | **0.54** (midpoint) |

One threshold does separate hits from misses, with a wide margin — NFR-2's
Mode A / Mode B routing is implementable as a similarity cut-off, as
DOMAIN-MODEL.md assumes.

Two caveats that belong in the ADR rather than being buried:

1. This is 3 queries against 10 protocols. The margin is comfortable, but the
   threshold must be re-validated against the real ~150-protocol corpus in
   Phase 2 — with 150 documents the highest irrelevant score rises, since
   there are simply more chances at a coincidental match. Keep it configurable.
2. The value **0.54 is specific to Qwen3-Embedding-8B**. Absolute cosine
   values are not portable across embedding models; a provider or model swap
   invalidates the number and requires re-running this script. The "provider
   swap is a config change" claim in ADR-002 holds for the *code*, but not for
   this constant.

## 4. Citation compliance and refusal behaviour

Same strict system prompt for both models (answer only from sources, cite
`[P-nn]` after every claim, answer in the question's language, say so if the
sources do not cover it). Sources: the top-3 hits of the respective query.

| | `Llama-3.3-70B-Instruct` | `Qwen3-30B-A3B-Instruct-2507` |
|---|---|---|
| Query (b): correct source cited | yes, P-08 | yes, P-08 |
| Query (b): claims cited | **3 of 3 sentences** | **1 citation for 5 sentences** |
| Query (b): answered in German | yes | yes |
| Query (b): latency | **33.2 s** (24.6 / 33.2 / 48.1 s over three runs) | **3.2 s** |
| Query (c): refused, invented nothing | yes | yes |
| Query (c): refusal language | **English — rule violated** | German — correct |
| Price (per 1M in/out) | $0.13 / $0.40 | $0.10 / $0.30 |

**Each model fails a different requirement, and neither failure is cosmetic:**

- **Llama-3.3-70B** produces exactly the Mode A output NFR-2 describes — every
  claim carries its source — but takes 24–48 s for one answer. NFR-4 sets ≤10 s
  as the target and 30 s as the acceptable ceiling; two of three runs exceeded
  even the ceiling, for a 786-token prompt. It also dropped into English for
  the refusal, meaning **the Mode B path would show a German operator an
  English message** — a visible defect in the exact night-shift scenario the
  demo is built around.
- **Qwen3-30B-A3B** is ten times faster, cheaper, and handled the language rule
  correctly on both paths — but it summarised the source in four uncited
  sentences and put a single `[P-08]` at the very end. That is not "every claim
  cited"; it is a grounded answer with a footnote. It also mistranslated
  "reference gauge" as "Referenzwaage" (a weighing scale) instead of
  "Referenzmanometer" — harmless here, but it is the model translating source
  content rather than quoting it.

Both refused correctly on query (c) and neither invented a repair procedure,
which is the more dangerous failure mode. The anti-hallucination design holds.

## 5. Cost

Computed from the `usage` fields of the real responses. Nebius chat prices were
read from the provider's own API (`GET /v1/models?verbose=true`, which returns a
per-model `pricing` object); the embedding price is from the Token Factory
console model page.

| Item | Measured tokens | Cost |
|---|---|---|
| Embedding one query | 15 | $0.00000015 |
| Answer with Llama-3.3-70B | 786 in + 144 out | $0.00016 |
| Answer with Qwen3-30B | 810 in + 179 out | $0.000135 |
| **Per query, end to end** | | **$0.00016 / $0.000135** |
| 1000 queries | | $0.16 / $0.13 |
| **Embedding the 150-protocol corpus** (194.3 tok/protocol measured, ×150) | 29,145 | **$0.00029, one-off** |
| Demo budget ceiling (200 queries/day × 30, per DECISIONS.txt) | | **≈ $0.96 / month** |

This confirms the cost assumption in DECISIONS.txt and then some: the LLM spend
is **below** the €1–3/month the ADR budgets, and the corpus is a rounding error
— embedding all 150 protocols costs three hundredths of a cent. The "keep 150
docs, do not shrink the corpus to save money" decision is correct with a wide
margin. The cost risk in this project is the VPS, not the tokens.

## 6. Latency against NFR-4

| Stage | Measured |
|---|---|
| Embed the query | 2.0–5.0 s |
| Generate the answer (Llama-3.3-70B) | 24.6 / 33.2 / 48.1 s |
| Generate the answer (Qwen3-30B) | 3.2 s |
| **End to end, Llama** | **27.1 / 39.0 / 50.4 s — fails NFR-4** |
| **End to end, Qwen3-30B** | **≈ 6–8 s — meets the ≤10 s target** |

The query-embedding step at 2–5 s is itself notable: it is a 15-token input, so
this is round-trip and queueing overhead, not compute. Against a ≤10 s budget
it consumes a third of the allowance before generation starts. Worth measuring
again from the Hetzner host in Phase 2 — this run went out over a home
connection through a Docker container, so some of it is local.

## DRAFT recommendation

**Keep Nebius Token Factory as the ADR-002 primary, but change the chat model
choice — and get an IONOS key before the ADR is accepted.**

Reasoning:

1. **The hard requirement is met.** ADR-002 states that a provider unable to
   serve a multilingual embedding model is disqualified regardless of price.
   Nebius serves one and it performs well cross-language (§2). This alone
   clears the blocking question.
2. **Cost is a non-issue** and behaves as the design assumed (§5).
3. **The chat model should not be Llama-3.3-70B.** It is the model ADR-002
   names, and it wins on citation discipline — but 24–48 s per answer breaks
   NFR-4, there is no faster serving tier to buy, and it violated the language
   rule on the refusal path. `Qwen3-30B-A3B-Instruct-2507` meets the latency
   target with room to spare, is cheaper, and gets the language rule right.
4. **Its citation weakness is a prompt problem before it is a model problem.**
   The current prompt says "cite after every claim" once. The obvious Phase 3
   work is to constrain the output shape — one claim per line, each line
   required to end in `[P-nn]`, possibly few-shot — and re-run this script to
   see whether Qwen complies. That experiment is cheap and should happen before
   the model choice is fixed. If Qwen still cannot be made to cite per claim,
   the real trade is "Llama + accept ~30 s" vs "Qwen + weaker citations", and
   that is a decision, not a measurement.

**Reservation.** This recommends a provider on a one-provider evaluation. The
IONOS side is unmeasured, and IONOS is not merely a fallback on paper: it
publishes prices in EUR openly, serves `bge-m3` (the model class ADR-002 was
written around, and which Nebius does **not** offer), and has the stronger
data-residency story per ADR-002. Nebius currently offers exactly one public
embedding model — a single point of failure for the project's hard
requirement. Running this script against IONOS is one API key and about two
minutes; the ADR should not be moved to *Accepted* without it.

## What this spike does not answer

Of the five open questions in ADR-002, this spike closes 1–3 for Nebius and
leaves these:

- **Question 4, billing controls** (prepaid credit / hard spending limits) —
  not testable through the API; it needs a look at each provider's billing
  console. NFR-7 layer 1 depends on it, and the ADR calls the demo
  "not shippable as designed" without a provider-side ceiling.
- **Question 5, Spring AI on Spring Boot 4.1** — untouched here; this spike is
  Python, chosen so it evaluates the *provider*, not the Java integration. The
  fallback (`RestClient` against the OpenAI-compatible endpoint) is unaffected
  by anything measured here.
- **IONOS end to end** — everything in §1–6 for the second provider.

---

## Appendix A — raw model answers

### Query (b) — "Presse zeigt Fehler E-47, Druck schwankt"

Sources given (top-3): P-08 (en), P-01 (de), P-05 (de).

**`meta-llama/Llama-3.3-70B-Instruct`** — 786 prompt + 144 completion tokens, 33.2 s, `finish_reason: stop`

> Das Symptom des Fehlers E-47 mit schwankendem Druck auf der Presse entspricht
> dem in Protokoll P-08 beschriebenen Fall [P-08]. Die Ursache dafür ist
> wahrscheinlich ein Drift auf dem Hauptdrucksensor, der um etwa 12 Prozent
> gegenüber dem Referenzmanometer verschoben ist [P-08]. Die empfohlene Maßnahme
> wäre, den Drucksensor gegen ein zertifiziertes Referenzmanometer zu
> kalibrieren, den Offset in den PLC-Parametern zu korrigieren und mehrere
> Testzyklen durchzuführen [P-08].

Citations: 3, all `P-08`, 0 of 3 sentences uncited. German: yes (11 German
markers, 0 English).

**`Qwen/Qwen3-30B-A3B-Instruct-2507`** — 810 prompt + 179 completion tokens, 3.2 s, `finish_reason: stop`

> Der Fehler E-47 tritt auf, wenn der Druck während des Pressvorgangs zwischen
> 180 und 240 bar schwankt, obwohl der Sollwert 210 bar beträgt. Die Ursache ist
> ein Drift des Hauptdrucksensors, der um etwa 12 Prozent gegenüber der
> Referenzwaage abweicht. Die Hydraulikschaltung selbst ist intakt. Die Maßnahme
> besteht in der Nachkalibrierung des Drucksensors anhand einer zertifizierten
> Referenzwaage, Korrektur des Offset-Werts im PLC-Parameter-Set sowie
> Durchführung von zehn Testzyklen. Danach ist der Druck stabil bei 210 bar und
> der Fehler E-47 tritt nicht mehr auf [P-08].

Citations: 1, `P-08`, **4 of 5 sentences uncited**. German: yes (23 German
markers, 0 English).

### Query (c) — "Lackierroboter traegt die Farbe ungleichmaessig auf, Farbnebel in der Kabine"

Sources given (top-3, all below threshold): P-03 (de), P-10 (en), P-06 (de).

**`meta-llama/Llama-3.3-70B-Instruct`** — 741 + 11 tokens, 9.1 s

> No protocol in the knowledge base covers this case.

Refusal: correct, 0 citations, nothing invented. **Language: English — the
question was German.**

**`Qwen/Qwen3-30B-A3B-Instruct-2507`** — 747 + 17 tokens, 0.5 s

> Kein Protokoll im Wissensbasis deckt diesen Fall ab.

Refusal: correct, 0 citations, nothing invented. Language: German (the article
is wrong — "im Wissensbasis" — but the language rule is respected).

## Appendix B — system prompt used

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

`temperature=0.0`, `max_tokens=500`. The user message is the top-3 protocols
verbatim, each prefixed with `[P-nn]`, then the question.

## Appendix C — Nebius model catalogue (27 models, 2026-08-06)

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

Only `Qwen/Qwen3-Embedding-8B` is an embedding model.

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
AI Model Hub section, retrieved 2026-08-06. Listed for completeness — **no
IONOS measurement was taken**, so no cost per query is derived from them:

| Model | Input | Output |
|---|---|---|
| Llama 3.3 70B Instruct | €0.65 | €0.65 |
| Qwen3.5-9B | €0.10 | €0.15 |
| Qwen3.5-397B-A17B | €0.60 | €3.60 |
| bge-m3 (embedding) | €0.02 | — |
| paraphrase-multilingual-mpnet-base-v2 | €0.01 | — |
| bge-large-en-v1.5 | €0.015 | — |

Note the order of magnitude: IONOS lists Llama 3.3 70B at €0.65/€0.65 against
Nebius' $0.13/$0.40 — roughly 3–5× more per answer. At this project's volume
that is still under €5/month, so price is unlikely to decide ADR-002 either way.

## Appendix E — reproducing this run

```powershell
cd spike/adr-002
cp .env.example .env    # paste the keys
docker run --rm -v "${PWD}:/spike" -w /spike --env-file .env python:3.12-slim `
  sh -c "pip install -q -r requirements.txt && python spike.py"
```

Retrieval scores are deterministic and reproduced exactly across three runs.
Chat answers and latency vary; `results.json` holds the third run, and the
latency ranges quoted above span all three.
