import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ProfileCheck } from './profile-check';
import { BrowserLocation, Session } from './session';
import { startSession } from './startup';

describe('startSession', () => {
  const authenticated = signal(false);
  const session = { load: vi.fn(), authenticated, takeReturnUrl: vi.fn() };
  const profiles = { missing: vi.fn(), onboardingUrl: (url: string) => `/bienvenue?retour=${url}` };
  const location = { current: vi.fn(), replace: vi.fn() };

  beforeEach(() => {
    vi.resetAllMocks();
    authenticated.set(false);
    session.load.mockResolvedValue(undefined);
    location.current.mockReturnValue('/');
    TestBed.configureTestingModule({
      providers: [
        { provide: Session, useValue: session },
        { provide: ProfileCheck, useValue: profiles },
        { provide: BrowserLocation, useValue: location },
      ],
    });
  });

  const start = () => TestBed.runInInjectionContext(startSession);

  it('leaves an anonymous visitor where they are, without asking for a profile', async () => {
    await start();

    expect(session.load).toHaveBeenCalledOnce();
    expect(profiles.missing).not.toHaveBeenCalled();
    expect(location.replace).not.toHaveBeenCalled();
  });

  it('brings a signed-in user back to the page asked before the login', async () => {
    authenticated.set(true);
    session.takeReturnUrl.mockReturnValue('/evenements/e1');
    profiles.missing.mockResolvedValue(false);

    await start();

    expect(location.replace).toHaveBeenCalledWith('/evenements/e1');
  });

  it('welcomes a user without a profile first', async () => {
    authenticated.set(true);
    session.takeReturnUrl.mockReturnValue(null);
    location.current.mockReturnValue('/mes-inscriptions');
    profiles.missing.mockResolvedValue(true);

    await start();

    expect(location.replace).toHaveBeenCalledWith('/bienvenue?retour=/mes-inscriptions');
  });

  it('changes nothing when the user is already in the right place', async () => {
    authenticated.set(true);
    session.takeReturnUrl.mockReturnValue(null);
    profiles.missing.mockResolvedValue(false);

    await start();

    expect(location.replace).not.toHaveBeenCalled();
  });
});
