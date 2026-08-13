import { HttpClient, HttpErrorResponse, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ConfigService } from '../config/config.service';
import {
  Approval,
  DeletedProtocolPage,
  HealthStatus,
  Machine,
  NO_FILTER,
  ProtocolCorrection,
  ProtocolFilter,
  ProtocolHistory,
  ProtocolPage,
  QueryAnswer,
  SimilarityReport,
  UploadAccepted,
  UploadStatus,
} from './api.types';

/**
 * The handful of calls this application makes to its own backend.
 *
 * One service rather than one per feature: the API is small, and splitting it would mean three
 * files that each inject the same base URL to make one request. The access token is attached by
 * `angular-oauth2-oidc`'s resource-server interceptor, configured once in `app.config.ts`, so
 * nothing here touches a header.
 */
@Injectable({ providedIn: 'root' })
export class MaintenanceApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(ConfigService).config.apiBaseUrl;

  /**
   * Whether the backend is answering.
   *
   * Public server-side — no token is required — but the request still goes through the same
   * HttpClient and therefore still carries the Bearer token the interceptor attaches. That is
   * harmless on a public endpoint, and a bypass built to avoid it would be a second HTTP path to
   * keep in step with the first for no gain.
   */
  health(): Observable<HealthStatus> {
    return this.http.get<HealthStatus>(`${this.apiBaseUrl}/health`);
  }

  /** The machines a question can be asked about. */
  machines(): Observable<Machine[]> {
    return this.http.get<Machine[]>(`${this.apiBaseUrl}/machines`);
  }

  /**
   * Asks a question about one machine.
   *
   * Search is scoped to exactly one machine by decision, so `machineId` is required rather than
   * optional-with-a-default: a question answered from another machine's protocols would look like
   * a better feature and be a worse answer.
   */
  ask(question: string, machineId: string, approvedOnly = false): Observable<QueryAnswer> {
    // `approvedOnly` defaults to false at every caller, and that default is the decision of
    // 2026-08-11 rather than a convenience: an unapproved protocol stays searchable, because the
    // admin may not review at a weekend and the factory does not stop. The facet narrows the
    // search when a reader asks for it; it never narrows it silently.
    return this.http.post<QueryAnswer>(`${this.apiBaseUrl}/query`, {
      question,
      machineId,
      approvedOnly,
    });
  }

  /**
   * Fetches a protocol's original document.
   *
   * **Through HttpClient, and that is the whole point.** This used to be a method returning the
   * URL for an `<a href>`, and it did not work: a browser-followed anchor is a fresh navigation
   * that never passes through Angular's interceptor chain, so it carries no `Authorization`
   * header. The backend is a stateless JWT resource server with no session and no cookie fallback
   * (`SecurityConfig`), so every such click answered 401. Returning a URL at all is what invited
   * the mistake, so no method here returns one.
   *
   * `observe: 'response'` rather than the body alone because the response headers are the useful
   * half: `Content-Type` decides whether the browser renders the document or downloads it, and
   * `Content-Disposition` carries the readable filename the backend built.
   */
  getDocument(protocolId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.apiBaseUrl}/protocols/${protocolId}/document`, {
      observe: 'response',
      responseType: 'blob',
    });
  }

  /**
   * One page of the whole corpus. Admin only, server-side.
   *
   * Paged rather than "give me everything": the corpus is 150 protocols and grows every time one is
   * uploaded, and a review screen that loads all of them gets slower every month.
   */
  moderationProtocols(
    page: number,
    size: number,
    filter: ProtocolFilter = NO_FILTER,
  ): Observable<ProtocolPage> {
    // Empty fields are left off the query string entirely rather than sent as "". An empty
    // parameter is a form field nobody filled in, and a request that says `machineNo=` is a request
    // that has to be interpreted rather than read.
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filter)) {
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<ProtocolPage>(`${this.apiBaseUrl}/moderation/protocols`, { params });
  }

  /**
   * A protocol's document through the moderation path.
   *
   * A separate call from {@link getDocument} rather than a parameter, because they differ in who
   * may make them: the shop-floor path is open to the three roles that ask questions, and an admin
   * holds none of them. Same file, same mechanics, different authorisation — which is exactly the
   * kind of difference that should be visible at the call site.
   */
  getModerationDocument(protocolId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.apiBaseUrl}/moderation/protocols/${protocolId}/document`, {
      observe: 'response',
      responseType: 'blob',
    });
  }

  /**
   * Removes a protocol from the corpus and moves it into the archive. Admin only, server-side.
   *
   * The comment travels in a body rather than a query string, matching the endpoint: a sentence
   * about a named colleague's mistake would otherwise land in access logs, proxy logs and browser
   * history — three places nobody audited for it.
   */
  deleteProtocol(protocolId: string, comment: string): Observable<void> {
    return this.http.delete<void>(`${this.apiBaseUrl}/moderation/protocols/${protocolId}`, {
      body: { comment },
    });
  }

  /**
   * Corrects a protocol in place. Answers 202: the text is fixed, the search index is not yet.
   *
   * Re-indexing is the backend's condition of the edit rather than a follow-up call from here —
   * a correction that could be applied without one would leave retrieval matching text the
   * document no longer contains (ADR-006 revision).
   */
  editProtocol(protocolId: string, correction: ProtocolCorrection): Observable<UploadAccepted> {
    return this.http.put<UploadAccepted>(
      `${this.apiBaseUrl}/moderation/protocols/${protocolId}`,
      correction,
    );
  }

  /**
   * Approves a protocol, or withdraws approval. Admin only, server-side.
   *
   * A state is asserted rather than a verb performed — `PUT` of `{approved}` rather than two
   * endpoints — so a double-clicked button is harmless and writes no second audit row.
   *
   * The comment is REQUIRED to withdraw and optional to grant, and travels in the body for the same
   * reason the delete comment does: it can name a colleague's mistake, and a query string lands in
   * access logs, proxy logs and browser history.
   */
  setApproval(protocolId: string, approved: boolean, comment = ''): Observable<Approval> {
    return this.http.put<Approval>(
      `${this.apiBaseUrl}/moderation/protocols/${protocolId}/approval`,
      { approved, comment },
    );
  }

  /**
   * What has been done to one protocol, newest first. ADMIN and SCHICHTLEITER, server-side.
   *
   * Capped by the backend rather than here: a protocol edited weekly for a year has fifty rows, and
   * fetching all of them so a dialog can drop forty-seven is a payload that grows with the corpus's
   * age. The response carries `total` so the viewer can still say that older entries exist.
   */
  protocolHistory(protocolId: string): Observable<ProtocolHistory> {
    return this.http.get<ProtocolHistory>(
      `${this.apiBaseUrl}/moderation/protocols/${protocolId}/history`,
    );
  }

  /**
   * Protocols on the same machine that say close to what this one says. Admin only, server-side.
   *
   * **A read, and only ever a read.** It is asked before an approval so the approver can compare,
   * and the approval itself does not depend on it: the backend recomputes the same comparison and
   * writes what it found into the ledger, so an approval made without ever calling this records the
   * same fact. That is what makes it safe for the view to fail open when this call errors — see
   * `Moderation.approve`.
   */
  similarProtocols(protocolId: string): Observable<SimilarityReport> {
    return this.http.get<SimilarityReport>(
      `${this.apiBaseUrl}/moderation/protocols/${protocolId}/similar`,
    );
  }

  /** One page of the archive: what was removed, by whom and why. Admin only, server-side. */
  deletedProtocols(machineNo: string, page: number, size: number): Observable<DeletedProtocolPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (machineNo) {
      params = params.set('machineNo', machineNo);
    }
    return this.http.get<DeletedProtocolPage>(
      `${this.apiBaseUrl}/moderation/protocols/deleted`,
      { params },
    );
  }

  /**
   * The document of an archived protocol.
   *
   * A third document call rather than a parameter on one, for the reason the second one exists:
   * they differ in what they are allowed to serve. This is the only door back to a removed
   * protocol's content, and it is what makes the archive evidence rather than a tombstone.
   */
  getArchivedDocument(protocolId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(
      `${this.apiBaseUrl}/moderation/protocols/deleted/${protocolId}/document`,
      { observe: 'response', responseType: 'blob' },
    );
  }

  /** The caller's own uploads and what became of them. Schichtleiter only, server-side. */
  myUploads(): Observable<UploadStatus[]> {
    return this.http.get<UploadStatus[]>(`${this.apiBaseUrl}/protocols/mine`);
  }

  /** Uploads a protocol document. Answers 202: stored, not yet searchable. */
  upload(form: FormData): Observable<UploadAccepted> {
    return this.http.post<UploadAccepted>(`${this.apiBaseUrl}/protocols`, form);
  }
}

