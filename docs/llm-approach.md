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
protocol. The provider decision is still open and tracked in
[ADR-002](adr/ADR-002-eu-hosted-llm-provider.html), which lists the questions a one-day spike has to
answer before the query module is built. Spend is bounded at three layers — provider billing limits,
per-user rate limiting with a daily budget in the application, and daily token logging — and
exhaustion degrades to a message rather than an error page.

Further reading: [Requirements](REQUIREMENTS.html) (NFR-1, NFR-2, NFR-7),
[Domain model](DOMAIN-MODEL.html), [ADR-004 on pgvector](adr/ADR-004-pgvector-for-vector-search.html).
