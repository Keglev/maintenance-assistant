# ADR-009: Ground an answer on an exact term, without re-calibrating the threshold

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-20 |
| **Deciders** | Carlos Keglevich |
| **Related** | ADR-002 (LLM provider, the 0.55 threshold), ADR-004 (pgvector, one query), ADR-008 (retrieval measurement), NFR-2 (grounded answers), NFR-4 (latency), PR #61, PR #62 |

## Context

The baseline (ADR-008, taken in #61 and re-taken on the repaired index in #62) left exactly one
question failing out of nineteen, and characterised it precisely enough that this decision is about
a known defect rather than about "improving search".

**G13 — "Was bedeutet KOM-04?" on PR-07.** The right protocol is retrieved at **rank 1**, scoring
**0.4288**. The Mode A/B gate is 0.55. So the reader was told *nothing* while the answer sat at the
top of the retrieved list.

The shape of the problem is the finding: **exact-term queries already win the ranking; their score
is too low to clear the gate.** A dense vector encodes meaning, and `KOM-04` has almost none — five
characters that a technician reads off a display carry certainty rather than semantics. The other
two exact-term questions (`SV0410`, a part number) clear the gate on the vector alone, so this is a
narrow gap and not a broken retriever.

## Decision

### 1. The lexical signal: literal term containment, in the same SQL statement

A term is extracted from the question when it carries **at least one letter and at least one
digit**. The retrieval query counts, per chunk, how many of those terms appear in the chunk text,
and adds a bounded fraction of that count to the cosine similarity **for ordering only**.

ADR-004's argument survives intact: relational filter, vector distance and lexical match are still
**one statement, one round trip, one store**.

```sql
(SELECT count(*) FROM unnest(string_to_array(:terms, ' ')) AS term
  WHERE term <> '' AND c.content ILIKE '%' || term || '%')          AS lexical_matches
...
ORDER BY (1 - (c.embedding <=> CAST(:vector AS vector)))
         + :lexicalWeight * (CAST(<the same count> AS float8) / :termCount) DESC
```

**Why letter-AND-digit is the whole safety argument.** Ordinary German or English prose cannot
satisfy it: `Dosierung`, `Füllmenge`, `Betriebsurlaub` carry no digits, and `400`, `250`, `30` carry
no letters. Measured across the golden set *before the code was written*: **3 of 19 questions produce
terms** (`E-47`, `SV0410`, `KOM-04`), in all three the expected protocol is among the matches, and
**both Mode B questions produce none.** The anti-hallucination property is preserved by construction,
not by tuning.

Bare numbers are excluded deliberately. `400` is a drum diameter in one protocol and a pressure, a
duration and a year in others; matching it would fire the signal on any question mentioning a
quantity. The cost is that a pure part-number question is answered by the vector alone — which it
already is, G14 clearing the gate at 0.6000 unaided.

### 2. The gate: vector similarity keeps its meaning; the term is a separate override

**This is the decision that mattered most, and both options worked.**

```
grounded  =  max(similarity) >= threshold   OR   any retrieved chunk contains a question term
```

The alternative was to fuse the two into one score and compare *that* with 0.55. It was rejected for
a reason that is about the gate's purpose rather than about elegance: **0.55 is a measured number**
(ADR-002, re-validated in #61), and it means "question-to-chunk cosine". Fusing a lexical component
into it would silently re-calibrate that number **for every question in the system, to fix one** —
and the calibration evidence would no longer describe the thing being compared.

The override is also the more explainable rule, which matters at a machine at 3 a.m.: *"the code you
typed is literally in this protocol"* is a sentence a technician can check. A fused score is a number
nobody can decompose.

It has a third property worth stating because it is what makes the change safe: **an override can
only turn Mode B into Mode A, never the reverse.** The sixteen questions that carry no term are
provably untouched by it.

**The gate reads the MAXIMUM similarity, not the first hit's.** Before this change they were the same
thing, because the list was ordered by similarity. Now the lexical weight can order a chunk first
that is not the most similar one, and reading the head would hand the gate a *lower* number than
before — turning a question that has always been Mode A into Mode B. That is a regression the
ordering change would have introduced silently, and it is closed in `QueryService` and mirrored in
the harness.

**The source list obeys the same rule as the gate.** A question grounded only by its code would
otherwise be routed to Mode A, offered no sources, produce no citation and fall through to Mode B
anyway. The gate and the source list have to agree on what counts as evidence.

**The 0.55 default is unchanged**, and the measurement did not force it.

### 3. The two components are reported separately, never fused

`RetrievedChunk`, `LabelledSource`, the API's `Citation` and the harness report all carry
`similarity` and `lexicalMatches` side by side. A single fused number would be one nobody can
decompose, and the interface needs the second component to say *why* a weak-scoring protocol was
cited.

### 4. Configuration

| property | default | argued from |
|---|---|---|
| `maintenance.query.lexical-weight` | `0.15` | the measured spread of this corpus (below) |
| `maintenance.query.similarity-threshold` | `0.55` | **unchanged**, ADR-002 |

`0.15` is a **ceiling on how much certainty an exact code may buy**. Same-machine chunk similarities
for a real question run about 0.30 at the bottom of a top-5 to about 0.71 at the top, so 0.15 lifts
an exact match several places — enough to carry it into a top-k it would otherwise miss — while a
chunk at 0.30 still lands at 0.45 and cannot displace a strong semantic match at 0.60+. Zero disables
the signal and restores pure-vector retrieval, which is what the regression test uses to prove the
two paths agree when a question carries no term.

## Alternatives rejected, with the measurements

### (a) Postgres full-text search — rejected, and measured rather than assumed

Run against the real corpus on the development database:

```sql
to_tsvector('german', '… KOM-04 …')  ->  'kom':7 '-04':8
plainto_tsquery('german','Was bedeutet KOM-04?')  ->  'bedeutet' & 'kom' & '-04'
```

**The stemmer splits the code into two lexemes**, and `plainto_tsquery` ANDs in `bedeutet`, which the
protocol does not contain — so the query matches **nothing at all**, and G13 fails outright.
`websearch_to_tsquery('german','KOM-04')` does match the right protocol and only it — but only when
the query is reduced to the bare code first, which requires the same term extraction this decision
performs anyway. Full-text then adds a stemmer between the reader and an exact string.

A second measurement made it worse: `to_tsvector('german','Die Antriebstrommeln …')` does **not**
match `plainto_tsquery('german','Antriebstrommel')`. German compound plurals are not related to their
singular by the snowball stemmer, and this corpus is made of compounds. For *exact-term* matching a
stemmer is a liability, not an asset.

It also costs a `tsvector` column or index and a migration, for a worse answer.

### (b) `pg_trgm` — rejected

Available (1.6) but not installed, so it needs an extension and a migration. Trigram similarity on a
five-character code is noisy — `KOM-04` and `KOM-05` differ by one trigram — and it answers "how
similar are these strings", which is not the question being asked. Containment of a term the user
literally typed is exact, needs no extension, and is trivially explainable.

### (c) Reciprocal Rank Fusion — rejected on the gate

RRF needs no calibrated weights, which is its attraction. It also **discards score magnitude**, and
magnitude is precisely what the Mode A/B gate reads. Adopting RRF would have meant inventing a new
gate — a far larger change than this one — and would have thrown away ADR-002's calibration to solve
a ranking problem the corpus does not have: recall@1 was already 94%, and G13's protocol was already
first.

## Results, measured on the golden set

Real provider, real corpus, `RetrievalBaselineIT`. Before is #62's committed baseline.

| metric | before | after |
|---|---|---|
| recall@1 | 16/17 (94%) | 16/17 (94%) |
| recall@3 | 16/17 (94%) | 16/17 (94%) |
| recall@5 | 17/17 (100%) | 17/17 (100%) |
| MRR | 0.9529 | 0.9529 |
| Mode A/B decision correct | 18/19 (95%) | **19/19 (100%)** |
| Mode B correctly ungrounded | 2/2 | **2/2** |
| **Answered fully correctly** | **18/19 (95%)** | **19/19 (100%)** |

| case | before, mode correct | after, mode correct |
|---|---|---|
| plain | 8/8 | 8/8 |
| e47-set | 1/1 | 1/1 |
| cross-lingual | 2/2 | 2/2 |
| **exact-term** | **2/3** | **3/3** |
| mode-b | 2/2 | **2/2** |
| v12-seed | 3/3 | 3/3 |

**Recall and MRR are identical** — the ranking did not move, which is the intended result: the
weight is a ceiling, and on this corpus the exact matches were already first. Nothing that was
correct became incorrect.

**Latency.** `EXPLAIN ANALYZE` on the development corpus, machine-filtered:

| query shape | execution | planning |
|---|---|---|
| before (pure vector) | 0.699 ms | 0.288 ms |
| after, with terms | 0.588 ms | 0.436 ms |
| after, no terms (16 of 19 questions) | 0.604 ms | 0.542 ms |

Indistinguishable at this corpus size — run-to-run noise exceeds the difference — and four orders of
magnitude inside NFR-4's 30 s. **A finding worth recording: both plans are a sequential scan with a
top-N heapsort, before and after.** The HNSW index was not serving this query even before the
change, because the machine filter makes a filtered scan cheaper across 182 chunks. So the expression
`ORDER BY` costs no index that was in use — *at this size*. On a corpus large enough for HNSW to win,
an expression `ORDER BY` cannot use it, and that is the limit at which this design must be re-taken.

## Honest limits

- **The gate widened, and that is a real trade.** An answer can now be grounded on a chunk scoring
  0.4288 — text the embedding considers weakly related — because the reader's own code is in it. The
  defence is that the term is the user's, and that the two components are shown separately so the
  interface can say which one grounded the answer.
- **One question, one corpus.** G13 is a single case in a 19-question set over 165 synthetic
  protocols, and every expectation in that set is still `ratified: false` (ADR-008). This decision is
  evidenced, not proven.
- **The generation layer is the remaining variance, not retrieval.** Retrieval and the gate are
  deterministic: measured 10 consecutive runs of G13, the gate opened every time with identical
  numbers (0.4288, one lexical match). The *answer* step declined on 2 of 17 observed calls, returning
  no claims and falling through to Mode B — the model applying its own rule 4 ("if the sources do not
  answer the question, return an empty list") to a definitional question backed by a single weak
  source. Committed baselines therefore show G13 as OK in some runs and MISS in others; two of three
  harness runs during this work showed MISS. **This is generation variance at temperature 0.1 and is
  outside ADR-009's scope**, but it is the honest reason the "19/19" figure above is a typical result
  rather than a guaranteed one.
- **Substring, not word-boundary, matching.** `E-47` would match a hypothetical `E-470`. No such pair
  exists in this corpus, and the failure mode is a spurious *grounding*, never a spurious refusal.
- **A question mentioning a code that the corpus only mentions in passing** will now ground on it.
  That is the intended behaviour and also its sharpest edge.

## Consequences

The exact-term gap the baseline identified is closed on the measured set, at the cost of one bounded
config property, one SQL sub-select and a widened gate whose widening direction is provably one-way.
`0.55` still means what ADR-002 measured. The reranker stays dropped (#62): recall@1 is 94% and the
one question at rank 2–5 is already answered correctly, so there is still nothing for it to reorder.

Remaining for v1.3.0: the threshold decision — the sweep in `baseline.md` now shows `0.48`–`0.55` as a
flat plateau with no wrongly-grounded Mode B question — and the tag.
