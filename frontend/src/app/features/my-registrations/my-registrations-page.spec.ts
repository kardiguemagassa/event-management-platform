import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEventsApiConfiguration } from '../../api/events/events-api-configuration';
import { EventResponse } from '../../api/events/models/event-response';
import { provideRegistrationsApiConfiguration } from '../../api/registrations/registrations-api-configuration';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { MyRegistrationsPage } from './my-registrations-page';

registerLocaleData(localeFr);

const NEXT = new Date();
NEXT.setDate(NEXT.getDate() + 30);
NEXT.setHours(9, 0, 0, 0);

const UPCOMING: EventResponse = {
  id: 'e1',
  title: 'Spring Boot Day',
  location: 'Paris',
  startAt: NEXT.toISOString(),
  status: 'PUBLISHED',
};
const CANCELLED: EventResponse = {
  ...UPCOMING,
  id: 'e2',
  title: 'Meetup Angular',
  status: 'CANCELLED',
};
const PAST: EventResponse = {
  ...UPCOMING,
  id: 'e3',
  title: 'Kafka en pratique',
  startAt: '2020-03-10T18:00:00Z',
};
const REGISTRATIONS = [
  { id: 'r1', eventId: 'e1', status: 'CONFIRMED' },
  { id: 'r2', eventId: 'e2', status: 'CONFIRMED' },
  { id: 'r3', eventId: 'e3', status: 'CONFIRMED' },
];

describe('MyRegistrationsPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'mes-inscriptions', component: MyRegistrationsPage }],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
        provideRegistrationsApiConfiguration(''),
        { provide: LOCALE_ID, useValue: 'fr-FR' },
        { provide: Session, useValue: { expire: vi.fn() } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const view = () => harness.routeNativeElement as HTMLElement;
  const titles = () =>
    [...view().querySelectorAll('.title')].map((title) => title.textContent?.trim());
  const toggles = () =>
    [...view().querySelectorAll('mat-button-toggle')].map((t) =>
      t.textContent?.replace(/\s+/g, ' ').trim(),
    );

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function expectMine(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/registrations/me');
  }

  function expectEvents(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/events');
  }

  async function open(url = '/mes-inscriptions'): Promise<void> {
    await harness.navigateByUrl(url);
    expectMine().flush({ content: REGISTRATIONS });
    expectEvents().flush({ content: [UPCOMING, CANCELLED, PAST] });
    await elapse();
  }

  it('reads the active registrations, then their events in one call', async () => {
    await harness.navigateByUrl('/mes-inscriptions');
    const mine = expectMine();
    expect(mine.request.params.has('status')).toBe(false);
    expect(mine.request.params.get('size')).toBe('100');
    mine.flush({
      content: [
        ...REGISTRATIONS,
        { id: 'r4', eventId: 'e4', status: 'CANCELLED' },
        { id: 'r5', eventId: 'e5', status: 'REJECTED', rejectionReason: 'EVENT_FULL' },
      ],
    });
    await elapse();

    const events = expectEvents();
    expect(events.request.params.getAll('ids')).toEqual(['e1', 'e2', 'e3']);
    expect(events.request.params.get('size')).toBe('100');
    events.flush({ content: [UPCOMING, CANCELLED, PAST] });
  });

  it('shows a registration waiting for its seat', async () => {
    await harness.navigateByUrl('/mes-inscriptions');
    expectMine().flush({ content: [{ ...REGISTRATIONS[0], status: 'PENDING' }] });
    await elapse();
    expectEvents().flush({ content: [UPCOMING] });
    await elapse();

    const badges = [...view().querySelectorAll('.badge')].map((badge) => badge.textContent?.trim());
    expect(badges).toEqual(['En attente']);
  });

  it('shows the upcoming ones by default, with the count of each period', async () => {
    await open();

    expect(toggles()).toEqual(['À venir (2)', 'Passées (1)']);
    expect(titles()).toEqual(['Spring Boot Day', 'Meetup Angular']);
    const badges = [...view().querySelectorAll('.badge')].map((badge) => badge.textContent?.trim());
    expect(badges).toEqual(['Confirmée', 'Événement annulé']);
    expect(view().querySelector('a.item')?.getAttribute('href')).toBe('/evenements/e1');
  });

  it('switches to the past ones through the address', async () => {
    await open();

    (view().querySelectorAll('mat-button-toggle button')[1] as HTMLButtonElement).click();
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/mes-inscriptions?periode=passees');
    expect(titles()).toEqual(['Kafka en pratique']);
    expect(view().querySelector('.badge')?.textContent?.trim()).toBe('Terminé');
  });

  it('opens directly on the past ones', async () => {
    await open('/mes-inscriptions?periode=passees');
    expect(titles()).toEqual(['Kafka en pratique']);
  });

  it('invites to the catalogue when there is nothing, without asking for events', async () => {
    await harness.navigateByUrl('/mes-inscriptions');
    expectMine().flush({ content: [] });
    await elapse();

    expect(view().querySelector('.state p')?.textContent?.trim()).toBe(
      "Tu n'es inscrit à aucun événement à venir.",
    );
    expect(view().querySelector('.state a')?.getAttribute('href')).toBe('/');
    expect(toggles()).toEqual(['À venir (0)', 'Passées (0)']);
  });

  it('says when there is no past registration', async () => {
    await harness.navigateByUrl('/mes-inscriptions?periode=passees');
    expectMine().flush({ content: [REGISTRATIONS[0]] });
    await elapse();
    expectEvents().flush({ content: [UPCOMING] });
    await elapse();

    expect(view().querySelector('.state p')?.textContent?.trim()).toBe(
      'Aucune inscription passée pour le moment.',
    );
  });

  it('explains a failure and retries', async () => {
    await harness.navigateByUrl('/mes-inscriptions');
    expectMine().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    await elapse();

    expect(view().querySelector('[role="alert"] p')?.textContent).toBe(
      'Le service est momentanément indisponible. Réessaie dans un instant.',
    );
    (view().querySelector('[role="alert"] button') as HTMLButtonElement).click();
    await elapse();
    expectMine().flush({ content: [] });
    await elapse();
    expect(view().querySelector('[role="alert"]')).toBeNull();
  });
});
