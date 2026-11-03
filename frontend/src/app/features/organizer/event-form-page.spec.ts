import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEventsApiConfiguration } from '../../api/events/events-api-configuration';
import { EventResponse } from '../../api/events/models/event-response';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { Confirmations } from '../../shared/confirm-dialog';
import { confirmLeave } from '../../shared/leave-guard';
import { toLocalInput } from './event-form';
import { EventFormPage } from './event-form-page';

@Component({ template: '' })
class ListStub {}

const IN_A_WEEK = new Date(Date.now() + 7 * 86_400_000);
IN_A_WEEK.setSeconds(0, 0);

const PUBLISHED: EventResponse = {
  id: 'p1',
  title: 'Spring Boot Day',
  location: 'Paris, La Défense',
  startAt: IN_A_WEEK.toISOString(),
  capacity: 200,
  reservedSeats: 148,
  description: 'Sécurité et observabilité.',
  status: 'PUBLISHED',
};

describe('EventFormPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let router: Router;
  const confirmations = { ask: vi.fn() };

  beforeEach(async () => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'organisation', component: ListStub },
            {
              path: 'organisation/nouveau',
              component: EventFormPage,
              canDeactivate: [confirmLeave],
            },
            {
              path: 'organisation/:id/modifier',
              component: EventFormPage,
              canDeactivate: [confirmLeave],
            },
          ],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
        { provide: Session, useValue: { expire: vi.fn() } },
        { provide: Confirmations, useValue: confirmations },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const view = () => harness.routeNativeElement as HTMLElement;
  const text = (selector: string) => view().querySelector(selector)?.textContent?.trim();
  const field = (name: string) =>
    view().querySelector(`[formcontrolname="${name}"]`) as HTMLInputElement;
  const errorOf = (name: string) =>
    field(name).closest('mat-form-field')?.querySelector('mat-error')?.textContent?.trim();
  const submit = () => view().querySelector<HTMLButtonElement>('button[type="submit"]')!;

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  async function type(name: string, value: string): Promise<void> {
    field(name).value = value;
    field(name).dispatchEvent(new Event('input'));
    field(name).dispatchEvent(new Event('blur'));
    await elapse();
  }

  async function edit(event: EventResponse = PUBLISHED): Promise<void> {
    await harness.navigateByUrl(`/organisation/${event.id}/modifier`);
    backend.expectOne(`/api/events/${event.id}`).flush(event);
    await elapse();
  }

  it('creates a draft, then shows it among the drafts', async () => {
    await harness.navigateByUrl('/organisation/nouveau');
    expect(text('h1')).toBe('Nouvel événement');

    await type('title', '  Meetup Angular ');
    await type('location', 'Nantes');
    await type('startAt', toLocalInput(IN_A_WEEK.toISOString()));
    await type('capacity', '60');
    submit().click();
    await elapse();

    const creating = backend.expectOne('/api/events');
    expect(creating.request.method).toBe('POST');
    expect(creating.request.body).toEqual({
      title: 'Meetup Angular',
      location: 'Nantes',
      startAt: IN_A_WEEK.toISOString(),
      capacity: 60,
      description: undefined,
    });
    creating.flush(
      { ...creating.request.body, id: 'n1', status: 'DRAFT' },
      { status: 201, statusText: '' },
    );
    await elapse();

    expect(router.url).toBe('/organisation?statut=brouillons');
    expect(router.lastSuccessfulNavigation()?.extras.state).toEqual({
      notice: 'Brouillon « Meetup Angular » créé : publie-le quand il est prêt.',
    });
    expect(confirmations.ask).not.toHaveBeenCalled();
  });

  it('says what is missing and sends nothing', async () => {
    await harness.navigateByUrl('/organisation/nouveau');

    submit().click();
    await elapse();

    expect(errorOf('title')).toBe('Ce champ est obligatoire.');
    expect(errorOf('startAt')).toBe('Ce champ est obligatoire.');
    expect(errorOf('capacity')).toBe('Ce champ est obligatoire.');
  });

  it('edits an event, never below the seats already reserved', async () => {
    await edit();

    expect(text('h1')).toBe("Modifier l'événement");
    expect(text('.subtitle')).toContain('les modifications sont visibles tout de suite');
    expect(field('title').value).toBe('Spring Boot Day');
    expect(text('mat-hint')).toContain('Ville et salle');
    expect(submit().disabled).toBe(true);

    await type('capacity', '100');
    expect(errorOf('capacity')).toBe('Au moins 148 places : 148 sont déjà réservées.');

    await type('capacity', '180');
    submit().click();
    await elapse();
    const updating = backend.expectOne('/api/events/p1');
    expect(updating.request.method).toBe('PUT');
    expect(updating.request.body).toEqual({
      title: 'Spring Boot Day',
      location: 'Paris, La Défense',
      startAt: PUBLISHED.startAt,
      capacity: 180,
      description: 'Sécurité et observabilité.',
    });
    updating.flush({ ...PUBLISHED, capacity: 180 });
    await elapse();

    expect(router.url).toBe('/organisation');
    expect(router.lastSuccessfulNavigation()?.extras.state).toEqual({
      notice: '« Spring Boot Day » est enregistré.',
    });
  });

  it('shows a refusal of the server under the field it concerns', async () => {
    await edit();
    await type('capacity', '150');

    submit().click();
    await elapse();
    backend
      .expectOne('/api/events/p1')
      .flush({ code: 'CAPACITY_BELOW_RESERVED' }, { status: 409, statusText: '' });
    await elapse();

    const message = 'La capacité ne peut pas être inférieure au nombre de places déjà réservées.';
    expect(text('.banner')).toBe(message);
    expect(errorOf('capacity')).toBe(message);
    expect(router.url).toBe('/organisation/p1/modifier');

    // The refusal stays under the field until it changes.
    submit().click();
    await elapse();
    backend.expectNone('/api/events/p1');
    await type('capacity', '190');
    await type('title', 'Spring Boot Day 2026');
    submit().click();
    await elapse();
    backend.expectOne('/api/events/p1').flush(
      {
        code: 'VALIDATION_FAILED',
        errors: { title: 'size must be between 0 and 200', other: 'x' },
      },
      { status: 400, statusText: '' },
    );
    await elapse();
    expect(text('.banner')).toBe('Certains champs sont invalides.');
    expect(errorOf('title')).toBe('Valeur refusée.');
  });

  it('cannot edit a cancelled event', async () => {
    await edit({ ...PUBLISHED, status: 'CANCELLED' });

    expect(text('.state p')).toBe('Cet événement est annulé : il ne peut plus être modifié.');
    expect(view().querySelector('form')).toBeNull();
  });

  it('explains an event that cannot be loaded, and tries again', async () => {
    await harness.navigateByUrl('/organisation/x/modifier');
    backend
      .expectOne('/api/events/x')
      .flush({ code: 'EVENT_NOT_FOUND' }, { status: 404, statusText: '' });
    await elapse();
    expect(text('[role="alert"] p')).toBe('Cet événement est introuvable.');

    view().querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    await elapse();
    backend.expectOne('/api/events/x').flush(PUBLISHED);
    await elapse();
    expect(field('title').value).toBe('Spring Boot Day');
  });

  it('asks before leaving with changes not saved', async () => {
    await edit();
    expect(await router.navigateByUrl('/organisation')).toBe(true);
    expect(confirmations.ask).not.toHaveBeenCalled();

    await edit();
    await type('title', 'Autre titre');
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);

    confirmations.ask.mockResolvedValueOnce(false);
    expect(await router.navigateByUrl('/organisation')).toBe(false);
    expect(confirmations.ask).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Quitter sans enregistrer ?' }),
    );

    confirmations.ask.mockResolvedValueOnce(true);
    expect(await router.navigateByUrl('/organisation')).toBe(true);
  });
});
