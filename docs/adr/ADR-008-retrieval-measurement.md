# ADR-008: Measure retrieval against a golden question set before changing it

| | |
|---|---|
| **Status** | Proposed — the golden set is **not ratified** |
| **Date** | 2026-08-19 |
| **Deciders** | Carlos Keglevich |
| **Related** | ADR-002 (LLM provider, the 0.55 threshold), ADR-004 (pgvector), ADR-006 (duplicate detection, the 0.92 threshold), ADR-007 (test the rendered application), NFR-2 (grounded answers), PR #56 |

## Context

v1.3.0 is "retrieval quality": hybrid keyword + semantic search, then a decision on a reranker, then
a revalidation of the similarity threshold. Every one of those is a change to the thing that decides
what a technician is told about a machine that is down, and the project had **no way to tell whether
any of them made retrieval better.**

What existed was three demo cases in `QueryDemoVerificationIT` — E-47 on PR-03, the DE→EN conveyor
question, and the deliberate dosing gap on AB-02. They are real measurements against the real
provider and they earn their place, but three cases chosen because they demo well cannot answer "did
this change help", and two of the three are the cases every previous change was already tuned
against.

Two configured thresholds also rest on numbers that were true when taken and had not been re-taken:

- **the query threshold, 0.55** (ADR-002) — question-to-chunk, measured 2026-08-07 against a corpus
  of 150 protocols, before the 15 that v1.2 added;