/**
 * The statuses the UI has a specific sentence for.
 *
 * Everything else collapses to a generic message, deliberately: inventing a distinct wording per
 * status code produces a wall of text nobody maintains, and the three below are the ones a user
 * can actually act on — wait a moment, come back tomorrow, or ask someone for a role.
 */
export type ApiFailure =
  | 'rateLimited'
  | 'budgetExhausted'
  | 'unavailable'
  /** The file is larger than the server's limit (413). */
  | 'tooLarge'
  /** The file was empty, not a .txt, or not text at all — the upload guards' 400 codes. */
  | 'rejectedContent'
  /** An edit tried to change the machine or the type — the identity lock (ADR-006 revision). */
  | 'identityLocked'
  /**
   * The approval was refused because the caller wrote or last corrected this protocol.
   *
   * Its own failure rather than a generic 400, because it is the only refusal in this API that the
   * reader can act on by asking a specific person: another administrator has to approve it. A
   * disabled button or a silent failure here is a bug report waiting to happen — the reader would
   * have no way to learn that the rule exists, let alone that they tripped it.
   */
  | 'fourEyesRequired'
  /** The protocol is already in the archive, and archived is final. */
  | 'archived'
  | 'forbidden'
  | 'notFound'
  | 'generic';

/**
 * Maps a failed request to the message the user should read.
 *
 * The NFR-7 guards reach the surface here, and they mean different things to a person: 429 is
 * "you, in a moment", a spent daily budget is "everyone, tomorrow", and an unreachable provider is
 * "try again shortly". One text for all three would make a rate limit look like an outage.
 *
 * The backend sends both budget exhaustion and provider trouble as 503 and distinguishes them with
 * a `reason` code in the body. The code is read rather than the message, so rewording the English
 * sentence on the server does not silently change what the German UI says.
 */
