-- V2 — what ingestion needs beyond the V1 baseline.
--
-- Two additions, both driven by the status lifecycle RECEIVED -> INDEXED | FAILED
-- (DECISIONS.txt): a place to record *why* something failed, and a counter that keeps
-- the embedding spend bounded (NFR-7).

-- ---------------------------------------------------------------------------
-- protocol: the failure story
-- ---------------------------------------------------------------------------
-- V1 stored the status but nowhere to say what went wrong. A protocol sitting at FAILED
-- with no reason forces whoever looks at it to re-run the failure to find out, which on a
-- provider outage means paying for it twice. The row and its file are kept on failure, so
-- this column plus the status is a retry worklist.
ALTER TABLE protocol ADD COLUMN failure_reason text;

-- When indexing completed, as opposed to when the protocol was uploaded. The gap between
-- created_at and indexed_at is the only measurement of ingestion latency that survives a
-- restart, and NFR-4 is a latency requirement.
ALTER TABLE protocol ADD COLUMN indexed_at timestamptz;

COMMENT ON COLUMN protocol.failure_reason IS
    'Why indexing failed; null unless status = FAILED. Row and source file are kept for retry.';
COMMENT ON COLUMN protocol.indexed_at IS
    'When indexing completed. Null until status = INDEXED.';

-- ---------------------------------------------------------------------------
-- embedding_budget: the NFR-7 stop
-- ---------------------------------------------------------------------------
-- A global per-day counter, not per user. Ingestion is background load triggered by a
-- Schichtleiter upload or a backlog run, so the abuse shape it guards against is a runaway
-- loop or a re-index storm, not one person clicking too often. Per-user rate limiting
-- belongs to the query path, where a request maps to a person.
--
-- One row per day rather than an append-only log: the counter is read and incremented on
-- every batch, and the useful question is only ever "how much today". Older rows stay as
-- a cheap usage history.
CREATE TABLE embedding_budget (
    usage_date    date        PRIMARY KEY,
    calls         integer     NOT NULL DEFAULT 0,
    prompt_tokens bigint      NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_embedding_budget_calls  CHECK (calls >= 0),
    CONSTRAINT ck_embedding_budget_tokens CHECK (prompt_tokens >= 0)
);

COMMENT ON TABLE  embedding_budget IS
    'Global daily embedding usage (NFR-7). One row per day; the limit itself is a config property.';
COMMENT ON COLUMN embedding_budget.calls IS
    'HTTP calls to the embedding provider, not chunks — the provider bills per request batch.';
COMMENT ON COLUMN embedding_budget.prompt_tokens IS
    'Input tokens as reported by the provider. Embeddings bill input only (ADR-002).';
