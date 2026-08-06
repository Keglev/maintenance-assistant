import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigService } from './config.service';

describe('ConfigService', () => {
  let service: ConfigService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ConfigService] });
    service = TestBed.inject(ConfigService);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('serves the compiled-in defaults before anything is loaded', () => {
    expect(service.config.keycloakClientId).toBe('frontend');
    expect(service.config.apiBaseUrl).toBe('/api');
  });

  it('merges the fetched file over the defaults', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ keycloakIssuer: 'https://auth.example.test/realms/x' }),
      }),
    );

    await service.load();

    expect(service.config.keycloakIssuer).toBe('https://auth.example.test/realms/x');
    // Untouched keys keep the fallback rather than becoming undefined.
    expect(service.config.keycloakClientId).toBe('frontend');
  });

  it('keeps the defaults when the file is missing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    const before = service.config;

    await service.load();

    expect(service.config).toEqual(before);
  });

  it('keeps the defaults when the fetch throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    const before = service.config;

    await expect(service.load()).resolves.toBeUndefined();
    expect(service.config).toEqual(before);
  });
});
