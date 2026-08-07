-- V1 — baseline schema: machine -> protocol -> chunk.
--
-- Source of truth: docs/DOMAIN-MODEL.md and docs/DECISIONS.txt ("DATA MODEL").
-- Roles: machine = filter unit, protocol = citation unit, chunk = search unit.
--
-- Two decisions from those documents are load-bearing here and are not free to change:
--   * the embedding column is vector(1024) because ADR-002 fixed the embedding model to
--     BAAI/bge-m3 (1024 dimensions). pgvector dimensions are part of the column type, so a
--     different model is a migration, not a config change.
--   * users are NOT in this schema. Identity lives in Keycloak (ADR-003); `uploaded_by`
--     stores the username claim as a plain string for traceability, deliberately without
--     a foreign key to anything.

-- ---------------------------------------------------------------------------
-- Extension
-- ---------------------------------------------------------------------------
-- Idempotent on purpose: the compose image (pgvector/pgvector) ships the extension files
-- but does not enable it in our database, and a fresh volume, a restored dump and a
-- re-run of `flyway clean` must all reach the same state without special-casing.
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- machine — the filter unit (US-3: search is scoped to one machine)
-- ---------------------------------------------------------------------------
CREATE TABLE machine (
    id           uuid         PRIMARY KEY,
    machine_no   varchar(32)  NOT NULL,
    name         varchar(128) NOT NULL,
    type         varchar(128) NOT NULL,
    manufacturer varchar(128),
    -- Legacy equipment is expected: 15-20 year old machines are the normal case in the
    -- plants this models, and age is part of what makes two machines of the same type
    -- behave differently.
    year_built   smallint,
    control_type varchar(16)  NOT NULL,
    location     varchar(128),
    created_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_machine_machine_no  UNIQUE (machine_no),
    CONSTRAINT ck_machine_control_type
        CHECK (control_type IN ('PLC', 'SCADA', 'CNC', 'TERMINAL')),
    CONSTRAINT ck_machine_year_built  CHECK (year_built BETWEEN 1950 AND 2100)
);

COMMENT ON TABLE  machine              IS 'Equipment master data; the filter unit for retrieval (US-3).';
COMMENT ON COLUMN machine.machine_no   IS 'Plant-facing identifier, e.g. PR-03. Unique, human-quoted.';
COMMENT ON COLUMN machine.control_type IS 'PLC | SCADA | CNC | TERMINAL.';

-- ---------------------------------------------------------------------------
-- protocol — the citation unit (what an answer points at)
-- ---------------------------------------------------------------------------
CREATE TABLE protocol (
    id                  uuid         PRIMARY KEY,
    machine_id          uuid         NOT NULL,
    incident_date       date         NOT NULL,
    protocol_type       varchar(16)  NOT NULL,
    -- Free text by decision, not a lookup table: real plants run mixed code catalogues
    -- across machine generations and a code sometimes points at the wrong problem. The
    -- actual retrieval path is semantic search over the symptom text; the code is a hint.
    error_code          varchar(64),
    title               varchar(255) NOT NULL,
    -- Structured fields rather than one blob. Protocols created through the Schichtleiter
    -- form fill all three; text extracted from an uploaded file lands in `symptom` and
    -- leaves the others null, which is a visible quality signal rather than a defect.
    symptom             text,
    cause               text,
    action              text,
    parts_used          text,
    downtime_minutes    integer,
    technician_initials varchar(8),
    language            varchar(2)   NOT NULL,
    -- Path on the Docker volume. The original file is never a BLOB in Postgres: it keeps
    -- the database small and the backup a plain pg_dump.
    source_file         varchar(512),
    status              varchar(16)  NOT NULL DEFAULT 'RECEIVED',
    -- Keycloak username claim. No FK — see the header note on users.
    uploaded_by         varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT fk_protocol_machine FOREIGN KEY (machine_id)
        REFERENCES machine (id) ON DELETE CASCADE,
    CONSTRAINT ck_protocol_type
        CHECK (protocol_type IN ('STOERUNG', 'WARTUNG')),
    -- The full lifecycle, enforced as a set rather than as transitions: RECEIVED is the
    -- state on upload, INDEXED and FAILED are the two terminal outcomes of embedding.
    CONSTRAINT ck_protocol_status
        CHECK (status IN ('RECEIVED', 'INDEXED', 'FAILED')),
    CONSTRAINT ck_protocol_language   CHECK (language IN ('de', 'en')),
    CONSTRAINT ck_protocol_downtime   CHECK (downtime_minutes IS NULL OR downtime_minutes >= 0)
);

COMMENT ON TABLE  protocol             IS 'Maintenance and fault protocols; the citation unit of an answer.';
COMMENT ON COLUMN protocol.error_code  IS 'Free text by decision (mixed plant catalogues), a retrieval hint only.';
COMMENT ON COLUMN protocol.status      IS 'RECEIVED -> INDEXED | FAILED. Set by the ingestion module (Phase 2).';
COMMENT ON COLUMN protocol.source_file IS 'Path of the original file on the Docker volume; no BLOBs in the database.';
COMMENT ON COLUMN protocol.uploaded_by IS 'Keycloak username string. Users are not rows in this database.';