- **the duplicate threshold, 0.92** (ADR-006, PR #56) — document-to-document, with a measured usable
  window only **0.0154 wide**. A window that narrow is a standing invitation to re-measure.

## Decision

**Build the instrument before making the change, and keep the instrument's inputs as data.**

### 1. A golden question set, as data, ratified by a human

`backend/src/test/resources/retrieval/golden-questions.json` holds 19 questions. Each carries the
question, its language, the machine it is asked on, the protocol(s) that are a **correct** citation,
a case label, and one line saying why that protocol is the right answer.

It is data rather than a Java array for three reasons. It is reviewed by a person who should not
have to read Java to rule on a question. It is diffed question by question across PRs, the way the
protocol corpus is. And a metric computed from a fixture living inside the test that reports it is a
number with no independent existence.

**Every entry is `ratified: false` until Carlos rules on it,** and the harness asserts that they all
are. A set that the same agent both wrote and graded measures nothing: choosing which protocol is
the right answer to "the gearbox is hot and there is oil on the floor" is a judgement about the
domain, and it is exactly the judgement a measurement cannot supply for itself.

The labelled cases, and why each is in the set:

| case | n | why |
|---|---|---|
| `plain` | 8 | the ordinary case, and the bulk of it — one question, one clearly right protocol |
| `e47-set` | 1 | one fault code, four legitimate root causes. **All four are correct**, so this measures whether the SET is retrieved, not which member ranks first |
| `cross-lingual` | 2 | a German question whose protocol is English, and the reverse. A headline claim of this project (bge-m3, no translation step) that had never been measured on a question set |
| `exact-term` | 3 | a bare alarm code, a rare code, a part number. The case hybrid search exists to fix, and expected to be weak at baseline |
| `mode-b` | 2 | questions the corpus genuinely cannot answer. Correct behaviour is a labelled ungrounded answer; a citation here is wrong however plausible it reads |
| `v12-seed` | 3 | the easier-fault protocols v1.2 added to give the approval queue something real. Nothing had measured them |

Retrieval is scoped to **one machine** (`ChunkRetriever` filters `chunk.machine_id` before ranking),
so every entry names one. A question without a machine is not a question this system can be asked.

### 2. A harness that measures and does not judge

`RetrievalBaselineIT` runs every question through the unchanged retrieval path against the real
provider and the real indexed corpus, key-gated on `LLM_API_KEY` exactly like the two manual ITs
that precede it, so CI never runs it and it never spends money by accident.

It reports per question the rank of the expected protocol, its similarity, the answer mode and
whether the expected protocol was actually cited; then recall@1/3/5, MRR and mode correctness, **each
also broken down by case label.** The breakdown is not a nicety: an aggregate that hides "every
cross-lingual question failed" is worse than no aggregate.

**It contains no assertion about quality, and must never be given one.** Its three assertions are
about the instrument — that the fixture still matches the corpus, that every question names a machine
that exists, that nothing is marked ratified, and that the stored vectors belong to the embedding
model configured now. Recall may fall to zero and this test stays green, because a falling number is
news for a person to read, not a build to break. The moment someone adds
`assertThat(recall).isGreaterThan(…)`, the set stops being a measurement and becomes a target.

### 3. The results are a committed file

The harness rewrites `backend/src/test/resources/retrieval/baseline.md` on every run and that file is
committed. PR 2 and PR 3 are judged by its **diff**. A report that only ever existed in a build
directory cannot be diffed by anyone who did not run it.

## Baseline, measured 2026-08-19

bge-m3, 1024 dims, top-k 5, query threshold 0.55, 166 live protocols in the measured database.

| metric | value |
|---|---|
| recall@1 | 14/17 (82%) |
| recall@3 | 14/17 (82%) |
| recall@5 | 14/17 (82%) |
| MRR | 0.8235 |
| Mode A/B decision correct | 16/19 (84%) |
| Mode B questions correctly ungrounded | 2/2 (100%) |
| Fully correct (right mode, and for Mode A the right citation) | 15/19 (79%) |

| case | recall@1 | mode correct |
|---|---|---|
| `plain` | 8/8 (100%) | 8/8 |
| `e47-set` | 1/1 (100%) | 1/1 |
| `cross-lingual` | 2/2 (100%) | 2/2 |
| `exact-term` | 3/3 (100%) | 2/3 |
| `mode-b` | n/a | 2/2 |
| `v12-seed` | 0/3 (0%) | 1/3 |

Three readings are worth carrying forward:

**Recall@1 equals recall@5.** Not one question had its expected protocol at rank 2–5. Retrieval
either puts the right protocol first or does not return it at all, which means a reranker (PR 3) has
nothing to reorder on this set — it would be solving a problem this corpus does not have. That is a
finding about the *roadmap*, taken before the work rather than after it.

**Cross-lingual retrieval works in both directions,** and this is the first time it has been measured
as anything other than one demo: 2/2 at rank 1, German→English at 0.7027 and English→German at
0.5582. The second is the weaker direction and it clears 0.55 by 0.0082 — a real margin, and a thin
one.

**The exact-term problem is a scoring problem, not a ranking problem.** All three exact-term
questions put the right protocol at rank 1. One of them, the bare rare code `KOM-04`, scores 0.4288
— rank 1, and *below the threshold*, so the answer is Mode B and the reader is told nothing while the
right protocol sits at the top of the retrieved list. This sharpens what PR 2 has to do: hybrid
search must lift the *score* of an exact-term match, not its rank.

## The two thresholds

### Query threshold — recommendation, not a change

The sweep is recomputed from the retrieved similarities, so the whole curve costs nothing. Three ways
a threshold can be wrong are counted separately, because a single accuracy number hides two of them.

| threshold | Mode A | answerable lost to Mode B | Mode B wrongly grounded |
|---|---|---|---|
| 0.43–0.45 | 18 | 1 | 2 |
| 0.46–0.47 | 17 | 1 | 1 |
| **0.48–0.53** | **16** | **1** | **0** |
| 0.55 (configured) | 15 | 2 | 0 |
| 0.58 | 13 | 4 | 0 |
| 0.67 | 3 | 14 | 0 |

**The windows overlap, and that is the finding.** The lowest answerable question scores 0.4288
(`KOM-04`); the lower of the two Mode B questions scores 0.4588. There is therefore **no threshold
that both keeps `KOM-04` grounded and keeps the unanswerable questions ungrounded.** `KOM-04` cannot
be fixed by moving this number, which is precisely why PR 2 is a retrieval change and not a config
change.

**Recommendation: leave 0.55 as it is, and re-measure after PR 2.** 0.48–0.53 is the widest interval
with no wrongly-grounded Mode B question, but its only advantage over 0.55 on this set is one
question whose expectation is invalid (see below), and 0.55 keeps 0.02 more margin above the highest
unanswerable question. **Nothing is changed in this PR by design:** a threshold moved in the PR that
built the instrument would be a number tuned against 19 questions before anyone had ratified them.

### Duplicate threshold — re-measured, still open

Re-taken on the current corpus with the existing `DuplicateSimilarityCalibrationIT`, which is the
instrument for it and was deliberately not reimplemented.

| | 2026-08-14 (PR #56) | 2026-08-19 | |
|---|---|---|---|
| Highest legitimate pair | 0.9151 | **0.9151** | PR-07, Halbjahres- vs Jahreswartung |
| Realistic duplicate (re-narration) | 0.9305 | **0.9305** | |
| Verbatim re-file | 0.9778 | **0.9778** | |
| Window width | 0.0154 | **0.0154** | unchanged |
| Configured 0.92 | inside | **inside** | +0.0049 above the ceiling, −0.0105 below the floor |

**The window has not closed and 0.92 still sits in it.** No change is proposed. The measurement is
reproducible to four decimal places, which is itself worth recording: the corpus, the chunker and
the model between them are stable.

One documentation defect found while re-measuring: `application.yml` names the highest legitimate
pair as "PR-07, the 4000 h and 8000 h services". The pair is PR-07's **Halbjahreswartung and
Jahreswartung**; the 4000 h and 8000 h services are KP-01's, and they are not the top pair.
`PROJECT-PHASES.txt` has it right. Corrected in this PR.

## Consequences

**Positive.** Every later retrieval change is judged against a fixed set instead of against whichever
demo the author remembered. The case breakdown makes a regression in one behaviour visible even when
the aggregate improves. Two of the three roadmap items for v1.3 already have evidence pointing at
them before any code was written — hybrid search has a precise target, and the reranker has a reason
to be questioned.

**Negative, and accepted.**

- **19 questions over 165 synthetic protocols measure direction, not quality.** A four-point move in
  recall is one question. Nothing in this ADR should be quoted as an accuracy figure for the system.
- **The set can be over-fitted to.** It is small, it is visible, and it will be sitting in the
  repository while someone tunes a retrieval change. **PR 2 must not tune against it beyond the
  threshold recommendation above**; if a change helps only these 19 questions, it has not helped
  retrieval. The defence is that the set is versioned and reviewed, so growing it to fit a result is
  a visible diff and not a quiet edit.
- **The set inherits the blind spots of the reading that produced it.** It was drafted by reading the
  corpus, so a fault the corpus covers in a way the drafter did not notice is not represented. This
  is the ratification's job to catch.
- **It costs a funded key and about three minutes** to re-take, so it is a deliberate act rather than
  something CI does. Cost is dominated by 19 chat calls out of the 400/day budget; the embedding half
  is EUR 0.000005.

## What this measurement found, that nothing else could

The first run reported 0/3 on the `v12-seed` case, with the expected protocols not merely low-ranked
but **absent**. Their true similarity to a question describing exactly the fault they document was
0.0174, −0.0083 and 0.0263 — orthogonal, not weak.

The cause is not retrieval. The 15 protocols v1.2 added carry vectors written by a **different
embedding model** than the one queries are embedded with: re-embedding a chunk's own stored text with
the configured model and comparing with the stored vector gives ~1.0 for the corpus and ≤0.04 for
these 15. They are all-positive and mutually similar at ~0.54, which is the signature of the e2e
provider stub's L2-normalised trigram counts, not of bge-m3.

**This class of defect is invisible to everything else the project has.** The rows exist, `status` is
`INDEXED`, the vectors are the right width and unit length, the ingestion counters are correct, and
every functional and integration test passes. The protocols simply can never be retrieved. So the
harness grew a fourth check, `theIndexIsInTheModelsSpace`, which re-embeds sampled stored text and
compares — and the generated report leads with a warning when it fails, so the document can never be
read as a clean measurement.

The finding is **reported and not fixed**, per the scope of the PR that took the baseline: repairing
the index would have destroyed the evidence and changed what the baseline was measured against. It is
recorded on the local development database only. **Whether production is affected is not verified**
and is the first thing to settle — the check now exists and is one run against that database away
from an answer.

---

## Revision 2026-08-19 (PR #62) — the defect closed, and what it changed

Carlos checked production: it holds **150 protocols**. The 15 v1.2 added never reached it — **v1.2
shipped as code and not as data** — so there were no corrupt vectors there to find, and the repair
was a local matter. The consequence that mattered more is that production has no `UNAPPROVED`
protocol at all, so the v1.2 trust chain cannot be demonstrated by clicking.
`seed-v12-protocols.md`, in
[`docs/runbooks/`](https://github.com/Keglev/maintenance-assistant/tree/main/docs/runbooks), is the
procedure for that, and it is a proposal until Carlos runs it. (Runbooks are repository documents
and are not published to the site. The link points at the *directory* on purpose: the site build's
Lua filter rewrites every link ending in `.md` to `.html`, including absolute ones, so a GitHub URL
naming a Markdown file is published pointing at a page GitHub does not have — see the note in
PROJECT-PHASES, two such links are already live.)

### The lesson worth keeping

**A vector's shape says nothing about its provenance.** Width, norm, `status = 'INDEXED'`, a
non-null `indexed_at` and a green test suite are all satisfied by a vector from the wrong model.
The only thing that distinguishes it is re-embedding the text and comparing, which is what
`EmbeddingProvenanceVerifier` now does.

**The e2e provider stub wrote into a database shared with development.** The stub exists for a good
reason — #51 made `reindex.e2e.ts` runnable without a funded key, and a test that never runs is
worth nothing — and it is reached exactly the way ADR-002 requires a provider to be swappable, by
pointing `LLM_BASE_URL` somewhere else. That is a supported configuration change, which is precisely
why it left no trace: nothing in the system distinguished "the provider answered" from "something
answered". The stub now names itself in every response (`e2e-provider-stub-not-a-real-model`) and
`IonosEmbeddingClient` warns when the model that answered is not the model it asked for — which also
covers the unrelated ADR-002 trap of the IONOS `*-migration` aliases resolving to another model.

**The detector was placed as a one-shot runner, not an endpoint.** An HTTP endpoint would be a
permanent surface on a public deployment that spends provider money when called and needs a role
rule, a rate limit and a controller test — for a diagnostic run perhaps twice a year. See
`EmbeddingProvenanceRunner`.

### The baseline after the repair — the first real use of it as a reference

| | #61 (corrupt index) | #62 (repaired) |
|---|---|---|
| recall@1 | 14/17 (82%) | **16/17 (94%)** |
| recall@3 | 14/17 (82%) | **16/17 (94%)** |
| recall@5 | 14/17 (82%) | **17/17 (100%)** |
| MRR | 0.8235 | **0.9529** |
| Mode A/B correct | 16/19 (84%) | **18/19 (95%)** |
| Fully correct | 15/19 (79%) | **18/19 (95%)** |
| `v12-seed` recall@1 | 0/3 (0%) | **2/3 (67%)** |

**No retrieval code changed between those two columns.** The entire difference is 15 protocols
becoming findable. That is the clearest possible demonstration of what the baseline is for, and of
how badly a corrupted index misrepresents retrieval quality.

The single remaining miss is `G13` (`KOM-04`), unchanged at rank 1 and 0.4288 — the exact-term
scoring problem, which is hybrid search's target.

### A correction to this ADR's own reading of the evidence

The section above records, from #61, that *recall@1 equals recall@5, so nothing lands at rank 2–5
and a reranker has nothing to reorder*. **That reading was taken on the corrupt index and does not
survive the repair.** After it, `G18` lands at **rank 5** — one question of 17 — so the literal claim
is now false and should not be quoted.

The conclusion nevertheless holds, for a reason that had to be checked rather than assumed:

- the one question that lands at rank 2–5 is **already answered correctly** — Mode A, citing the
  expected protocol. Re-ordering its sources would change no answer.
- the one question that is answered **wrongly**, `G13`, is at rank 1 already. A reranker reorders
  candidates; it cannot lift a similarity through the Mode A/B threshold, so it cannot fix that one
  either.

So **no question on this set is answered wrongly because of ordering**, which is the claim the
decision actually needs.

### Decision — the reranker leaves v1.3

**Carlos's decision, 2026-08-19: Qwen3-VL-Reranker-8B is dropped from v1.3.0.** v1.3 becomes
measurement (#61, #62) and hybrid search. The evidence is the corrected statement above: a reranker
would be a second model, a second latency budget and a second provider dependency, bought to fix a
failure mode this corpus does not currently exhibit.

**It returns to the roadmap when the measurement says so**, and the condition is written down here so
that the decision is re-openable on evidence rather than on taste:

- questions appear that are answered **wrongly** while the right protocol sits at rank 2–5 — that is,
  `recall@5` meaningfully above `recall@1` **with wrong answers attached to the gap**; or
- the corpus grows enough that top-k truncation starts hiding correct protocols, which the same
  harness measures by raising `top-k` and watching recall move.

Neither is true at 165 protocols. Re-read this note before the reranker is picked up again.

---

## Revision 2026-08-19 (v1.3.0 close-out) — the query threshold stays 0.55

**Carlos's decision: no change.** The number this ADR set out to revalidate has been revalidated and
keeps its value, which is a result and not an absence of one.

### The evidence

The sweep in `baseline.md`, re-measured after hybrid retrieval (ADR-009), is **flat across
0.48–0.55**. Every row is identical:

| threshold | Mode A questions | answerable lost | Mode B wrongly grounded | expected above t |
|---|---|---|---|---|
| 0.48 … 0.55 | 17 | 0 | **0** | 16 / 17 |
| 0.56 | 16 | **1** | 0 | 14 / 17 |

**Moving the threshold anywhere inside that plateau changes nothing observable on the golden set.**
A configuration change with no effect is documentation debt: it invalidates the history attached to
the old number and buys nothing measurable, so the measured value keeps its provenance. 0.55 also
sits at the *top* of the plateau, where the margin above the highest unanswerable question is
largest — 0.55 against 0.4771 — and one step further (0.56) is where the first answerable question
starts being lost.

### What removed the argument for lowering it

The exact-term case was the only thing pushing downward: G13 scored 0.4288 and needed a lower gate to
be answered at all. **ADR-009's term override solved that at every threshold**, by grounding on the
code the reader typed rather than by loosening the number for all nineteen questions. The pressure
to lower the threshold is gone, not accommodated.

### The floor, stated so nobody optimises toward it

Below the plateau the anti-hallucination guarantee starts failing, and the measured boundary is
**0.48, not lower**:

- **0.46–0.47** — one Mode B question acquires a citation (G16, scoring 0.4771).
- **0.45 and below** — both do (G15 joins at 0.4588).

So 0.48 is the floor of NFR-2's guarantee on this corpus, and the plateau is bounded *below* by
correctness rather than by preference. Any future proposal to lower the threshold has to clear that
line first. (A separate column moves earlier — `expected above t` drops from 17/17 to 16/17 at 0.43 —
but that is about which protocols may still be offered as sources, not about unanswerable questions
acquiring citations. The two are easy to confuse and mean different things.)

### The known thin spot stays known, and stays measured

**The EN→DE cross-lingual question clears the threshold by 0.0082** (G11 at 0.5582). That is a real
margin and a thin one, and the response is deliberately *not* a preemptive nudge downward: moving the
gate to buy headroom for one question would trade a measured number for a guess, and the plateau
above shows it would change nothing else anyway.

**The response is measurement.** The baseline harness re-measures that margin on every run and the
figure is in the committed report, so a narrowing is visible as a diff rather than as a surprise.

**Revisit the threshold when the harness says the margin is closing** — most plausibly when the
corpus gains a substantial number of English protocols and cross-lingual questions start landing
near or below the gate. That is the condition; a single run showing G11 below 0.55, or the
cross-lingual case dropping in `mode correct`, is the trigger to re-open this decision with fresh
numbers rather than to adjust the number quietly.

### Still open, and not this decision's to close

The golden set remains **`ratified: false`** in every entry. The threshold decision above rests on a
set no human has ruled on yet, which is the honest caveat on all of it — ratification is Carlos's and
is still outstanding.
