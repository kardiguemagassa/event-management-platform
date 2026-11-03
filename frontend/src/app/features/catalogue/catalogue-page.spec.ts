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
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEventsApiConfiguration } from '../../api/events/events-api-configuration';
import { PageResponseEventResponse } from '../../api/events/models/page-response-event-response';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { FrenchPaginatorIntl } from '../../core/ui/french-paginator-intl';
import { provideIcons } from '../../core/ui/icons';
import { CataloguePage } from './catalogue-page';

registerLocaleData(localeFr);

const SPRING_DAY = {
  id: 'e1',
  title: 'Spring Boot Day',
  location: 'Paris',
  startAt: '2099-12-03T08:00:00Z',
  capacity: 200,
  reservedSeats: 148,
  status: 'PUBLISHED' as const,
};

function page(
  content: PageResponseEventResponse['content'],
  totalElements = content?.length ?? 0,
): PageResponseEventResponse {
  return { content, page: 0, size: 12, totalElements, totalPages: Math.ceil(totalElements / 12) };
}

describe('CataloguePage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '', component: CataloguePage }], withComponentInputBinding()),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideEventsApiConfiguration(''),
        provideNativeDateAdapter(),
        provideIcons(),
        { provide: MatPaginatorIntl, useClass: FrenchPaginatorIntl },
        { provide: LOCALE_ID, useValue: 'fr-FR' },
        { provide: Session, useValue: { expire: vi.fn() } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  function expectList(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/events');
  }

  /** Renders once the requests answered (whenStable waits for the pending ones: flush them first). */
  async function settle(): Promise<HTMLElement> {
    await harness.fixture.whenStable();
    harness.detectChanges();
    return harness.routeNativeElement as HTMLElement;
  }

  /** Lets timers, navigations and effects run, without waiting for the requests they send. */
  async function elapse(ms = 0): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, ms));
    harness.detectChanges();
  }

  it('lists the published events from today, earliest first', async () => {
    await harness.navigateByUrl('/');
    const request = expectList();
    const params = request.request.params;
    expect(params.get('status')).toBe('PUBLISHED');
    expect(params.get('size')).toBe('12');
    expect(params.get('sort')).toBe('startAt,asc');
    expect(params.has('q')).toBe(false);
    const from = new Date(params.get('from') ?? '');
    expect(from.getHours()).toBe(0);
    expect(from.getDate()).toBe(new Date().getDate());

    request.flush(page([SPRING_DAY]));
    const view = await settle();

    expect(view.querySelector('h1')?.textContent).toBe('Événements à venir');
    expect(view.querySelectorAll('app-event-card')).toHaveLength(1);
    expect(view.querySelector('.count')?.textContent?.trim()).toBe('1 événement');
    expect(view.querySelector('mat-paginator')).toBeNull();
  });

  it('searches with the filters of the address', async () => {
    await harness.navigateByUrl('/?q=paris&from=2099-12-01&page=2');
    const request = expectList();
    const params = request.request.params;

    expect(params.get('q')).toBe('paris');
    expect(params.get('from')).toBe(new Date(2099, 11, 1).toISOString());
    expect(params.get('page')).toBe('2');
    request.flush(page([]));
  });

  it('puts the search in the address, back to the first page', async () => {
    await harness.navigateByUrl('/?page=3');
    expectList().flush(page([SPRING_DAY], 40));
    const view = await settle();

    const input = view.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = '  kafka ';
    input.dispatchEvent(new Event('input'));
    await elapse(350);

    expect(TestBed.inject(Router).url).toBe('/?q=kafka');
    const search = expectList();
    expect(search.request.params.get('q')).toBe('kafka');
    expect(search.request.params.get('page')).toBe('0');
    search.flush(page([]));
  });

  it('says when nothing matches and offers to clear the filters', async () => {
    await harness.navigateByUrl('/?q=zzz');
    expectList().flush(page([]));
    const view = await settle();

    expect(view.querySelector('.state p')?.textContent?.trim()).toBe(
      'Aucun événement ne correspond à ta recherche.',
    );
    (view.querySelector('.state button') as HTMLButtonElement).click();
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/');
    expectList().flush(page([]));
    const empty = await settle();
    expect(empty.querySelector('.state p')?.textContent?.trim()).toBe(
      'Aucun événement à venir pour le moment.',
    );
  });

  it('pages through many events', async () => {
    await harness.navigateByUrl('/');
    expectList().flush(page([SPRING_DAY], 30));
    const view = await settle();

    expect(view.querySelector('mat-paginator')).not.toBeNull();
    expect(view.querySelector('.mat-mdc-paginator-range-label')?.textContent?.trim()).toBe(
      '1 – 12 sur 30',
    );
    (view.querySelector('button.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/?page=1');
    const next = expectList();
    expect(next.request.params.get('page')).toBe('1');
    next.flush(page([SPRING_DAY], 30));
  });

  it('explains a failure in French and retries', async () => {
    await harness.navigateByUrl('/');
    expectList().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    const view = await settle();

    expect(view.querySelector('[role="alert"] p')?.textContent).toBe(
      'Le service est momentanément indisponible. Réessaie dans un instant.',
    );
    (view.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    await elapse();
    expectList().flush(page([SPRING_DAY]));
    expect((await settle()).querySelectorAll('app-event-card')).toHaveLength(1);
  });
});
