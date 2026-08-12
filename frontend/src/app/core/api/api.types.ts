/**
 * The wire contract, as the backend records define it.
 *
 * Kept as plain interfaces in one file rather than generated from the OpenAPI spec: the spec is
 * published and reachable, but generating from it would add a build step and a checked-in artefact
 * to describe five endpoints. If this grows past that, the generator earns its place.
 */

/** A machine, from `GET /api/machines`. */
export interface Machine {
  readonly id: string;
  /** The plant-facing identifier (PR-03) — what people say out loud. */
  readonly machineNo: string;
  readonly name: string;
  readonly type: string;
  readonly location: string | null;
}

/** NFR-2's two answer modes. */
export type AnswerMode = 'A' | 'B';

/** One statement and the label of the source it came from. Mode A only. */
export interface Claim {
  readonly text: string;
  /** Matches a {@link Citation.label} — `P1`, `P2`, … */
  readonly source: string;
}

/** A cited protocol. Mode A only; Mode B returns an empty list. */
export interface Citation {
  readonly label: string;
  readonly protocolId: string;
  readonly title: string;
  readonly errorCode: string | null;
  readonly incidentDate: string | null;
  /** Cosine similarity, 0..1. Shown so the demo can say why this is Mode A and the next is Mode B. */
  readonly similarity: number;
  /**
   * Whether an administrator has vouched for the protocol behind this citation.
   *
   * On the citation rather than fetched per source, because a client that had to ask a second
   * endpoint for it would eventually forget to — and forgetting is the failure that matters here.
   * Unapproved protocols stay searchable by decision (2026-08-11), so a Mode A answer can be
   * grounded in text nobody has reviewed, and NFR-2's citation discipline makes any cited claim
   * LOOK checked. This flag is what keeps that impression honest.
   */
  readonly approved: boolean;
}

/**
 * The answer, from `POST /api/query`.
 *
 * `answer` already carries its inline `[P1]` markers and its claims are already validated against
 * what was retrieved. The client renders it; it does not re-parse, reorder or second-guess it.
 */
export interface QueryAnswer {
  readonly mode: AnswerMode;
  readonly answer: string;
  /** The language the backend pinned the answer to — independent of the UI language. */
  readonly language: string;
  readonly claims: readonly Claim[];
  readonly citations: readonly Citation[];
}

/** One of the caller's uploads, from `GET /api/protocols/mine`. */
export interface UploadStatus {
  readonly id: string;
  readonly machineNo: string;
  readonly title: string;
  readonly status: 'RECEIVED' | 'INDEXED' | 'FAILED';
  /** Why it failed; null unless FAILED. */
  readonly failureReason: string | null;
  readonly createdAt: string;
  readonly indexedAt: string | null;
}

/** What `POST /api/protocols` answers with its 202. */
export interface UploadAccepted {
  readonly id: string;
  readonly status: string;
  readonly message: string;
}

/**
 * One protocol as the moderation view sees it, from `GET /api/moderation/protocols`.
 *
 * `uploadedBy` is the reason this view exists rather than a nicety: the threat ADR-006 describes is
 * a plausible protocol filed by an authorised writer, and a reviewer who can see the protocol but
 * not its author can remove the text without learning anything.
 */
export interface ModeratedProtocol {
  readonly id: string;
  readonly machineNo: string;
  readonly title: string;
  readonly protocolType: string;
  readonly errorCode: string | null;
  readonly uploadedBy: string;
  readonly uploadedAt: string;
  readonly status: 'RECEIVED' | 'INDEXED' | 'FAILED';
  /** Searchable pieces this protocol contributes; 0 means stored but not retrievable. */
  readonly chunkCount: number;
  /** Who vouched for this protocol, and when. On every row, not only on the queue. */
  readonly approval: Approval;
}

/** The two states approval can be in. A closed set on the wire, matching the schema's constraint. */
export type ApprovalState = 'APPROVED' | 'UNAPPROVED';

/**
 * One protocol's approval, as `PUT …/approval` returns it and as every list row carries it.
 *
 * `approvedBy` and `approvedAt` are non-null exactly when the state is `APPROVED` — a database
 * constraint says so, which is why this is a state plus its evidence rather than three independent
 * fields the client has to reconcile.
 */
