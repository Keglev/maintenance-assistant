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
}

/** The unfiltered corpus: what the view shows until someone narrows it. */
export const NO_FILTER: ProtocolFilter = {
  machineNo: '',
  titleContains: '',
  from: '',
  to: '',
};

/** One page of the corpus. `total` is what turns "Weiter" into "page 3 of 16". */
export interface ProtocolPage {
  readonly items: readonly ModeratedProtocol[];
  readonly page: number;
  readonly size: number;
  readonly total: number;
}
