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

  it('offers to sign in, back to the current page', async () => {
    const view = render();
    await TestBed.inject(Router).navigateByUrl('/?q=paris');

    (view.querySelector('.account button') as HTMLButtonElement).click();

    expect(view.querySelector('.account button')?.textContent).toContain('Se connecter');
    expect(session.login).toHaveBeenCalledWith('/?q=paris');
  });

  it('shows the signed-in user, who can sign out from the bottom bar', () => {
    user.set({ subject: 'ada', name: 'Ada Lovelace', email: 'ada@example.com', roles: [] });
    session.logout.mockResolvedValue(undefined);
    const view = render();

    expect(view.querySelector('.account-name')?.textContent).toBe('Ada Lovelace');
    (view.querySelector('.bottom-nav button') as HTMLButtonElement).click();

    expect(session.logout).toHaveBeenCalledOnce();
  });

  it('reports a failed sign-out in French', async () => {
    user.set({ subject: 'ada', name: null, email: 'ada@example.com', roles: [] });
    session.logout.mockRejectedValue(new ApiProblem(503, 'SERVICE_UNAVAILABLE', 'Indisponible'));
    const view = render();
    expect(view.querySelector('.account-name')?.textContent).toBe('ada@example.com');

    (view.querySelector('.bottom-nav button') as HTMLButtonElement).click();
    await new Promise((resolve) => setTimeout(resolve));

    expect(document.querySelector('.mat-mdc-snack-bar-label')?.textContent).toContain(
      'Indisponible',
    );
  });
});
