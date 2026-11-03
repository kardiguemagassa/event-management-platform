import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import localeFr from '@angular/common/locales/fr';
import { Component, LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { UserResponse } from '../../api/users/models/user-response';
import { provideUsersApiConfiguration } from '../../api/users/users-api-configuration';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { ProfilePage } from './profile-page';

registerLocaleData(localeFr);

@Component({ template: '' })
class Elsewhere {}

const ADA: UserResponse = {
  id: 'u1',
  fullName: 'Ada Lovelace',
  email: 'ada@example.com',
  createdAt: '2026-10-26T10:00:00Z',
};

describe('ProfilePage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  const session = { logout: vi.fn(), expire: vi.fn() };

  beforeEach(async () => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'profil', component: ProfilePage },
          { path: '**', component: Elsewhere },
        ]),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideUsersApiConfiguration(''),
        { provide: LOCALE_ID, useValue: 'fr-FR' },
        { provide: Session, useValue: session },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const view = () => harness.routeNativeElement as HTMLElement;
  const text = (selector: string) => view().querySelector(selector)?.textContent?.trim();
  const button = (label: string) =>
    [...view().querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === label,
    ) as HTMLButtonElement;
  const input = () => view().querySelector('input') as HTMLInputElement;

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  async function open(): Promise<void> {
    await harness.navigateByUrl('/profil');
    backend.expectOne('/api/users/me').flush(ADA);
    await elapse();
  }

  it('shows the profile, the e-mail and the date of sign-up', async () => {
    await open();

    expect(input().value).toBe('Ada Lovelace');
    expect([...view().querySelectorAll('dd')].map((dd) => dd.textContent?.trim())).toEqual([
      'ada@example.com',
      'octobre 2026',
    ]);
    expect(button('Enregistrer').disabled).toBe(true);
  });

  it('saves a new name', async () => {
    await open();
    input().value = 'Ada King';
    input().dispatchEvent(new Event('input'));
    harness.detectChanges();

    button('Enregistrer').click();
    const save = backend.expectOne('/api/users/me');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ fullName: 'Ada King' });
    save.flush({ ...ADA, fullName: 'Ada King' });
    await elapse();

    expect(text('[role="status"]')).toBe('Profil enregistré.');
    expect(input().value).toBe('Ada King');
  });

  it('deletes the profile after confirmation, then signs out', async () => {
    session.logout.mockResolvedValue(undefined);
    await open();

    button('Supprimer mon profil').click();
    harness.detectChanges();
    button('Annuler').click();
    harness.detectChanges();
    button('Supprimer mon profil').click();
    harness.detectChanges();
    button('Oui, supprimer').click();

    const erase = backend.expectOne('/api/users/me');
    expect(erase.request.method).toBe('DELETE');
    erase.flush(null, { status: 204, statusText: '' });
    await elapse();

    expect(session.logout).toHaveBeenCalledOnce();
  });

  it('signs out', async () => {
    session.logout.mockResolvedValue(undefined);
    await open();

    button('Se déconnecter').click();
    await elapse();

    expect(session.logout).toHaveBeenCalledOnce();
  });

  it('reports a failed sign-out', async () => {
    session.logout.mockRejectedValue(new Error('network'));
    await open();

    button('Se déconnecter').click();
    await elapse();

    expect(text('[role="alert"]')).toBe(
      "Une erreur inattendue s'est produite. Réessaie plus tard.",
    );
  });

  it('sends a user without profile to the onboarding page', async () => {
    await harness.navigateByUrl('/profil');
    backend
      .expectOne('/api/users/me')
      .flush({ code: 'USER_NOT_FOUND' }, { status: 404, statusText: '' });
    await elapse();
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/bienvenue?retour=%2Fprofil');
  });

  it('offers to retry a failure', async () => {
    await harness.navigateByUrl('/profil');
    backend.expectOne('/api/users/me').flush(null, { status: 503, statusText: '' });
    await elapse();

    button('Réessayer').click();
    await elapse();
    backend.expectOne('/api/users/me').flush(ADA);
    await elapse();
    expect(input().value).toBe('Ada Lovelace');
  });
});
