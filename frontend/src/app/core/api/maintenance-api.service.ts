import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ConfigService } from '../config/config.service';
import {
  Machine,
  ProtocolPage,
  QueryAnswer,
  UploadAccepted,
  UploadStatus,
} from './api.types';

/**
 * The five calls this application makes to its own backend.
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
  ask(question: string, machineId: string): Observable<QueryAnswer> {
    return this.http.post<QueryAnswer>(`${this.apiBaseUrl}/query`, { question, machineId });
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
  moderationProtocols(page: number, size: number): Observable<ProtocolPage> {
    return this.http.get<ProtocolPage>(`${this.apiBaseUrl}/moderation/protocols`, {
      params: { page, size },
    });
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

  /** Removes a protocol permanently: chunks, row and file. Admin only, server-side. */
  deleteProtocol(protocolId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiBaseUrl}/moderation/protocols/${protocolId}`);
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
      return REJECTION_CODES.has(reasonOf(error) ?? '') ? 'rejectedContent' : 'generic';
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
const REJECTION_CODES = new Set(['EMPTY_FILE', 'UNSUPPORTED_TYPE', 'NOT_TEXT']);

/** The body is JSON in practice and untyped in principle; a missing code is simply not a match. */
function reasonOf(error: HttpErrorResponse): string | null {
  const body: unknown = error.error;
  if (body && typeof body === 'object' && 'reason' in body) {
    const reason = (body as { reason?: unknown }).reason;
    return typeof reason === 'string' ? reason : null;
  }
  return null;
}