export function classify(error: unknown): ApiFailure {
  if (!(error instanceof HttpErrorResponse)) {
    return 'generic';
  }
  switch (error.status) {
    case 429:
      return 'rateLimited';
    case 503:
      return reasonOf(error) === 'BUDGET_EXHAUSTED' ? 'budgetExhausted' : 'unavailable';
    case 403:
      return 'forbidden';
    // The upload guards. 413 is the container refusing the bytes; the 400 codes are the endpoint
    // refusing the content. Matched on the `reason` code rather than on the sentence, for the same
    // reason the query path does: English prose from another layer is not an API.
    case 413:
      return 'tooLarge';
    case 400:
      if (reasonOf(error) === 'PROTOCOL_IDENTITY_LOCKED') {
        return 'identityLocked';
      }
      // The four-eyes refusal. Matched on the code rather than on the status, because a 400 from
      // this endpoint is also how a withdrawal without a reason comes back — and those two need
      // opposite sentences: one names a rule, the other names a missing field.
      if (reasonOf(error) === 'FOUR_EYES_REQUIRED') {
        return 'fourEyesRequired';
      }
      return REJECTION_CODES.has(reasonOf(error) ?? '') ? 'rejectedContent' : 'generic';
    // Only one thing answers 409 in this API: an edit of a protocol that is already archived. The
    // status alone would be enough, and the code is still checked — a second conflict added later
    // must not silently inherit this sentence.
    case 409:
      return reasonOf(error) === 'PROTOCOL_ARCHIVED' ? 'archived' : 'generic';
    // The protocol row exists and its file does not, or the id is unknown — the backend answers
    // the same 404 for both on purpose. For a source link it means one specific thing worth
    // saying: the evidence behind this claim can no longer be opened.
    case 404:
      return 'notFound';
    case 0:
    case 502:
    case 504:
      return 'unavailable';
    default:
      return 'generic';
  }
}

/**
 * The upload-rejection codes the backend sends with a 400.
 *
 * <p>All three mean the same thing to the person at the keyboard — this file is not something the
 * system can read — so they collapse to one message rather than three that differ only in which
 * technicality was hit. A 400 with any other code stays generic: those are field-level mistakes the
 * form should have prevented.
 */
const REJECTION_CODES = new Set([
  'EMPTY_FILE',
  'UNSUPPORTED_TYPE',
  'NOT_TEXT',
  // A correction that is empty or past the size cap. The same rules as an upload, checked by the
  // same property, so it collapses to the same sentence rather than a fourth one saying the same
  // thing about a different verb.
  'INVALID_CONTENT',
]);

/** The body is JSON in practice and untyped in principle; a missing code is simply not a match. */
function reasonOf(error: HttpErrorResponse): string | null {
  const body: unknown = error.error;
  if (body && typeof body === 'object' && 'reason' in body) {
    const reason = (body as { reason?: unknown }).reason;
    return typeof reason === 'string' ? reason : null;
  }
  return null;
}
