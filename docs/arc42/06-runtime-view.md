# 6. Runtime View

> **Stub — to be filled in Phase 3**, when the query and ingestion flows exist end to end.

Scenarios planned for documentation here, each as a sequence diagram under
[`diagrams/`](diagrams/):

1. **Login** — Authorization Code Flow + PKCE between browser, Keycloak and the backend resource
   server.
2. **Search, Mode A** — question → embedding → filtered vector search → hits above threshold →
   grounded answer with citations, filtered by the caller's role.
3. **Search, Mode B** — same path, no hit above threshold → explicit "no source in the corpus" plus
   a clearly labelled general suggestion (operator-safe steps and escalation advice for Operators).
4. **Upload and indexing** — immediate `RECEIVED` confirmation, then the asynchronous
   extract → chunk → embed → `INDEXED` / `FAILED` flow driven by Spring events.
5. **Budget exhaustion** — daily budget counter reached → graceful user-facing message (NFR-7).
