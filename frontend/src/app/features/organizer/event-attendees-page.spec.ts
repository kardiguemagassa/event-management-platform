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
import { MatPaginatorIntl } from '@angular/material/paginator';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEventsApiConfiguration } from '../../api/events/events-api-configuration';
import { EventResponse } from '../../api/events/models/event-response';
import { EventRegistrationResponse } from '../../api/registrations/models/event-registration-response';
import { provideRegistrationsApiConfiguration } from '../../api/registrations/registrations-api-configuration';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { FrenchPaginatorIntl } from '../../core/ui/french-paginator-intl';
import { EventAttendeesPage } from './event-attendees-page';

registerLocaleData(localeFr);

const EVENT: EventResponse = {
  id: 'p1',
  title: 'Spring Boot Day',
  startAt: new Date(2099, 0, 21, 9, 0).toISOString(),
  capacity: 200,
  reservedSeats: 148,
  status: 'PUBLISHED',
};
const AWA: EventRegistrationResponse = {
  id: 'r1',
  attendeeName: 'Awa Diallo',
  createdAt: new Date(2098, 11, 3, 10, 30).toISOString(),
  status: 'CONFIRMED',
};
const UNKNOWN: EventRegistrationResponse = { ...AWA, id: 'r2', attendeeName: undefined };
const THOMAS: EventRegistrationResponse = {
  ...AWA,
  id: 'r3',
  attendeeName: 'Thomas Leroy',
  status: 'CANCELLED',
  cancelledAt: new Date(2098, 11, 5, 18, 0).toISOString(),
};

describe('EventAttendeesPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'organisation/:id/inscrits', component: EventAttendeesPage }],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
        provideRegistrationsApiConfiguration(''),
        { provide: LOCALE_ID, useValue: 'fr-FR' },
        { provide: MatPaginatorIntl, useClass: FrenchPaginatorIntl },
        { provide: Session, useValue: { expire: vi.fn() } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const view = () => harness.routeNativeElement as HTMLElement;
  const text = (selector: string) =>
    view().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const rows = () =>
    [...view().querySelectorAll('tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.textContent?.replace(/\s+/g, ' ').trim()),
    );

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function expectAttendees(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/registrations/events/p1');
  }

  async function open(url: string, content: EventRegistrationResponse[]): Promise<TestRequest> {
    await harness.navigateByUrl(url);
    backend.expectOne('/api/events/p1').flush(EVENT);
    const request = expectAttendees();
    request.flush({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
    await elapse();
    return request;
  }

  it('lists the confirmed registrations of the event by default, with its summary', async () => {
    const request = await open('/organisation/p1/inscrits', [AWA, UNKNOWN]);

    expect(request.request.params.get('status')).toBe('CONFIRMED');
    expect(request.request.params.get('size')).toBe('20');
    expect(text('.subtitle')).toBe(
      'Spring Boot Day · 21/01/2099 · 09:00 · 148 / 200 places réservées',
    );
    expect(text('.count')).toBe('2 inscriptions');
    expect(rows()).toEqual([
      ['Awa Diallo', '03/12/2098 · 10:30', 'Confirmée'],
      ['Nom indisponible', '03/12/2098 · 10:30', 'Confirmée'],
    ]);
  });

  it('shows when a registration was cancelled, and filters through the address', async () => {
    const request = await open('/organisation/p1/inscrits?statut=toutes', [THOMAS]);
    expect(request.request.params.has('status')).toBe(false);
    expect(rows()).toEqual([['Thomas Leroy', '03/12/2098 · 10:30', 'Annulée le 05/12/2098']]);

    (view().querySelectorAll('mat-button-toggle button')[1] as HTMLButtonElement).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/organisation/p1/inscrits?statut=annulees');
    const cancelled = expectAttendees();
    expect(cancelled.request.params.get('status')).toBe('CANCELLED');
    cancelled.flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();
    expect(text('.state p')).toBe('Aucune inscription annulée.');
  });

  it('pages through many registrations', async () => {
    await harness.navigateByUrl('/organisation/p1/inscrits?page=1');
    backend.expectOne('/api/events/p1').flush(EVENT);
    const request = expectAttendees();
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ content: [AWA], page: 1, size: 20, totalElements: 21, totalPages: 2 });
    await elapse();

    expect(text('.mat-mdc-paginator-range-label')).toBe('21 – 21 sur 21');
    (
      view().querySelector('button.mat-mdc-paginator-navigation-previous') as HTMLButtonElement
    ).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/organisation/p1/inscrits');
    expectAttendees().flush({ content: [AWA], totalElements: 21, totalPages: 2 });
  });

  it('says when nobody is registered', async () => {
    await open('/organisation/p1/inscrits', []);
    expect(text('.state p')).toBe("Personne n'est inscrit pour l'instant.");
  });

  it("refuses another organizer's event, and retries what failed", async () => {
    await harness.navigateByUrl('/organisation/p1/inscrits');
    backend
      .expectOne('/api/events/p1')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    expectAttendees().flush({ code: 'NOT_EVENT_ORGANIZER' }, { status: 403, statusText: '' });
    await elapse();
    expect(text('[role="alert"] p')).toBe(
      "Seul l'organisateur de cet événement peut faire cette action.",
    );

    (view().querySelector('[role="alert"] button') as HTMLButtonElement).click();
    await elapse();
    backend.expectOne('/api/events/p1').flush(EVENT);
    expectAttendees().flush({ content: [AWA], totalElements: 1, totalPages: 1 });
    await elapse();
    expect(text('.count')).toBe('1 inscription');
  });
});
