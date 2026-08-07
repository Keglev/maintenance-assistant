# ADR-002 spike — EU-hosted LLM provider evaluation

Self-contained evidence gathering for
[ADR-002](../../docs/adr/ADR-002-eu-hosted-llm-provider.md): **Nebius Token
Factory** vs **IONOS AI Model Hub**. It answers the ADR's open questions with
measurements against the live APIs rather than with vendor claims.

The measured results are in [RESULTS.md](RESULTS.md); the raw run output is in
`results.json`. This directory is committed on purpose — the evaluation method
is part of the story, and the run is reproducible.

## What it measures

| # | Question | How |
|---|---|---|
| 1 | Which models does the provider actually serve? | `GET /v1/models`, then pick the best multilingual embedding model and a mid-size instruct model |
| 2 | Does a German query retrieve an **English** protocol? | 10 protocols (7 DE / 3 EN), the only E-47 protocol is English; cosine top-3 |
| 3 | Does one similarity threshold separate hits from misses? | a hit query, a cross-language hit query, and a query with no answer in the corpus |
| 4 | Are citations and the answer language respected? | strict system prompt, top-3 hits as sources, `[P-nn]` per claim |
| 5 | Does the model admit "no source" instead of inventing one? | same prompt on the no-answer query |
| 6 | What does one answer cost? | `usage` fields of the real responses × published per-token prices |

The threshold from (3) is the Mode A / Mode B switch of NFR-2, and the corpus
here mirrors the demo seeds of `docs/DOMAIN-MODEL.md`.

## Round 2

Round 1 found three defects: the smaller chat models summarise instead of
citing per claim, Llama-3.3-70B refuses in English to a German question, and
generation latency is uneven. `round2.py` attacks all three with prompt
engineering and re-measures **all four chat models on both providers**:

| # | Change | Measured by |
|---|---|---|
| a | one **few-shot worked example** of the `[P-nn]`-per-claim format | citations per sentence on the grounded query |
| b | an explicit **language pin** ("all output, including refusals") | refusal language on the no-answer query |
| c | `max_tokens=400`, the NFR-7 cap | generation latency, 3 repeats |
| d | **structured JSON** (`{answer_language, claims:[{text, source}]}`) | claims carrying a source, parse rate |

Each change is tested in isolation (V0 control, V1 few-shot, V2 language pin,
V3 both), so an improvement is attributable to the change rather than to prompt
length. Round 2 reads the top-3 hits out of `results.json` and computes no
embeddings, so it measures generation only — **run `spike.py` first**.

## Running it

Put the keys in `.env` (never committed):

```bash
cp .env.example .env   # then paste the keys
```

With Python 3.12+ on the machine:

```bash
pip install -r requirements.txt
set -a && . ./.env && set +a     # PowerShell: see below
python spike.py                  # round 1; optional arg: `nebius` or `ionos`
python round2.py                 # round 2; same optional provider filter
```

Without a local Python — the way this run was done — through Docker:

```powershell
docker run --rm -v "${PWD}:/spike" -w /spike --env-file .env python:3.12-slim `
  sh -c "pip install -q -r requirements.txt && python spike.py"
```

Note for `--env-file`: Docker does not strip quotes, so write `KEY=value`
without quoting.

`spike.py` takes provider keys as arguments (`python spike.py ionos`) and
**merges** into an existing `results.json`, so re-running one provider keeps
the other's recorded run. Round 2 makes 44 generation calls and takes 15–25
minutes, most of it waiting on Nebius' Llama-3.3-70B.

## Files

| File | |
|---|---|
| `spike.py` | round 1: discovery, retrieval, threshold, citation, refusal, cost |
| `round2.py` | round 2: few-shot citations, language pinning, latency at the NFR-7 cap, structured JSON |
| `corpus.py` | the 10 test protocols and the 3 queries, with the invariants that must not be edited away |
| `results.json` | raw output of round 1 (models, scores, answers, usage, latency), both providers |
| `round2.json` | raw output of round 2 (every prompt variant × query × model, plus the prompts used) |
| `RESULTS.md` | the comparison table, the appendix of raw answers, and a **DRAFT** recommendation |

`RESULTS.md` is written from `results.json` by hand — the numbers are
mechanical, the interpretation is reviewable. The recommendation is marked
DRAFT: the ADR decision belongs to the project owner.
