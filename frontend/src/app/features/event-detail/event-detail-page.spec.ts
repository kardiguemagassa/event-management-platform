import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEventsApiConfiguration } from '../../api/events/events-api-configuration';
import { EventResponse } from '../../api/events/models/event-response';
import { RegistrationResponse } from '../../api/registrations/models/registration-response';
import { provideRegistrationsApiConfiguration } from '../../api/registrations/registrations-api-configuration';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { provideIcons } from '../../core/ui/icons';
import { EventDetailPage } from './event-detail-page';

registerLocaleData(localeFr);

const EVENT: EventResponse = {
  id: 'e1',
  title: 'Spring Boot Day',
  description: 'Une journée consacrée à Spring Boot.',
  location: 'Paris, La Défense',
  startAt: new Date(2099, 11, 3, 9, 0).toISOString(),
  capacity: 200,
  reservedSeats: 148,
  status: 'PUBLISHED',
};
const REGISTRATION: RegistrationResponse = { id: 'r1', eventId: 'e1', status: 'CONFIRMED' };

describe('EventDetailPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  const authenticated = signal(false);
  const session = { authenticated, login: vi.fn(), expire: vi.fn() };

  beforeEach(async () => {
    vi.resetAllMocks();
    authenticated.set(false);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'evenements/:id', component: EventDetailPage }],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
        provideRegistrationsApiConfiguration(''),
        provideIcons(),
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

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function expectEvent(): TestRequest {
    return backend.expectOne('/api/events/e1');
  }

  function expectMine(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/registrations/me');
  }

  async function open(
    event: EventResponse,
    registration?: RegistrationResponse | null,
  ): Promise<void> {
    await harness.navigateByUrl('/evenements/e1');
    expectEvent().flush(event);
    if (registration !== undefined) {
      expectMine().flush({ content: registration ? [registration] : [] });
    }
    await elapse();
  }

  it('shows the event to an anonymous visitor, who signs in to register', async () => {
    await open(EVENT);

    expect(text('h1')).toBe('Spring Boot Day');
    expect(text('time')).toBe('jeudi 3 décembre 2099 · 09:00');
    expect(text('.description')).toBe('Une journée consacrée à Spring Boot.');
    expect(text('.seats')).toBe('52 places restantes sur 200');

    button("Se connecter pour s'inscrire").click();
    expect(session.login).toHaveBeenCalledWith('/evenements/e1');
  });

  it('asks whether the signed-in visitor is registered, then registers them', async () => {
    authenticated.set(true);
    await harness.navigateByUrl('/evenements/e1');
    expectEvent().flush(EVENT);
    const mine = expectMine();
    expect(mine.request.params.get('eventId')).toBe('e1');
    expect(mine.request.params.get('status')).toBe('CONFIRMED');
    mine.flush({ content: [] });
    await elapse();

    button("S'inscrire").click();
    const registering = backend.expectOne('/api/registrations');
    expect(registering.request.method).toBe('POST');
    expect(registering.request.body).toEqual({ eventId: 'e1' });
    registering.flush(REGISTRATION);
    await elapse();
    expectEvent().flush({ ...EVENT, reservedSeats: 149 });
    await elapse();

    expect(text('.feedback [role="status"]')).toBe('Inscription confirmée. À bientôt !');
    expect(text('.actions .banner')).toBe('Tu es inscrit à cet événement.');
    expect(text('.seats')).toBe('51 places restantes sur 200');
  });

  it('cancels a registration after confirmation', async () => {
    authenticated.set(true);
    await open(EVENT, REGISTRATION);

    button('Annuler mon inscription').click();
    harness.detectChanges();
    button('Non, garder ma place').click();
    harness.detectChanges();
    button('Annuler mon inscription').click();
    harness.detectChanges();
    button('Oui, annuler').click();

    const cancelling = backend.expectOne('/api/registrations/r1/cancel');
    expect(cancelling.request.method).toBe('POST');
    cancelling.flush({ ...REGISTRATION, status: 'CANCELLED' });
    await elapse();
    expectEvent().flush(EVENT);
    await elapse();

    expect(text('.feedback [role="status"]')).toBe('Inscription annulée. Ta place est libérée.');
    expect(button("S'inscrire")).toBeDefined();
  });

  it('explains a refused registration and refreshes the seats', async () => {
    authenticated.set(true);
    await open({ ...EVENT, reservedSeats: 199 }, null);

    button("S'inscrire").click();
    backend
      .expectOne('/api/registrations')
      .flush({ code: 'EVENT_FULL' }, { status: 409, statusText: '' });
    await elapse();
    expectEvent().flush({ ...EVENT, reservedSeats: 200 });
    await elapse();

    expect(text('.feedback [role="alert"]')).toBe("L'événement est complet.");
    expect(button('Complet').disabled).toBe(true);
  });

  it('finds the existing registration when already registered elsewhere', async () => {
    authenticated.set(true);
    await open(EVENT, null);

    button("S'inscrire").click();
    backend
      .expectOne('/api/registrations')
      .flush({ code: 'ALREADY_REGISTERED' }, { status: 409, statusText: '' });
    await elapse();
    expectMine().flush({ content: [REGISTRATION] });
    expectEvent().flush(EVENT);
    await elapse();

    expect(text('.feedback [role="alert"]')).toBe('Tu es déjà inscrit à cet événement.');
    expect(button('Annuler mon inscription')).toBeDefined();
  });

  it.each([
    [{ status: 'CANCELLED' as const }, 'Cet événement est annulé.'],
    [{ status: 'DRAFT' as const }, "Brouillon : visible par toi seul tant qu'il n'est pas publié."],
    [{ startAt: '2020-01-01T09:00:00Z' }, 'Cet événement est terminé.'],
  ])('offers no registration when %o', async (change, banner) => {
    await open({ ...EVENT, ...change });

    expect(text('.content .banner')).toBe(banner);
    expect(view().querySelector('.actions button')).toBeNull();
  });

  it('says when the event does not exist', async () => {
    await harness.navigateByUrl('/evenements/e1');
    expectEvent().flush({ code: 'EVENT_NOT_FOUND' }, { status: 404, statusText: '' });
    await elapse();

    expect(text('h1')).toBe('Événement introuvable');
  });

  it('offers to retry any other failure', async () => {
    await harness.navigateByUrl('/evenements/e1');
    expectEvent().flush(null, { status: 503, statusText: '' });
    await elapse();

    expect(text('[role="alert"] p')).toBe(
      'Le service est momentanément indisponible. Réessaie dans un instant.',
    );
    button('Réessayer').click();
    await elapse();
    expectEvent().flush(EVENT);
    await elapse();
    expect(text('h1')).toBe('Spring Boot Day');
  });
});
