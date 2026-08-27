# Instandhaltungs-Assistent / Maintenance Protocol Assistant

**Requirements — v0.2 (reviewed)**

## 1. Problem / Problemstellung

Maintenance knowledge in manufacturing is trapped in scattered PDFs, scans, and paper manuals locked in drawers and cabinets — and in the heads of experienced technicians. Many machines run 15–20 years on minimal software (PLC/SCADA only), so the machine itself offers little diagnostic help beyond an error code.

When a machine stops, the standard escalation applies (e.g. Operator → call Techniker after 10 minutes → Schichtleiter → management decides on manufacturer support). Every step costs downtime — and far too often the fault was already solved last month, but nobody can find the protocol. The pain peaks on night shifts with no technician on site: the operator either waits or improvises.

**Solution:** A DSGVO-compliant AI assistant that lets shop-floor staff search maintenance protocols in natural language (DE/EN) and answers with citations to the source documents. All data processing happens exclusively in the EU. The escalation chain itself is organizational context, not a system feature (this is not a ticketing/CMMS system).

## 2. Users & Roles / Nutzer & Rollen

| Role | Description | Permissions |
|---|---|---|
| Operator | Machine operator, incl. legacy terminals with intranet access | Search; receives **operator-safe instructions only** (clean sensor, remove material, change program) — never electrical/mechanical repair steps |
| Techniker | Shop-floor technician (Fachkraft) | Search; receives full technical answers incl. repair steps |
| Schichtleiter | Shift supervisor | All above + create and upload protocols (sole write access) |
| Admin | System administrator (IT role only) | All above + user/role management via Keycloak |

**Design decision:** Write access is restricted to Schichtleiter to guarantee protocol consistency and quality (language, structure, correctness) and to prevent a polluted knowledge base. Known limitation: the person who fixed the fault (Techniker) is not the person documenting it — knowledge capture depends on the shift handover.

## 3. User Stories (MVP / Phase 1)

- **US-1** — As an *Operator*, I enter an error code or message and receive only actions I am allowed and able to perform (e.g. "Sensor A3 reinigen", "Material A2 entfernen", "Programm T5 laden"). If the fix requires a Fachkraft, the system tells me to escalate — immediately, not after 40 wasted minutes.
- **US-2** — As a *Techniker*, I ask in natural language (e.g. "Presse 3 zeigt Fehler E-47, gab es das schon?") and receive an answer citing the source protocols (document + date), so I can verify it.
- **US-3** — As a *Techniker*, I can filter my search by machine, so results stay relevant.
- **US-4** — As a *Techniker*, when no relevant protocol exists, the system says so explicitly and may offer a **clearly labeled general troubleshooting suggestion** (see NFR-2, Mode B) — e.g. verify sensor, check wiring to PLC/motor, review PLC/robot program.
- **US-5** — As a *Schichtleiter*, I create or upload a protocol (PDF or text) and see its processing status (received → indexed), so I know it is searchable.
- **US-6** — As an *Admin*, I manage users and role assignments in Keycloak without code changes.
- **US-7** — As *any user*, I log in via single sign-on (Keycloak/OIDC); the UI shows only the functions my role permits.
- **US-8** — As *any user*, I can switch the UI between German and English.
- **US-9** — As *any user*, and above all as a first-time visitor with no plant experience, I am offered example questions for the machine I selected, so I can ask one that actually reaches a protocol instead of guessing; and when nothing matches, the answer tells me how many protocols exist for that machine and gives me one way to narrow the question. **Added 2026-08-27 (ADR-011)** from live testing: a two-word question correctly returns the labelled Mode B card, and to a reader who has never seen the plant that reads as a broken product rather than as an invitation to be more specific.

## 4. Non-Functional Requirements / Nicht-funktionale Anforderungen

- **NFR-1 DSGVO / Data residency:** All document and query processing exclusively on EU infrastructure. LLM inference via EU-hosted provider (pay-as-you-go); no data used for model training. Demo runs on synthetic data only.
- **NFR-2 Verifiability — two answer modes, visually distinct:**
  - **Mode A "Belegte Antwort":** grounded in indexed protocols, every claim cited. Default mode.
  - **Mode B "Allgemeiner Vorschlag — keine Quelle im Bestand":** only when no relevant protocol exists; clearly labeled as ungrounded general troubleshooting; never presented as fact; for Operators restricted to operator-safe steps and escalation advice.
- **NFR-3 Security:** Authentication/authorization via Keycloak (OIDC, Authorization Code Flow + PKCE). Backend as OAuth2 Resource Server; role-based access **and role-based answer filtering** enforced server-side.
- **NFR-4 Performance:** Target ≤ 10 s per answer; up to 30 s acceptable. Upload confirmation immediate, indexing asynchronous.
- **NFR-5 Operability:** Full stack runs via one `docker compose up` on a single VPS (~8 GB RAM). CI/CD via GitHub Actions with automated tests (JUnit/Mockito) on every push.
- **NFR-6 AI transparency:** Repo contains `AI-USAGE.md` documenting AI tools used, generated vs. self-written code, and the verification process.
- **NFR-7 Cost control (public demo):** LLM spend is bounded at three layers: provider-side billing controls, per-user rate limiting + global daily call budget + capped answer length + query caching (application), and daily token-usage logging (visibility). Budget exhaustion degrades gracefully with a user-facing message, never with an error page. **Amended 2026-08-06 (ADR-002):** the chosen provider offers **cost alerts only, no hard spending cap** (a €7 alert is configured), so layer 1 detects rather than prevents and the application layer is the real ceiling. The requirement stands; the measured cost per query (a fraction of a cent) is what makes carrying it in the application acceptable.

## 5. Out of Scope (Phase 1)

- Kafka / separate microservices (planned Phase 2: extraction of ingestion service — see ADR-001)
- Ticketing / escalation workflow / CMMS functionality
- OCR for scanned documents and digitization of paper manuals (stretch goal)
- Multi-tenancy, mobile app, protocol editing/versioning

## 6. Acceptance Demo (recruiter walkthrough, ≤ 90 seconds)

1. Log in as `operator` (night-shift scenario): enter "Fehler E-47 Presse 3" → operator-safe instruction with source, or immediate escalation advice.
2. Log in as `techniker`: same question → full technical answer citing two past protocols (cause + fix). Then ask something with no match → Mode B appears, clearly labeled.
3. Log in as `schichtleiter`: upload a protocol, watch status change to "indexed", find it via search.

Demo credentials in README — **tested before every application sent out.**

## 7. Tech Stack (decided — details in ADRs)

Java 21 · Spring Boot 4.1 · Angular + TypeScript (thin) · PostgreSQL + pgvector · Keycloak · EU-hosted LLM (IONOS AI Model Hub, Berlin; Nebius Token Factory as documented fallback — ADR-002) · Docker Compose · GitHub Actions · Hetzner VPS
