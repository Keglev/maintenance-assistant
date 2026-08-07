-- V3 — the NFR-7 spend ceiling for the query path.
--
-- A second table rather than a second column on embedding_budget, because the two
-- counters bound two different things and are read by two different guards: embedding
-- is background load whose abuse shape is a re-index storm, chat is interactive load
-- whose abuse shape is a person or a script asking repeatedly. Sharing one row would
-- mean a corpus re-index could silently exhaust the day's answers, which is exactly the
-- coupling the demo cannot afford.
--
-- Same shape as embedding_budget on purpose: one row per day, incremented as each
-- provider request is served, so the two are read the same way and neither invents its
-- own convention. ADR-002 records that the provider offers cost alerts and no hard cap,
-- which is why this lives in the database and survives a restart rather than in memory.
CREATE TABLE chat_budget (
    usage_date        date        PRIMARY KEY,
    calls             integer     NOT NULL DEFAULT 0,
    prompt_tokens     bigint      NOT NULL DEFAULT 0,
    -- Chat bills output as well as input, unlike embeddings — so the two token columns
    -- are counted apart. At IONOS' Llama-3.3-70B price they cost the same per token,
    -- but a model swap can change that and a merged figure could not be re-priced.
    completion_tokens bigint      NOT NULL DEFAULT 0,
    updated_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_chat_budget_calls             CHECK (calls >= 0),
    CONSTRAINT ck_chat_budget_prompt_tokens     CHECK (prompt_tokens >= 0),
    CONSTRAINT ck_chat_budget_completion_tokens CHECK (completion_tokens >= 0)
);

COMMENT ON TABLE  chat_budget IS
    'Global daily chat usage (NFR-7), query path. One row per day; the limit is a config property.';
COMMENT ON COLUMN chat_budget.calls IS
    'Chat completion requests served by the provider, counted by the client whether or not the answer could be used.';
COMMENT ON COLUMN chat_budget.completion_tokens IS
    'Output tokens. Capped per request by maintenance.chat.max-tokens.';
