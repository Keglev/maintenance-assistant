# ADR-002: EU-Hosted LLM & Embedding Provider

| | |
|---|---|
| **Status** | **Proposed** — blocked on a 1-day spike (Phase 0), see *Open questions* below |
| **Date** | 2026-08-05 |
| **Deciders** | Project owner (solo) |
| **Related** | Implements NFR-1 (data residency) and NFR-7 (cost control) |

## Context

NFR-1 requires all processing on EU infrastructure with pay-as-you-go pricing and no training on
submitted data (DSGVO positioning). The system needs two model types: an **embedding model** that
MUST be multilingual — the corpus is mixed DE/EN and a German query must retrieve English
protocols, and nothing is translated anywhere — and a **chat/completion model** for answer
generation with citations. Both providers under consideration expose OpenAI-compatible APIs, so
the integration code is identical either way.

## Decision

*(Proposed — to be confirmed or revised by the spike.)*

Primary: **Nebius Token Factory** (EU regions). Reasons: publicly visible per-token pricing
(transparent for the portfolio cost story), a broad open-source model catalogue including
multilingual embedding models (BGE-M3 class), OpenAI-compatible API.

Documented fallback: **IONOS AI Model Hub** — German company, data centres in Germany, ISO 27001;
the strongest possible data-residency story, but token prices are only visible after registration.

A multilingual embedding model is a **hard requirement**, not a preference. A provider that cannot
serve one is disqualified regardless of price.

## Open questions (resolved by the 1-day spike, Phase 0)

1. **DE↔EN retrieval** — does the candidate multilingual embedding model retrieve correctly across
   languages on 10 sample protocols (German query → English protocol)?
2. **Citation prompt** — does a mid-size chat model follow the citation-format prompt reliably
   enough for Mode A ("Belegte Antwort", every claim cited)?
3. **Cost per query** — what is the real measured cost of one end-to-end answer?
4. **Billing controls** — are prepaid credit and/or hard spending limits available? Required by
   NFR-7 layer 1; without a hard provider-side ceiling the public demo is not shippable as designed.
5. **Spring AI compatibility** — is Spring AI compatible with Spring Boot 4.1? Fallback: a plain
   `RestClient` against the OpenAI-compatible endpoint (~50 lines), which keeps this ADR valid
   either way.

**Exit criterion:** all five answered → status changes to *Accepted* (or the fallback provider is
promoted to primary) before Phase 1 coding on the query module begins.

## Consequences

**Positive**

- A provider swap is a base-URL + model-name change (OpenAI-compatible APIs) — itself a talking
  point: no vendor lock-in.
- Expected demo cost: low single-digit €/month, ~5% of total running cost (the VPS is ~95%).

**Negative**

- Open-source models are weaker than frontier models; acceptable because RAG narrows the task to
  "summarise retrieved protocols with citations", which mid-size models handle well.
- Status *Proposed* means the query module cannot be finalised until the spike closes.

## Alternatives considered

- **OpenAI / Anthropic APIs** — best model quality, but US processing contradicts the project's
  core DSGVO claim. *Rejected.*
- **Self-hosted model on a GPU server** — full data control, but GPU hosting costs (≥ ~€100/month)
  and ops effort are disproportionate for a portfolio, and contradict the pay-as-you-go story.
  *Rejected.*
- **Spring AI as a mandatory abstraction** — desirable, but compatibility with Spring Boot 4.1 must
  be verified first; see open question 5. *Conditional.*
