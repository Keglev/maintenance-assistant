# 2. Architecture Constraints

## 2.1 Technical constraints

| # | Constraint | Background |
|---|---|---|
| TC-1 | **All processing on EU infrastructure**, LLM inference via an EU-hosted provider, no training on submitted data. | NFR-1; the core product claim. Disqualifies US-hosted model APIs and SaaS vector services outright. |
| TC-2 | **Single VPS, ~8 GB RAM** (Hetzner CX33, x86_64 — revised from the planned CAX21/arm64 on 2026-08-06, see DECISIONS.txt). Full stack via one `docker compose up`. | NFR-5 and the cost decisions: 4 GB was rejected (OOM risk during indexing), 8 GB confirmed. |
| TC-3 | **Published images are `linux/amd64`.** The *base* images stay multi-arch, so the source builds on a laptop of either architecture; only the images CI publishes are single-platform. | AMENDED 2026-08-25. The constraint used to read "every image is published for amd64 **and** arm64" — untrue since 2026-08-10, when both deploy workflows dropped the arm64 leg (DECISIONS.txt, "REVISED AGAIN 2026-08-10"): the arm64 image had no consumer, and building it under QEMU emulation hung 13+ minutes before dying on `npm ci` with "qemu: uncaught target signal 4 (Illegal instruction)". Moving to an arm64 host is therefore a build-platform change — one line per workflow, or better a native arm64 runner — and still not a code change. |
| TC-4 | **Java 21 · Spring Boot 4.1**. Boot 3.x is EOL since 2026-06-30. | Decided stack. Spring AI is used only if compatible with Boot 4.1; fallback is a plain `RestClient`. |
| TC-5 | **Angular + TypeScript** for the frontend, kept thin (three views: search, upload, login redirect). | Decided stack; also a deliberate portfolio choice for the German enterprise market. |
| TC-6 | **PostgreSQL + pgvector** as the only datastore; no dedicated vector database. | [ADR-004](../adr/ADR-004-pgvector-for-vector-search.md). |
| TC-7 | **Keycloak as the identity provider**; the realm is exported and versioned so the stack is reproducible. | [ADR-003](../adr/ADR-003-keycloak-for-iam.md). |
| TC-8 | **Multilingual embedding model is a hard requirement.** Nothing is translated anywhere; texts are stored as written and DE↔EN is bridged by the embedding space. | DECISIONS.txt; [ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md). |
| TC-9 | **Hard ceiling on LLM spend** at provider, application and logging layers. | NFR-7; the demo is public and therefore abusable. |
| TC-10 | **CI/CD via GitHub Actions** with automated tests (JUnit/Mockito) on every push. | NFR-5. |

## 2.2 Organisational constraints

| # | Constraint | Background |
|---|---|---|
| OC-1 | **Solo developer, 3–4 week budget**, split into phases 1–4 with an optional phase 5. | PROJECT-PHASES.txt. Rules out anything that multiplies operational surface — see [ADR-001](../adr/ADR-001-modular-monolith-first.md). |
| OC-2 | **The demo must always work**, and is tested before every application sent out. | A personal rule following concrete recruiter feedback about a broken demo. |
| OC-3 | **Every architecture change gets an ADR.** | PROJECT-PHASES.txt, rules carried across sessions. |
| OC-4 | **Synthetic data only.** ~150 generated protocols, no real customer documents. | NFR-1; also removes any confidentiality question from the public demo. |
| OC-5 | **AI usage is documented** in `AI-USAGE.md`. | NFR-6. |

## 2.3 Conventions

- **Documentation in English**, product UI bilingual DE/EN (US-8). Domain terms stay German where
  they are the real shop-floor vocabulary (*Störung*, *Schichtleiter*, *Techniker*) — see the
  [glossary](12-glossary.md).
- **arc42** for architecture documentation, **MADR-style ADRs** for decisions.
- **Conventional commits**; small, reviewable commits.
- Realm name `maintenance`, repository name `maintenance-assistant`.
