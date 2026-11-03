import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideUsersApiConfiguration } from '../../api/users/users-api-configuration';
import { Session, SessionUser } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { OnboardingPage } from './onboarding-page';

@Component({ template: '' })
class Elsewhere {}

describe('OnboardingPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  const user = signal<SessionUser | null>({
    subject: 'k',
    name: 'Kardigué Magassa',
    email: 'k@example.com',
    roles: [],
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'bienvenue', component: OnboardingPage },
            { path: '**', component: Elsewhere },
          ],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideUsersApiConfiguration(''),
        { provide: Session, useValue: { user, expire: vi.fn() } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const view = () => harness.routeNativeElement as HTMLElement;
  const input = () => view().querySelector('input') as HTMLInputElement;
  const submit = () => (view().querySelector('button[type="submit"]') as HTMLButtonElement).click();

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  it('pre-fills the name, saves it and goes back to the page asked', async () => {
    await harness.navigateByUrl('/bienvenue?retour=%2Fevenements%2Fe1');
    expect(input().value).toBe('Kardigué Magassa');

    input().value = '  Kardigué M.  ';
    input().dispatchEvent(new Event('input'));
    submit();
    const save = backend.expectOne('/api/users/me');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ fullName: 'Kardigué M.' });
    save.flush({ id: 'k', fullName: 'Kardigué M.' }, { status: 201, statusText: '' });
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/evenements/e1');
  });

  it('never goes back to another site', async () => {
    await harness.navigateByUrl('/bienvenue?retour=%2F%2Fevil.example');
    submit();
    backend.expectOne('/api/users/me').flush({ id: 'k' });
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/');
  });

  it('asks for a name before saving', async () => {
    await harness.navigateByUrl('/bienvenue');
    input().value = '   ';
    input().dispatchEvent(new Event('input'));
    submit();
    harness.detectChanges();

    expect(view().querySelector('mat-error')?.textContent?.trim()).toBe('Indique ton nom.');
  });

  it('explains a refused save', async () => {
    await harness.navigateByUrl('/bienvenue');
    submit();
    backend
      .expectOne('/api/users/me')
      .flush({ code: 'EMAIL_ALREADY_USED' }, { status: 409, statusText: '' });
    await elapse();

    expect(view().querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'Cette adresse e-mail est déjà utilisée par un autre compte.',
    );
  });
});