export interface Approval {
  readonly state: ApprovalState;
  readonly approvedBy: string | null;
  readonly approvedAt: string | null;
}

/**
 * What the corpus list is narrowed to. Every field is a form value, so "" means "not filled in".
 *
 * The machine is not optional in practice: the backend refuses a title or date filter without one
 * (400 `MACHINE_REQUIRED_FOR_FILTER`), because across ten machines a title fragment on its own
 * answers with rows the reviewer was not looking at. The view enforces the same rule by disabling
 * the other fields, so the 400 is a backstop rather than something a user can reach.
 */
export interface ProtocolFilter {
  readonly machineNo: string;
  readonly titleContains: string;
  /** ISO `yyyy-MM-dd`, as an `<input type="date">` produces it. */
  readonly from: string;
  readonly to: string;
  /**
   * The approval queue, and the one filter that is NOT subject to the machine-first rule.
   *
   * That rule exists because a title fragment across ten machines answers with rows the reviewer was
   * not looking at. "Everything still waiting for review" is the opposite: a queue is only useful
   * whole, and it is the first question an administrator asks after signing in. The backend agrees —
   * it applies `approvalState` without requiring `machineNo`.
   */
  readonly approvalState: ApprovalState | '';
}

/** The unfiltered corpus: what the view shows until someone narrows it. */
export const NO_FILTER: ProtocolFilter = {
  machineNo: '',
  titleContains: '',
  from: '',
  to: '',
  approvalState: '',
};

/** One page of the corpus. `total` is what turns "Weiter" into "page 3 of 16". */
export interface ProtocolPage {
  readonly items: readonly ModeratedProtocol[];
  readonly page: number;
  readonly size: number;
  readonly total: number;
}

/**
 * A correction, for `PUT /api/moderation/protocols/{id}`.
 *
 * `machineNo` and `protocolType` are sent back unchanged and checked rather than applied — the
 * backend answers 400 `PROTOCOL_IDENTITY_LOCKED` if either differs. They are in the shape because
 * the form submits every field it shows, and the edit dialog shows both, read-only: a protocol's
 * machine is its provenance, not its content (ADR-006 revision). Words can be fixed, identity
 * cannot.
 */
export interface ProtocolCorrection {
  readonly machineNo: string;
  readonly protocolType: string;
  readonly title: string;
  readonly errorCode: string;
  readonly content: string;
  /** Why. Required — the backend refuses a blank one, and so does the dialog. */
  readonly comment: string;
}

/**
 * A protocol in the archive, from `GET /api/moderation/protocols/deleted`.
 *
 * It is out of retrieval, out of every list and out of "Meine Uploads"; this is the only place it
 * still appears. There is no restore, by design — the archive exists so that removing garbage does
 * not also destroy the record of who produced it.
 */
export interface ArchivedProtocol {
  readonly id: string;
  readonly machineNo: string;
  readonly title: string;
  readonly protocolType: string;
  readonly errorCode: string | null;
  readonly uploadedBy: string;
  readonly uploadedAt: string;
  readonly deletedAt: string;
  readonly deletedBy: string | null;
  /** The stated reason. The field the whole archive exists to carry. */
  readonly deleteComment: string | null;
}

/**
 * One page of the archive.
 *
 * `cap` is the per-machine ceiling, sent by the backend rather than hard-coded here so the hint on
 * screen cannot drift away from the number the purge actually enforces.
 */
export interface DeletedProtocolPage {
  readonly items: readonly ArchivedProtocol[];
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly cap: number;
}

/**
 * What `GET /api/health` answers.
 *
 * Public by design (see `HealthController`): it is what a load balancer, the compose healthcheck
 * and a recruiter clicking the demo URL hit first, and none of them hold a token.
 *
 * `version` is the BACKEND's version, and nothing reads it yet — the footer indicator uses only
 * `status`. It is typed here because it is part of the response, not because this PR shows it: the
 * Settings dialog shows the FRONTEND version from package.json, and putting two different versions
 * on screen in one release is how a reader learns to distrust both.
 */
export interface HealthStatus {
  readonly status: string;
  readonly version: string;
}
