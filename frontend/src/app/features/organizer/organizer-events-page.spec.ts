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
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { FrenchPaginatorIntl } from '../../core/ui/french-paginator-intl';
import { Confirmations } from '../../shared/confirm-dialog';
import { OrganizerEventsPage } from './organizer-events-page';

registerLocaleData(localeFr);

const DRAFT: EventResponse = {
  id: 'd1',
  title: 'Atelier observabilité',
  location: 'Paris, Bastille',
  startAt: new Date(2099, 0, 21, 14, 0).toISOString(), // 14:00 in the time zone of the run, CI included
  capacity: 40,
  reservedSeats: 0,
  status: 'DRAFT',
};
const PUBLISHED: EventResponse = {
  ...DRAFT,
  id: 'p1',
  title: 'Spring Boot Day',
  reservedSeats: 148,
  capacity: 200,
  status: 'PUBLISHED',
};
const CANCELLED: EventResponse = { ...DRAFT, id: 'c1', title: 'Afterwork', status: 'CANCELLED' };

describe('OrganizerEventsPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  const confirmations = { ask: vi.fn() };

  beforeEach(async () => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'organisation', component: OrganizerEventsPage }],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
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
  const text = (selector: string) => view().querySelector(selector)?.textContent?.trim();
  const row = (title: string) =>
    [...view().querySelectorAll('tbody tr')].find(
      (tr) => tr.querySelector('strong')?.textContent === title,
    )!;
  const actions = (title: string) =>
    [...row(title).querySelectorAll('.actions button, .actions a')].map((action) =>
      action.textContent?.trim(),
    );
  const click = (title: string, label: string) =>
    (
      [...row(title).querySelectorAll('.actions button')].find(
        (button) => button.textContent?.trim() === label,
      ) as HTMLButtonElement
    ).click();

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function expectList(): TestRequest {
    return backend.expectOne(
      (request) => request.url === '/api/events' && request.method === 'GET',
    );
  }

  async function open(
    url = '/organisation',
    content = [DRAFT, PUBLISHED, CANCELLED],
  ): Promise<void> {
    await harness.navigateByUrl(url);
    expectList().flush({
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: 1,
    });
    await elapse();
  }

  it("lists the organizer's own events, drafts included, soonest first", async () => {
    await harness.navigateByUrl('/organisation');
    const params = expectList().request.params;
    expect(params.get('organizer')).toBe('me');
    expect(params.has('status')).toBe(false);
    expect(params.get('sort')).toBe('startAt,asc');
  });

  it('shows status, fill and the actions each status allows', async () => {
    await open();

    expect(text('.count')).toBe('3 événements');
    expect(row('Spring Boot Day').querySelector('.badge')?.textContent?.trim()).toBe('Publié');
    expect(row('Spring Boot Day').querySelector('.seats')?.textContent?.trim()).toBe(
      '148 / 200 places',
    );
    expect(row('Spring Boot Day').querySelector('time')?.textContent?.trim()).toBe(
      '21/01/2099 · 14:00',
    );
    expect(actions('Atelier observabilité')).toEqual([
      'Publier',
      'Modifier',
      'Annuler',
      'Supprimer',
    ]);
    expect(actions('Spring Boot Day')).toEqual(['Modifier', 'Annuler']);
    expect(row('Spring Boot Day').querySelector('.actions a')?.getAttribute('href')).toBe(
      '/organisation/p1/modifier',
    );
    expect(view().querySelector('.page-header a')?.getAttribute('href')).toBe(
      '/organisation/nouveau',
    );
    expect(actions('Afterwork')).toEqual(['Supprimer']);
  });

  it('shows the message left by the event form', async () => {
    await TestBed.inject(Router).navigate(['/organisation'], {
      state: { notice: '« Meetup » est enregistré.' },
    });
    await elapse();
    expectList().flush({ content: [DRAFT], totalElements: 1, totalPages: 1 });
    await elapse();

    expect(text('[role="status"]')).toBe('« Meetup » est enregistré.');
  });

  it('filters by status through the address', async () => {
    await open();

    (view().querySelectorAll('mat-button-toggle button')[1] as HTMLButtonElement).click();
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/organisation?statut=brouillons');
    const filtered = expectList();
    expect(filtered.request.params.get('status')).toBe('DRAFT');
    filtered.flush({ content: [DRAFT], totalElements: 1, totalPages: 1 });
  });

  it('publishes a draft and reloads the list', async () => {
    await open();

    click('Atelier observabilité', 'Publier');
    const publishing = backend.expectOne('/api/events/d1/publish');
    expect(publishing.request.method).toBe('POST');
    publishing.flush({ ...DRAFT, status: 'PUBLISHED' });
    await elapse();
    expectList().flush({
      content: [{ ...DRAFT, status: 'PUBLISHED' }],
      totalElements: 1,
      totalPages: 1,
    });
    await elapse();

    expect(text('[role="status"]')).toBe(
      '« Atelier observabilité » est publié : il apparaît dans le catalogue.',
    );
    expect(confirmations.ask).not.toHaveBeenCalled();
  });

  it('cancels an event only once confirmed', async () => {
    await open();
    confirmations.ask.mockResolvedValueOnce(false);
    click('Spring Boot Day', 'Annuler');
    await elapse();
    backend.expectNone('/api/events/p1/cancel');

    confirmations.ask.mockResolvedValueOnce(true);
    click('Spring Boot Day', 'Annuler');
    await elapse();
    expect(confirmations.ask).toHaveBeenLastCalledWith(
      expect.objectContaining({ title: 'Annuler « Spring Boot Day » ?', danger: true }),
    );
    backend.expectOne('/api/events/p1/cancel').flush({ ...PUBLISHED, status: 'CANCELLED' });
    await elapse();
    expectList().flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();

    expect(text('[role="status"]')).toBe('« Spring Boot Day » est annulé.');
  });

  it('explains a refused deletion', async () => {
    await open();
    confirmations.ask.mockResolvedValueOnce(true);

    click('Atelier observabilité', 'Supprimer');
    await elapse();
    const deleting = backend.expectOne('/api/events/d1');
    expect(deleting.request.method).toBe('DELETE');
    deleting.flush('{"code":"EVENT_HAS_RESERVATIONS"}', { status: 409, statusText: '' });
    await elapse();
    expectList().flush({ content: [DRAFT], totalElements: 1, totalPages: 1 });
    await elapse();

    expect(text('.feedback [role="alert"]')).toBe(
      "L'événement a des inscrits : annule-le plutôt que de le supprimer.",
    );
  });

  it('pages through many events', async () => {
    await harness.navigateByUrl('/organisation?page=1');
    const request = expectList();
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ content: [DRAFT], page: 1, size: 20, totalElements: 30, totalPages: 2 });
    await elapse();

    expect(text('.mat-mdc-paginator-range-label')).toBe('21 – 30 sur 30');
    (
      view().querySelector('button.mat-mdc-paginator-navigation-previous') as HTMLButtonElement
    ).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/organisation');
    expectList().flush({ content: [DRAFT], totalElements: 30, totalPages: 2 });
  });

  it('invites to create when there is nothing, and explains a failure', async () => {
    await open('/organisation', []);
    expect(text('.state p')).toBe("Tu n'as encore créé aucun événement.");
    expect(view().querySelector('.state a')?.getAttribute('href')).toBe('/organisation/nouveau');

    (view().querySelectorAll('mat-button-toggle button')[3] as HTMLButtonElement).click();
    await elapse();
    expectList().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    await elapse();
    expect(text('[role="alert"] p')).toBe(
      'Le service est momentanément indisponible. Réessaie dans un instant.',
    );

    (view().querySelector('[role="alert"] button') as HTMLButtonElement).click();
    await elapse();
    expectList().flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();
    expect(text('.state p')).toBe('Aucun événement dans ce statut.');
  });
});
