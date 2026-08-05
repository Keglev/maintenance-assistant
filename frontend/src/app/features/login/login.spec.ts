import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { Login } from './login';

describe('Login', () => {
  let login: ReturnType<typeof vi.fn>;
  let authenticated: boolean;

  async function setup() {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { isAuthenticated: () => authenticated, login },
        },
      ],
    }).compileComponents();
  }

  beforeEach(() => {
    login = vi.fn();
    authenticated = false;
  });

  it('starts the Keycloak redirect when the button is clicked', async () => {
    await setup();
    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelector('button')?.click();

    expect(login).toHaveBeenCalled();
  });

  it('sends an already authenticated user to /home', async () => {
    authenticated = true;
    await setup();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const fixture = TestBed.createComponent(Login);
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/home']);
  });
});
