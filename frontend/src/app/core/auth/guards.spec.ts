import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { authenticated, hasRole } from './guards';
import { Session } from './session';

describe('guards', () => {
  const session = { authenticated: vi.fn(), hasRole: vi.fn(), login: vi.fn() };
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/organisation' } as RouterStateSnapshot;

  beforeEach(() => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: Session, useValue: session }],
    });
  });

  const run = (guard: typeof authenticated) =>
    TestBed.runInInjectionContext(() => guard(route, state));

  it('lets a signed-in user in', () => {
    session.authenticated.mockReturnValue(true);
    expect(run(authenticated)).toBe(true);
  });

  it('sends an anonymous visitor to the login, back to the page asked', () => {
    session.authenticated.mockReturnValue(false);
    expect(run(authenticated)).toBe(false);
    expect(session.login).toHaveBeenCalledWith('/organisation');
    expect(run(hasRole('ORGANIZER'))).toBe(false);
  });

  it('lets a user with the role in, sends the others to the catalogue', () => {
    session.authenticated.mockReturnValue(true);
    session.hasRole.mockReturnValue(true);
    expect(run(hasRole('ORGANIZER'))).toBe(true);

    session.hasRole.mockReturnValue(false);
    const result = run(hasRole('ORGANIZER'));
    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/');
    expect(session.login).not.toHaveBeenCalled();
  });
});