-- ---------------------------------------------------------------------------
-- chunk — the search unit
-- ---------------------------------------------------------------------------
CREATE TABLE chunk (
    id          uuid        PRIMARY KEY,
    protocol_id uuid        NOT NULL,
    -- Not in DOMAIN-MODEL.md: position of the chunk within its protocol. It makes
    -- re-indexing a delete-and-reinsert with a stable identity and gives citations a
    -- deterministic order.
    chunk_index integer     NOT NULL,
    content     text        NOT NULL,
    -- 1024 dimensions = BAAI/bge-m3 (ADR-002). Changing the embedding model changes this
    -- type and requires re-embedding the whole corpus.
    embedding   vector(1024),
    language    varchar(2)  NOT NULL,
    -- Denormalized from protocol so that the relational filter and the vector ranking are
    -- one query and one index scan (ADR-004):
    --   WHERE machine_id = ? ORDER BY embedding <=> :q LIMIT 5
    -- The cost is that both columns must be rewritten if a protocol is reassigned; that
    -- happens during ingestion only, where the chunks are rewritten anyway.
    machine_id  uuid        NOT NULL,
    error_code  varchar(64),
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_chunk_protocol FOREIGN KEY (protocol_id)
        REFERENCES protocol (id) ON DELETE CASCADE,
    CONSTRAINT fk_chunk_machine  FOREIGN KEY (machine_id)
        REFERENCES machine (id) ON DELETE CASCADE,
    CONSTRAINT uq_chunk_protocol_index UNIQUE (protocol_id, chunk_index),
    CONSTRAINT ck_chunk_language       CHECK (language IN ('de', 'en')),
    CONSTRAINT ck_chunk_index          CHECK (chunk_index >= 0)
);

COMMENT ON TABLE  chunk            IS 'Embedded text fragments; the search unit of retrieval.';
COMMENT ON COLUMN chunk.embedding  IS 'vector(1024) = BAAI/bge-m3 per ADR-002. Nullable until indexing completes.';
COMMENT ON COLUMN chunk.machine_id IS 'Denormalized from protocol so filter and vector ranking share one query.';
COMMENT ON COLUMN chunk.error_code IS 'Denormalized from protocol; free text, nullable.';

-- ---------------------------------------------------------------------------
-- Relational indexes — the Phase 3 query path
-- ---------------------------------------------------------------------------
-- The retrieval query filters chunks by machine first and ranks by vector distance
-- second, optionally narrowed by an error-code hint. This is the index that decides
-- whether that filter is a scan or a lookup.
CREATE INDEX ix_chunk_machine_id ON chunk (machine_id);

-- Error code is free text typed by humans: E-47, e-47 and "E47 " all occur. Indexing the
-- normalized form means the hint query can be case-insensitive without losing the index.
-- Partial, because most chunks carry no code and those rows are dead weight in this index.
CREATE INDEX ix_chunk_machine_error ON chunk (machine_id, upper(btrim(error_code)))
    WHERE error_code IS NOT NULL;

-- Citation assembly (chunk -> protocol) and the FK's own cascade path.
CREATE INDEX ix_chunk_protocol_id ON chunk (protocol_id, chunk_index);

-- Protocol list per machine, newest first — the Schichtleiter view and the citation
-- footer both read in this order.
CREATE INDEX ix_protocol_machine_date ON protocol (machine_id, incident_date DESC);

-- Same normalization as on chunk, for queries that start from the protocol side.
CREATE INDEX ix_protocol_machine_error ON protocol (machine_id, upper(btrim(error_code)))
    WHERE error_code IS NOT NULL;

-- The ingestion worklist and the "what failed?" view. Partial: once the corpus is indexed
-- almost every row is INDEXED, so the interesting rows are a small tail.
CREATE INDEX ix_protocol_pending ON protocol (status, created_at)
    WHERE status <> 'INDEXED';

-- ---------------------------------------------------------------------------
-- Vector index — HNSW
-- ---------------------------------------------------------------------------
-- HNSW over IVFFlat. IVFFlat has to be built on populated, representative data (its lists
-- are k-means centroids) and this migration runs against an empty table, so an IVFFlat
-- index created here would be trained on nothing and would have to be rebuilt after every
-- ingestion run. HNSW builds incrementally and is correct from the first insert. Its cost
-- — build time and memory — is irrelevant at low thousands of chunks.
-- Rationale and trade-offs: ADR-004, Consequences, note of 2026-08-07.
--
-- vector_cosine_ops matches the `<=>` operator used by the retrieval query in ADR-004;
-- an index built for a different distance function is silently unused by that query.
-- m/ef_construction are pgvector's defaults, stated explicitly so a later change to them
-- shows up as a diff rather than as a version-dependent surprise.
CREATE INDEX ix_chunk_embedding_hnsw ON chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
