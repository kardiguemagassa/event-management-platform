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
import { provideUsersApiConfiguration } from '../../api/users/users-api-configuration';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { FrenchPaginatorIntl } from '../../core/ui/french-paginator-intl';
import { provideIcons } from '../../core/ui/icons';
import { Confirmations } from '../../shared/confirm-dialog';
import { AdminEventsPage } from './admin-events-page';

registerLocaleData(localeFr);

const PUBLISHED: EventResponse = {
  id: 'p1',
  title: 'Spring Boot Day',
  location: 'Paris',
  startAt: new Date(2099, 0, 21, 9, 0).toISOString(),
  capacity: 200,
  reservedSeats: 148,
  status: 'PUBLISHED',
  organizerId: 'o1',
};
const DRAFT: EventResponse = {
  ...PUBLISHED,
  id: 'd1',
  title: 'Atelier',
  reservedSeats: 0,
  status: 'DRAFT',
  organizerId: 'o2',
};
const CANCELLED: EventResponse = { ...DRAFT, id: 'c1', title: 'Afterwork', status: 'CANCELLED' };

describe('AdminEventsPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  const confirmations = { ask: vi.fn() };

  beforeEach(async () => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'administration/evenements', component: AdminEventsPage }],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
        provideUsersApiConfiguration(''),
        provideIcons(),
        { provide: LOCALE_ID, useValue: 'fr-FR' },
        { provide: MatPaginatorIntl, useClass: FrenchPaginatorIntl },
        { provide: Session, useValue: { expire: vi.fn() } },
        { provide: Confirmations, useValue: confirmations },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const view = () => harness.routeNativeElement as HTMLElement;
  const text = (selector: string) =>
    view().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const row = (title: string) =>
    [...view().querySelectorAll('tbody tr')].find(
      (tr) => tr.querySelector('strong')?.textContent === title,
    )!;
  const actions = (title: string) =>
    [...row(title).querySelectorAll('.actions button, .actions a')].map((action) =>
      action.textContent?.trim(),
    );

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function expectEvents(): TestRequest {
    return backend.expectOne(
      (request) => request.url === '/api/events' && request.method === 'GET',
    );
  }

  function expectNames(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/users' && request.method === 'GET');
  }

  async function open(
    url = '/administration/evenements',
    content = [PUBLISHED, DRAFT, CANCELLED],
  ): Promise<TestRequest> {
    await harness.navigateByUrl(url);
    const request = expectEvents();
    request.flush({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
    await elapse();
    return request;
  }

  it('lists every event, drafts included, newest first, with the name of each organizer', async () => {
    const request = await open();

    expect(request.request.params.get('scope')).toBe('all');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    expect(request.request.params.has('status')).toBe(false);
    const names = expectNames();
    expect(names.request.params.getAll('ids')).toEqual(['o1', 'o2']);
    names.flush({ content: [{ id: 'o1', fullName: 'Awa Diallo' }], totalElements: 1 });
    await elapse();

    expect(text('h1')).toBe('Administration');
    expect(text('.count')).toBe('3 événements');
    expect(row('Spring Boot Day').querySelector('.organizer')?.textContent?.trim()).toBe(
      'Awa Diallo',
    );
    expect(row('Atelier').querySelector('.organizer')?.textContent?.trim()).toBe(
      'Nom indisponible',
    );
    expect(actions('Spring Boot Day')).toEqual(['Inscrits', 'Annuler']);
    expect(row('Spring Boot Day').querySelector('.actions a')?.getAttribute('href')).toBe(
      '/administration/evenements/p1/inscrits',
    );
    expect(actions('Atelier')).toEqual(['Annuler']);
    expect(actions('Afterwork')).toEqual(['Inscrits']);
  });

  it('still lists the events when the names cannot be read', async () => {
    await open();
    expectNames().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    await elapse();

    expect(text('.count')).toBe('3 événements');
    expect(row('Spring Boot Day').querySelector('.organizer')?.textContent?.trim()).toBe(
      'Nom indisponible',
    );
  });

  it('searches and filters by status through the address', async () => {
    await open();
    expectNames().flush({ content: [] });

    const field = view().querySelector('input[type="search"]') as HTMLInputElement;
    field.value = ' kafka ';
    field.dispatchEvent(new Event('input'));
    (view().querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/administration/evenements?q=kafka');
    const searched = expectEvents();
    expect(searched.request.params.get('q')).toBe('kafka');
    searched.flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();
    expect(text('.state p')).toBe('Aucun événement ne correspond à « kafka ».');

    (view().querySelectorAll('mat-button-toggle button')[1] as HTMLButtonElement).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/administration/evenements?q=kafka&statut=brouillons');
    const drafts = expectEvents();
    expect(drafts.request.params.get('status')).toBe('DRAFT');
    expect(drafts.request.params.get('q')).toBe('kafka');
    drafts.flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();

    (view().querySelector('form button[type="button"]') as HTMLButtonElement).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/administration/evenements?statut=brouillons');
    expectEvents().flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();
    expect(text('.state p')).toBe('Aucun événement dans ce statut.');
  });

  it('cancels the event of an organizer once confirmed, naming the organizer', async () => {
    await open();
    expectNames().flush({ content: [{ id: 'o1', fullName: 'Awa Diallo' }] });
    await elapse();

    confirmations.ask.mockResolvedValueOnce(false);
    (row('Spring Boot Day').querySelector('.actions button') as HTMLButtonElement).click();
    await elapse();
    backend.expectNone('/api/events/p1/cancel');

    confirmations.ask.mockResolvedValueOnce(true);
    (row('Spring Boot Day').querySelector('.actions button') as HTMLButtonElement).click();
    await elapse();
    expect(confirmations.ask).toHaveBeenLastCalledWith(
      expect.objectContaining({
        title: 'Annuler « Spring Boot Day » ?',
        message: expect.stringContaining("l'événement de Awa Diallo est annulé"),
        danger: true,
      }),
    );
    const cancelling = backend.expectOne('/api/events/p1/cancel');
    expect(cancelling.request.method).toBe('POST');
    cancelling.flush({ ...PUBLISHED, status: 'CANCELLED' });
    await elapse();
    expectEvents().flush({ content: [{ ...PUBLISHED, status: 'CANCELLED' }], totalElements: 1 });
    await elapse();
    expectNames().flush({ content: [] });
    await elapse();

    expect(text('[role="status"]')).toBe('« Spring Boot Day » est annulé.');
  });

  it('explains a refused cancellation and a failed load', async () => {
    await open('/administration/evenements', [PUBLISHED]);
    expectNames().flush({ content: [] });
    confirmations.ask.mockResolvedValueOnce(true);
    (row('Spring Boot Day').querySelector('.actions button') as HTMLButtonElement).click();
    await elapse();
    backend
      .expectOne('/api/events/p1/cancel')
      .flush({ code: 'ACCESS_DENIED' }, { status: 403, statusText: '' });
    await elapse();
    expectEvents().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    await elapse();

    expect(text('.feedback [role="alert"]')).toBe("Tu n'as pas les droits pour cette action.");
    expect(text('.state[role="alert"] p')).toBe(
      'Le service est momentanément indisponible. Réessaie dans un instant.',
    );
    (view().querySelector('.state[role="alert"] button') as HTMLButtonElement).click();
    await elapse();
    expectEvents().flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();
    expect(text('.state p')).toBe("Aucun événement pour l'instant.");
  });

  it('pages through many events', async () => {
    await harness.navigateByUrl('/administration/evenements?page=1');
    const request = expectEvents();
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ content: [CANCELLED], page: 1, size: 20, totalElements: 21, totalPages: 2 });
    await elapse();
    expectNames().flush({ content: [] });
    await elapse();

    expect(text('.mat-mdc-paginator-range-label')).toBe('21 – 21 sur 21');
    (
      view().querySelector('button.mat-mdc-paginator-navigation-previous') as HTMLButtonElement
    ).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/administration/evenements');
    expectEvents().flush({ content: [], totalElements: 21, totalPages: 2 });
  });
});
