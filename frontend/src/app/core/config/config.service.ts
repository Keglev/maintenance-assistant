import { Injectable } from '@angular/core';

import { RuntimeConfig } from './runtime-config';
import { environment } from '../../../environments/environment';

/**
 * Loads {@link RuntimeConfig} from `/config.json` once, during startup.
 *
 * A missing or malformed file is not an error: the values compiled into
 * `environment.ts` are used instead. That keeps the image runnable on its own —
 * a deployment that forgets the bind mount serves the built-in defaults rather
 * than a blank page.
 */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly fallback: RuntimeConfig = {
    keycloakIssuer: environment.keycloakIssuer,
    keycloakClientId: environment.keycloakClientId,
    apiBaseUrl: environment.apiBaseUrl,
  };

  private current: RuntimeConfig = this.fallback;

  /** The active configuration. Valid before {@link load}, using the fallback. */
  get config(): RuntimeConfig {
    return this.current;
  }

  /**
   * Fetched with `fetch` rather than HttpClient on purpose: this runs before the
   * first route renders, and the interceptor chain HttpClient carries is set up
   * to attach an access token that does not exist yet.
   */
  async load(): Promise<void> {
    try {
      const response = await fetch('config.json', { cache: 'no-store' });
      if (!response.ok) {
        return;
      }
      const loaded = (await response.json()) as Partial<RuntimeConfig>;
      // Merged, not replaced: a deployment may override one key and inherit the rest.
      this.current = { ...this.fallback, ...loaded };
    } catch {
      // Offline, absent file, or invalid JSON — the fallback already stands.
    }
  }
}
