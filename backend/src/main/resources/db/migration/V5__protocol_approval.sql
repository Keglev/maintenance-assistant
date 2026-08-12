-- V5 — the trust chain: a protocol can be reviewed, and the review is on the record.
--
-- ADR-006, revision 2026-08-13. Carlos's three decisions of 2026-08-11 are what this
-- migration implements; the reasoning lives in the ADR and the one-line summaries here
-- exist so a reader of the schema alone is not misled.

-- ---------------------------------------------------------------------------
-- protocol: approval as a STATE, with an actor and a time
-- ---------------------------------------------------------------------------
-- A STATUS COLUMN, NOT A BOOLEAN, and the choice is worth stating because a boolean is
-- the obvious first answer.
--
--   1. `approved = false` means two different things — "nobody has looked at this yet"
--      and "somebody looked and withdrew their approval". This table already lost that
--      distinction once: `status` exists precisely because RECEIVED, INDEXED and FAILED
--      are not a boolean either.
--   2. The vocabulary is written down. A reader of the schema sees UNAPPROVED and
--      APPROVED rather than inferring what `false` was supposed to mean.
--   3. It matches how this schema already spells a small closed set: a check-constrained
--      varchar, exactly like `protocol.status` and `moderation_event.action`. A boolean
--      here would be a third convention for the same kind of fact.
--
-- The cost is honest: a check constraint has to be altered to add a value, where a
-- boolean needs no migration at all. That cost is only paid if the set grows, and if it
-- grows a boolean would have needed a second column anyway.
ALTER TABLE protocol
    ADD COLUMN approval_state varchar(16) NOT NULL DEFAULT 'UNAPPROVED',
    -- Keycloak username, the same identity `uploaded_by` and `moderation_event.actor`
    -- store, so who filed a protocol and who vouched for it are written in one alphabet.
    ADD COLUMN approved_by    varchar(128),
    ADD COLUMN approved_at    timestamptz;

ALTER TABLE protocol ADD CONSTRAINT ck_protocol_approval_state
    CHECK (approval_state IN ('UNAPPROVED', 'APPROVED'));

-- AN APPROVAL WITHOUT AN ACTOR IS NOT AN AUDIT RECORD. This is the whole point of
-- ADR-006 expressed as a constraint rather than as a convention: an APPROVED row must
-- say who, and when. The reverse direction matters just as much — an UNAPPROVED row must
-- NOT carry a stale approver, or the columns would quietly describe a state the protocol
-- is no longer in. The history of who approved what and when is kept where history
-- belongs, in moderation_event; these two columns describe only the present.
ALTER TABLE protocol ADD CONSTRAINT ck_protocol_approval_actor
    CHECK ((approval_state = 'APPROVED')
           = (approved_by IS NOT NULL AND approved_at IS NOT NULL));

COMMENT ON COLUMN protocol.approval_state IS
    'UNAPPROVED = filed but not yet reviewed. APPROVED = an administrator vouched for it. '
    'UNAPPROVED protocols REMAIN SEARCHABLE by decision (2026-08-11): the admin may not '
    'review at a weekend and the factory does not stop. The state is therefore exposed '
    'everywhere a protocol is used, so a reader always knows which they are reading.';
COMMENT ON COLUMN protocol.approved_by IS
    'Keycloak username of the approver. Null exactly when approval_state is UNAPPROVED.';

-- The approval queue: one machine at a time, oldest first is what a reviewer works
-- through. Partial, because the queue is the small end of the table — the approved
-- corpus is the other 90% and has no business in this index.
CREATE INDEX ix_protocol_unapproved ON protocol (machine_id, created_at)
    WHERE approval_state = 'UNAPPROVED' AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- The 150 seeded protocols are born approved (decision 2 of 2026-08-11)
-- ---------------------------------------------------------------------------
-- They are the demo's known-good corpus: every answer in every walkthrough is grounded
-- in them, and starting them as unreviewed would put 150 rows in a queue nobody is going
-- to work through and mark the entire demo as unverified.
--
-- IDENTIFIED BY THEIR DETERMINISTIC IDS. The seed inserts fixed UUIDs under the prefix
-- below (CorpusSeedRunner, "rows are inserted with fixed UUIDs"), so "the seeded corpus"
-- is a fact this migration can state precisely rather than a guess from a timestamp or
-- an uploader name a real user could also have.
--
-- THE ACTOR IS A SYSTEM NAME, NOT A PERSON. Writing a human's username here would invent
-- an audit record for a review that never happened — the exact failure this table's
-- constraint above exists to prevent. `system:corpus-seed` is not a Keycloak user and
-- cannot be mistaken for one.
UPDATE protocol
SET approval_state = 'APPROVED',
    approved_by    = 'system:corpus-seed',
    approved_at    = now()
WHERE id::text LIKE '0f9c5b02-0000-4000-8000-%'
  AND approval_state = 'UNAPPROVED';

-- ---------------------------------------------------------------------------
-- WHAT HAPPENS TO EVERYTHING ELSE ALREADY IN A LIVE DATABASE
-- ---------------------------------------------------------------------------
-- Production carries test uploads filed through the real form, and they are NOT covered
-- by the update above. They stay UNAPPROVED — the column default — and that is a
-- decision, not an oversight.
--
-- The alternative is to approve them, and it is worse in the way that matters: this
-- migration has no idea whether a human ever read them, so approving them would fabricate
-- exactly the kind of unearned trust the approval flag exists to make visible. Leaving
-- them unapproved is also safe by construction, because an unapproved protocol is still
-- searchable (decision 1) — nothing disappears from an answer. They simply appear in the
-- admin's queue on the first login after deploy, which is precisely where a handful of
-- unreviewed test uploads belong.

-- ---------------------------------------------------------------------------
-- moderation_event learns two more verbs
-- ---------------------------------------------------------------------------
-- Approving and un-approving are moderation acts, so they leave a row in the same ledger
-- as EDIT and DELETE rather than in a table of their own. One place to read "what has
-- been done to this protocol, and by whom" is the property that makes the ledger useful.
ALTER TABLE moderation_event DROP CONSTRAINT ck_moderation_event_action;
ALTER TABLE moderation_event ADD CONSTRAINT ck_moderation_event_action
    CHECK (action IN ('EDIT', 'DELETE', 'APPROVE', 'UNAPPROVE'));

COMMENT ON COLUMN moderation_event.action IS
    'EDIT = in-place correction with a forced re-index. DELETE = soft delete into the '
    'archive. APPROVE / UNAPPROVE = a reviewer vouching for a protocol, or withdrawing '
    'that. An EDIT also resets approval, and writes both rows.';
