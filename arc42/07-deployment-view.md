# 7. Deployment View

> **Stub — to be filled in Phase 1** (local compose stack) and completed in Phase 4 (VPS, domain,
> TLS).

Planned infrastructure, to be documented here with a deployment diagram:

- **Local development** — three containers via `docker compose up`: the application, PostgreSQL +
  pgvector, and Keycloak (dev mode, realm imported from `docker/keycloak/realm-export.json`, exposed
  on port 8081). See [`docker/`](../../docker/).
- **Production** — a single Hetzner CAX VPS (arm64, 8 GB) running the same compose stack, behind a
  domain with TLS. Images are built multi-arch in CI.
- **Constraints already fixed:** arm64-compatible images only (TC-3), 8 GB is not reduced further
  (OOM risk during indexing), and a paused server is snapshotted and deleted rather than stopped,
  because a stopped Hetzner server still bills in full.
