# ADR-002: EU-Hosted LLM & Embedding Provider

**Status:** Proposed (validate in 1-day spike) · **Date:** 2026-08-05

## Context
NFR-1 requires all processing on EU infrastructure with pay-as-you-go pricing and no training on submitted data (DSGVO positioning). The system needs two model types: an **embedding model** that MUST be multilingual (mixed DE/EN corpus; a German query must retrieve English protocols) and a **chat/completion model** for answer generation with citations. Both providers under consideration expose OpenAI-compatible APIs, so the integration code is identical.

## Decision
Primary: **Nebius Token Factory** (EU regions). Reasons: publicly visible per-token pricing (transparent for the portfolio cost story), broad open-source model catalog including multilingual embedding models, OpenAI-compatible API.

Documented fallback: **IONOS AI Model Hub** — German company, data centers in Germany, ISO 27001; strongest possible data-residency story, but token prices are only visible after registration.

Spike (1 day, before coding): verify on the chosen provider (a) a multilingual embedding model retrieves DE↔EN correctly on 10 sample protocols, (b) a mid-size chat model follows the citation-format prompt reliably, (c) real cost per query, (d) billing controls: prepaid credit and/or spending limits available (required by NFR-7 cost control).

## Consequences
- (+) Provider swap is a base-URL + model-name change (OpenAI-compatible APIs) — this is itself a talking point: no vendor lock-in.
- (+) Expected demo cost: low single-digit €/month.
- (−) Open-source models are weaker than frontier models (GPT/Claude); acceptable because RAG narrows the task to "summarize retrieved protocols with citations," which mid-size models handle well.

## Alternatives rejected
- **OpenAI / Anthropic APIs:** best model quality, but US processing contradicts the project's core DSGVO claim.
- **Self-hosted model on GPU server:** full data control, but GPU hosting costs (≥ ~€100+/month) and ops effort are disproportionate for a portfolio; also contradicts the pay-as-you-go story.
- **Spring AI as mandatory abstraction:** desirable, but compatibility with Spring Boot 4.1 must be verified in the spike; fallback is a plain `RestClient` against the OpenAI-compatible endpoint (~50 lines), which keeps the ADR valid either way.
