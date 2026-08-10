-- V4 — moderation becomes correctable and auditable (ADR-006, revision 2026-08-10).
--
-- Two additions. A protocol can now be removed without being destroyed, and every
-- moderation act leaves a row rather than only a log line.

-- ---------------------------------------------------------------------------
-- protocol: deletion that keeps the evidence
-- ---------------------------------------------------------------------------
-- #37 deleted the chunks, the row and the file. That removed the bad protocol and with
-- it the record that it had ever existed — the one thing the insider-threat chain cannot
-- afford to lose: an administrator cleaning up a poisoned protocol would erase the trace
-- of who filed it.
--
-- A protocol with deleted_at set is dead to every read path except the admin archive.
-- ADR-006 originally rejected exactly this column, on the ground that it makes every
-- query grow a filter it must never forget. That objection is answered by what the
-- delete still does rather than by discipline: THE CHUNKS ARE STILL DELETED, so
-- retrieval cannot return a deleted protocol because there is nothing left to match.
-- The `deleted_at IS NULL` guards on the row-based reads are the second line, and what
-- they prevent is a deleted protocol appearing in a LIST — visible and harmless — not
-- one being cited in an answer.
ALTER TABLE protocol ADD COLUMN deleted_at timestamptz;

COMMENT ON COLUMN protocol.deleted_at IS
    'When an administrator removed this protocol. Non-null = out of every read path except '
    'the admin archive; chunks are already gone, so it is out of retrieval by construction. '
    'There is no restore, by design (ADR-006 revision).';

-- The archive is read one machine at a time, newest deletion first. Partial, because it
-- only ever serves rows that have a deleted_at — the live corpus is the other 99% of the
-- table and has no business in this index.
CREATE INDEX ix_protocol_deleted ON protocol (machine_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- moderation_event — the audit trail as data
-- ---------------------------------------------------------------------------
-- #37 logged every deletion at INFO with the actor and the protocol's identity, and that
-- log line stays. A log is the right place for the human-readable story and the wrong
-- place for the archive to read a comment out of: it is not queryable, it rotates, and it
-- lives on a host rather than in the backup.
--
-- One row per moderation act. The comment is NOT NULL and the endpoints refuse a blank
-- one: an edit or a deletion without a stated reason is an unexplained change to the
-- corpus, which is the shape of the very thing this table exists to make visible.
--
-- NO FOREIGN KEY TO protocol, deliberately. The archive is capped at 50 deletions per
-- machine and the purge beyond that removes the row and the file — a cascade would mean
-- the fifty-first deletion erasing the record of the first, the audit function losing its
-- own audit trail. The same reasoning as `uploaded_by`, which references a Keycloak user
-- no table holds: an event is a statement about a moment, and it has to outlive its
-- subject to be worth writing down.
CREATE TABLE moderation_event (
    id          uuid         PRIMARY KEY,
    protocol_id uuid         NOT NULL,
    action      varchar(16)  NOT NULL,
    -- Keycloak username, the same identity protocol.uploaded_by stores, so the record of
    -- who removed a protocol and the record of who filed it are written in one alphabet.
    actor       varchar(128) NOT NULL,
    comment     text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_moderation_event_action CHECK (action IN ('EDIT', 'DELETE')),
    CONSTRAINT ck_moderation_event_comment CHECK (length(btrim(comment)) > 0)
);

-- The archive joins each deleted protocol to its DELETE event; the edit history of one
-- protocol is read the same way. Both are "this protocol, newest act first".
CREATE INDEX ix_moderation_event_protocol ON moderation_event (protocol_id, created_at DESC);

COMMENT ON TABLE moderation_event IS
    'One row per moderation act (ADR-006 revision). Deliberately has no FK to protocol: it '
    'must survive the archive purge that removes its subject.';
COMMENT ON COLUMN moderation_event.action IS
    'EDIT = in-place correction with a forced re-index. DELETE = soft delete into the archive.';
COMMENT ON COLUMN moderation_event.comment IS
    'Why. Required by the endpoints and by this constraint — an unexplained change to the '
    'corpus is what the table exists to make visible.';
