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

### Note, 2026-08-07 — the vector index is HNSW, not IVFFlat

Implementing the schema (Flyway `V1__baseline_schema.sql`) forced the index choice that the
third *Positive* point above left open. It is **HNSW**:

```sql
CREATE INDEX ix_chunk_embedding_hnsw ON chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

**Why not IVFFlat.** IVFFlat partitions the vectors into `lists` cells whose centroids are
learned by k-means *from the data present when the index is built*. The migration runs against
an empty table, so an IVFFlat index created there would be trained on nothing; it would have to
be dropped and rebuilt after each ingestion run, and the `lists` parameter would have to track a
corpus size that is still changing. That is an operational obligation the project would have to
remember forever. HNSW builds its graph incrementally and is correct from the first insert — the
index is simply part of the schema.

**What it costs.** HNSW builds more slowly and uses more memory than IVFFlat, and its index is
larger. At the scale this project actually has — ~150 protocols, low thousands of chunks, a
1024-dimension vector each — that is a few tens of MB and a build measured in seconds on the
8 GB VPS. The trade-off that makes IVFFlat attractive (cheap build over millions of vectors)
does not apply here, and the trade-off that makes HNSW expensive does not bite here either.

**The honest caveat.** At this corpus size the index is not what makes the query fast. With a
selective `machine_id` filter the planner will often prefer a bitmap scan plus exact distance
computation over the HNSW graph, and it is right to: a few hundred chunks per machine are
scanned faster than the graph is traversed, and exact search cannot lose a result the way
approximate post-filtering can. The index earns its place as headroom — it means growth to tens
of thousands of chunks needs no migration — not as a present-day speed-up. Recall tuning
(`hnsw.ef_search`) is therefore deliberately left at the default until there is a corpus large
enough to measure it against.

`vector_cosine_ops` is not incidental: it matches the `<=>` operator in the retrieval query
above. An index built for a different distance function is silently ignored by that query rather
than reported as an error.

## Alternatives considered

- **Dedicated vector DB (Qdrant, Weaviate, Milvus, Chroma)** — built for scale this project will
  never reach; costs an extra container plus a consistency strategy between Postgres and the vector
  store. Choosing infrastructure for imaginary scale is the same anti-pattern as day-1
  microservices (see ADR-001). *Rejected.*
- **Managed vector service (Pinecone)** — SaaS dependency, US processing by default; contradicts
  NFR-1. *Rejected.*
- **Elasticsearch/OpenSearch kNN** — capable, but a heavy JVM container (~1–2 GB) duplicating what
  Postgres already provides here. *Rejected.*
