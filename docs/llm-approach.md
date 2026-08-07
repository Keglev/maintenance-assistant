# AI & LLM Approach

**Grounding comes first.** The assistant does not answer from what a language model happens to know
about hydraulic presses. Every question is embedded and used to retrieve the most similar chunks of
actual maintenance protocols from the corpus, filtered to the machine in question; the model's only
job is to summarise what was retrieved and cite it. A protocol is the unit of citation, a chunk the
unit of search, and a machine the unit of filtering. In a domain where a wrong answer sends someone
to a live machine, an answer that cannot be checked against its source is worse than no answer — so
every claim carries the document and date it came from.

**Two answer modes, visually distinct.** When retrieval returns hits above a similarity threshold,
the system produces **Mode A — "Belegte Antwort"**: grounded in indexed protocols, every claim
cited. When nothing clears the threshold, it says so plainly and may offer **Mode B — "Allgemeiner
Vorschlag — keine Quelle im Bestand"**: general troubleshooting, clearly labelled as ungrounded and
never presented as fact. The mode is runtime behaviour decided by that threshold, not a stored
attribute. Role-based filtering applies on top and is enforced server-side: an Operator receives
operator-safe steps and escalation advice only, never electrical or mechanical repair instructions,
no matter what the retrieved protocol contains.

**EU hosting is a requirement, not a preference.** All document and query processing happens on EU
infrastructure, with inference through an EU-hosted provider that does not train on submitted data.
That rules out the strongest US-hosted models, which is an accepted trade: retrieval narrows the
task to "summarise these protocols with citations", which mid-size open models handle well. A
multilingual embedding model is a hard requirement rather than a nice-to-have — the corpus is mixed
German and English, nothing is translated anywhere, and a German question must retrieve an English
protocol.

**The provider was chosen by measurement, not by brochure.** A two-round spike ran both candidates
against their live APIs on a purpose-built corpus, and it reversed the original proposal: inference
now runs on the **IONOS AI Model Hub** in Berlin, with Nebius Token Factory as a documented
fallback. The deciding facts were that IONOS serves three multilingual embedding models where
Nebius publicly serves one — leaving this project's hard requirement without a second option — and
that a German query retrieved the English protocol more reliably there, losing only 0.011 of
similarity crossing the language boundary. The same 70B chat model also answered five to ten times
faster. The spike additionally showed that citation discipline is a *prompt* problem rather than a
model limitation: one worked example took every model tested to a citation on every claim, and a
structured output schema can make an uncited claim impossible to express at all. Decision, evidence
and residual risks: [ADR-002](adr/ADR-002-eu-hosted-llm-provider.html).

**Spend is bounded, with one honest gap.** The plan was three layers, the first being a
provider-side spending cap — and that layer turned out not to exist: IONOS offers cost alerts, not
a hard ceiling, so a €7 alert is configured and the real ceiling is the application's own per-user
rate limiting, daily budget, capped answer length and query cache, backed by daily token logging.
Exhaustion degrades to a message rather than an error page. The measured cost makes that
acceptable: a full answer costs a fraction of a cent, and demo-volume spend lands in the low single
digits of euros per month.

Further reading: [Requirements](REQUIREMENTS.html) (NFR-1, NFR-2, NFR-7),
[Domain model](DOMAIN-MODEL.html), [ADR-004 on pgvector](adr/ADR-004-pgvector-for-vector-search.html).
