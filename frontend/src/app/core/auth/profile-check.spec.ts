import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideUsersApiConfiguration } from '../../api/users/users-api-configuration';
import { problemInterceptor } from '../http/problem.interceptor';
import { ProfileCheck, safeReturnPath } from './profile-check';
import { Session } from './session';

describe('ProfileCheck', () => {
  let check: ProfileCheck;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideUsersApiConfiguration(''),
        { provide: Session, useValue: { expire: vi.fn() } },
      ],
    });
    check = TestBed.inject(ProfileCheck);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('finds an existing profile', async () => {
    const missing = check.missing();
    backend.expectOne('/api/users/me').flush({ id: 'u1', fullName: 'Ada' });
    expect(await missing).toBe(false);
  });

  it('knows a profile is missing only when user-service says so', async () => {
    const missing = check.missing();
    backend
      .expectOne('/api/users/me')
      .flush({ code: 'USER_NOT_FOUND' }, { status: 404, statusText: '' });
    expect(await missing).toBe(true);

    const unavailable = check.missing();
    backend.expectOne('/api/users/me').flush(null, { status: 503, statusText: '' });
    expect(await unavailable).toBe(false);
  });

  it('builds the onboarding address with the page to come back to', () => {
    expect(check.onboardingUrl('/evenements/e1?x=1')).toBe(
      '/bienvenue?retour=%2Fevenements%2Fe1%3Fx%3D1',
    );
    expect(check.onboardingUrl('/')).toBe('/bienvenue');
    expect(check.onboardingUrl('/bienvenue?retour=%2Fprofil')).toBe('/bienvenue');
  });
});

describe('safeReturnPath', () => {
  it('keeps in-app paths only', () => {
    expect(safeReturnPath('/mes-inscriptions')).toBe('/mes-inscriptions');
    expect(safeReturnPath('//evil.example')).toBe('/');
    expect(safeReturnPath('https://evil.example')).toBe('/');
    expect(safeReturnPath(undefined)).toBe('/');
  });
});
