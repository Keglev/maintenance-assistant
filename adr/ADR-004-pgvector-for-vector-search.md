# ADR-004: PostgreSQL + pgvector Instead of a Dedicated Vector Database

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-05 |
| **Deciders** | Project owner (solo) |
| **Related** | [ADR-001](ADR-001-modular-monolith-first.md); implements US-3 and NFR-5 |

## Context

RAG requires storing embeddings and running nearest-neighbour similarity search, filtered by
relational attributes (`machine_id`, US-3). Corpus size: ~150 protocols ≈ well under 5,000 chunks.
Constraint: a single ~8 GB VPS with a minimal container count (NFR-5).

## Decision

PostgreSQL with the pgvector extension. Chunks live in a normal table with a `vector` column;
retrieval is one SQL query combining the relational filter and cosine-distance ordering:

```sql
SELECT ... FROM chunk
WHERE machine_id = :machine
ORDER BY embedding <=> :query_embedding
LIMIT 5;
```

## Consequences

**Positive**

- One database for relational data **and** vectors: fewer containers, one backup, transactional
  consistency between protocol metadata and chunks (no sync problem between two stores).
- The machine filter and the vector search happen in the same query plan — with a dedicated vector
  DB this requires metadata-filter features and cross-store consistency handling.
- At this corpus size, exact (non-indexed) search is already fast; an HNSW index can be added with
  one statement if the corpus grows. The scale ceiling is millions of vectors — orders of magnitude
  above this use case.

**Negative**

- Not the right choice for very large corpora or extreme QPS; documented deliberately as a
  scale-aware decision, not ignorance of the alternatives.

## Alternatives considered

- **Dedicated vector DB (Qdrant, Weaviate, Milvus, Chroma)** — built for scale this project will
  never reach; costs an extra container plus a consistency strategy between Postgres and the vector
  store. Choosing infrastructure for imaginary scale is the same anti-pattern as day-1
  microservices (see ADR-001). *Rejected.*
- **Managed vector service (Pinecone)** — SaaS dependency, US processing by default; contradicts
  NFR-1. *Rejected.*
- **Elasticsearch/OpenSearch kNN** — capable, but a heavy JVM container (~1–2 GB) duplicating what
  Postgres already provides here. *Rejected.*
