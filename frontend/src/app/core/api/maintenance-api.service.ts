import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ConfigService } from '../config/config.service';
import { Machine, QueryAnswer, UploadAccepted, UploadStatus } from './api.types';

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

  /** The absolute URL of a protocol's original document, for a link the browser follows itself. */
  documentUrl(protocolId: string): string {
    return `${this.apiBaseUrl}/protocols/${protocolId}/document`;
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
export type ApiFailure = 'rateLimited' | 'budgetExhausted' | 'unavailable' | 'forbidden' | 'generic';

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
    case 0:
    case 502:
    case 504:
      return 'unavailable';
    default:
      return 'generic';
  }
}

/** The body is JSON in practice and untyped in principle; a missing code is simply not a match. */
function reasonOf(error: HttpErrorResponse): string | null {
  const body: unknown = error.error;
  if (body && typeof body === 'object' && 'reason' in body) {
    const reason = (body as { reason?: unknown }).reason;
    return typeof reason === 'string' ? reason : null;
  }
  return null;
}
