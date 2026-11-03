import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Session, SessionUser } from '../core/auth/session';
import { ApiProblem } from '../core/http/api-problem';
import { provideIcons } from '../core/ui/icons';
import { Shell } from './shell';

describe('Shell', () => {
  const user = signal<SessionUser | null>(null);
  const session = {
    user,
    authenticated: () => user() !== null,
    organizer: () => user()?.roles.includes('ORGANIZER') ?? false,
    administrator: () => user()?.roles.includes('ADMIN') ?? false,
    login: vi.fn(),
    logout: vi.fn(),
  };

  beforeEach(() => {
    vi.resetAllMocks();
    user.set(null);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideIcons(), { provide: Session, useValue: session }],
    });
  });

  function render(): HTMLElement {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('links the catalogue and my registrations', () => {
    const view = render();
    const links = [...view.querySelectorAll('.top-nav a')].map((a) => [
      a.textContent?.trim(),
      a.getAttribute('href'),
    ]);
    expect(links).toEqual([
      ['Catalogue', '/'],
      ['Inscriptions', '/mes-inscriptions'],
    ]);
  });

  it('adds the organizer space for organizers only', () => {
    user.set({ subject: 'k', name: 'K', email: null, roles: ['ATTENDEE', 'ORGANIZER'] });
    const view = render();
    const links = [...view.querySelectorAll('.top-nav a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/', '/mes-inscriptions', '/organisation']);
  });

  it('adds the administration for administrators only', () => {
    user.set({ subject: 'k', name: 'K', email: null, roles: ['ATTENDEE', 'ADMIN'] });
    const view = render();
    const links = [...view.querySelectorAll('.top-nav a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/', '/mes-inscriptions', '/administration']);
  });

  it('offers to sign in, back to the current page', async () => {
    const view = render();
    await TestBed.inject(Router).navigateByUrl('/?q=paris');

    (view.querySelector('.account button') as HTMLButtonElement).click();

    expect(view.querySelector('.account button')?.textContent).toContain('Se connecter');
    expect(session.login).toHaveBeenCalledWith('/?q=paris');
  });

  /** Opens the account menu (desktop) and returns its items. */
  function openAccountMenu(view: HTMLElement): HTMLElement[] {
    (view.querySelector('.account-button') as HTMLButtonElement).click();
    return [...document.querySelectorAll<HTMLElement>('.mat-mdc-menu-item')];
  }

  it('shows the signed-in user, who reaches the account page and signs out from the menu', () => {
    user.set({ subject: 'ada', name: 'Ada Lovelace', email: 'ada@example.com', roles: [] });
    session.logout.mockResolvedValue(undefined);
    const view = render();

    expect(view.querySelector('.account-name')?.textContent).toBe('Ada Lovelace');
    expect(view.querySelector('.bottom-nav a[href="/profil"]')?.textContent?.trim()).toBe('Compte');
    const [account, signOut] = openAccountMenu(view);
    expect(account.getAttribute('href')).toBe('/profil');
    signOut.click();

    expect(session.logout).toHaveBeenCalledOnce();
  });

  it('reports a failed sign-out in French', async () => {
    user.set({ subject: 'ada', name: null, email: 'ada@example.com', roles: [] });
    session.logout.mockRejectedValue(new ApiProblem(503, 'SERVICE_UNAVAILABLE', 'Indisponible'));
    const view = render();
    expect(view.querySelector('.account-name')?.textContent).toBe('ada@example.com');

    openAccountMenu(view)[1].click();
    await new Promise((resolve) => setTimeout(resolve));

    expect(document.querySelector('.mat-mdc-snack-bar-label')?.textContent).toContain(
      'Indisponible',
    );
  });

  it('offers to sign in from the bottom bar to an anonymous visitor', () => {
    const view = render();
    expect(view.querySelector('.bottom-nav button')?.textContent?.trim()).toBe('Connexion');
  });
});
