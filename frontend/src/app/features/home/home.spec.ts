import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../core/auth/auth.service';
import { Home } from './home';

describe('Home', () => {
  let logout: jasmine.Spy;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    logout = jasmine.createSpy('logout');

    await TestBed.configureTestingModule({
      imports: [Home],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            username: () => 'schichtleiter',
            realmRoles: () => ['schichtleiter'],
            logout,
          },
        },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows the username and realm roles from the token', async () => {
    const fixture = TestBed.createComponent(Home);
    httpMock.expectOne('/api/hello').flush({ username: 'schichtleiter', roles: ['ROLE_SCHICHTLEITER'] });
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="username"]')?.textContent).toContain(
      'schichtleiter',
    );
    expect(element.querySelector('[data-testid="roles"]')?.textContent).toContain('schichtleiter');
  });

  it('reports the backend identity when /api/hello answers', async () => {
    const fixture = TestBed.createComponent(Home);
    httpMock.expectOne('/api/hello').flush({ username: 'techniker', roles: ['ROLE_TECHNIKER'] });
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="backend-ok"]')?.textContent).toContain(
      'ROLE_TECHNIKER',
    );
  });

  it('stays usable when the backend is unreachable', async () => {
    const fixture = TestBed.createComponent(Home);
    httpMock
      .expectOne('/api/hello')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="backend-error"]')?.textContent).toContain(
      'not reachable',
    );
  });

  it('signs the user out on button click', async () => {
    const fixture = TestBed.createComponent(Home);
    httpMock.expectOne('/api/hello').flush({ username: 'operator', roles: [] });
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelector('button')?.click();

    expect(logout).toHaveBeenCalled();
  });
});
