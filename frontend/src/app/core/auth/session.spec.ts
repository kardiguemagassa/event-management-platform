import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BrowserLocation, Session, SessionUser } from './session';

const ADA: SessionUser = {
  subject: 'ada',
  name: 'Ada Lovelace',
  email: 'ada@example.com',
  roles: ['ATTENDEE', 'ORGANIZER'],
};

describe('Session', () => {
  let session: Session;
  let backend: HttpTestingController;
  const stored = new Map<string, string>();
  const location = {
    assign: vi.fn(),
    store: (key: string, value: string) => stored.set(key, value),
    take: (key: string) => {
      const value = stored.get(key) ?? null;
      stored.delete(key);
      return value;
    },
  };

  beforeEach(() => {
    stored.clear();
    location.assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BrowserLocation, useValue: location },
      ],
    });
    session = TestBed.inject(Session);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  async function loadWith(body: SessionUser | null, status = 200): Promise<void> {
    const loading = session.load();
    backend.expectOne('/bff/me').flush(body, { status, statusText: '' });
    await loading;
  }

  it('knows the signed-in user and their roles', async () => {
    await loadWith(ADA);

    expect(session.user()).toEqual(ADA);
    expect(session.authenticated()).toBe(true);
    expect(session.organizer()).toBe(true);
    expect(session.hasRole('ADMIN')).toBe(false);
  });

  it('is anonymous on 401, and on any other failure of the probe', async () => {
    await loadWith(null, 401);
    expect(session.authenticated()).toBe(false);

    await loadWith(null, 503);
    expect(session.user()).toBeNull();
    expect(session.organizer()).toBe(false);
  });

  it('signs in through the gateway and remembers the page asked', () => {
    session.login('/mes-inscriptions');

    expect(location.assign).toHaveBeenCalledWith('/oauth2/authorization/keycloak');
    expect(session.takeReturnUrl()).toBe('/mes-inscriptions');
    expect(session.takeReturnUrl()).toBeNull();
  });

  it('never returns to another site after signing in', () => {
    session.login('//evil.example');
    expect(session.takeReturnUrl()).toBeNull();
    session.login('https://evil.example');
    expect(session.takeReturnUrl()).toBeNull();
  });

  it('signs out of the gateway, then of Keycloak', async () => {
    await loadWith(ADA);

    const loggingOut = session.logout();
    const request = backend.expectOne('/logout');
    expect(request.request.method).toBe('POST');
    request.flush({
      logoutUrl: 'http://localhost:8180/realms/event-platform/protocol/openid-connect/logout?x=1',
    });
    await loggingOut;

    expect(session.authenticated()).toBe(false);
    expect(location.assign).toHaveBeenCalledWith(
      'http://localhost:8180/realms/event-platform/protocol/openid-connect/logout?x=1',
    );
  });

  it('becomes anonymous when the server session expires', async () => {
    await loadWith(ADA);
    session.expire();
    expect(session.user()).toBeNull();
  });
});

describe('BrowserLocation', () => {
  it('reads and replaces the address without loading it', () => {
    const location = new BrowserLocation();
    const before = location.current();
    location.replace('/mes-inscriptions?periode=passees');
    expect(location.current()).toBe('/mes-inscriptions?periode=passees');
    location.replace(before);
  });

  it('stores a value for one read', () => {
    const location = new BrowserLocation();
    location.store('key', 'value');
    expect(location.take('key')).toBe('value');
    expect(location.take('key')).toBeNull();
  });
});
