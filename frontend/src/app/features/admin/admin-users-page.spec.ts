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
import { MatPaginatorIntl } from '@angular/material/paginator';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { UserResponse } from '../../api/users/models/user-response';
import { provideUsersApiConfiguration } from '../../api/users/users-api-configuration';
import { Session } from '../../core/auth/session';
import { problemInterceptor } from '../../core/http/problem.interceptor';
import { FrenchPaginatorIntl } from '../../core/ui/french-paginator-intl';
import { Confirmations } from '../../shared/confirm-dialog';
import { AdminUsersPage } from './admin-users-page';

registerLocaleData(localeFr);

const ME: UserResponse = {
  id: 'admin-1',
  fullName: 'Kardigué Magassa',
  email: 'k@example.com',
  createdAt: '2026-11-03T10:00:00Z',
};
const AWA: UserResponse = {
  id: 'u1',
  fullName: 'Awa Diallo',
  email: 'awa@example.com',
  createdAt: '2026-10-02T10:00:00Z',
};

describe('AdminUsersPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  const confirmations = { ask: vi.fn() };

  beforeEach(async () => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'administration/utilisateurs', component: AdminUsersPage }],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        provideUsersApiConfiguration(''),
        { provide: LOCALE_ID, useValue: 'fr-FR' },
        { provide: MatPaginatorIntl, useClass: FrenchPaginatorIntl },
        {
          provide: Session,
          useValue: {
            expire: vi.fn(),
            user: signal({ subject: 'admin-1', name: 'K', email: null, roles: ['ADMIN'] }),
          },
        },
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
  const rows = () =>
    [...view().querySelectorAll('tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.textContent?.replace(/\s+/g, ' ').trim()),
    );
  const deleteButton = (name: string) =>
    [...view().querySelectorAll('tbody tr')]
      .find((tr) => tr.querySelector('.name')?.textContent?.includes(name))!
      .querySelector('.actions button') as HTMLButtonElement | null;

  async function elapse(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
  }

  function expectList(): TestRequest {
    return backend.expectOne((request) => request.url === '/api/users' && request.method === 'GET');
  }

  async function open(
    url = '/administration/utilisateurs',
    content = [ME, AWA],
  ): Promise<TestRequest> {
    await harness.navigateByUrl(url);
    const request = expectList();
    request.flush({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
    await elapse();
    return request;
  }

  it("lists the profiles newest first, without offering to delete one's own", async () => {
    const request = await open();

    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.has('q')).toBe(false);
    expect(text('h1')).toBe('Administration');
    expect(text('.count')).toBe('2 profils');
    expect(rows()).toEqual([
      ['Kardigué Magassa Toi', 'k@example.com', '03/11/2026', ''],
      ['Awa Diallo', 'awa@example.com', '02/10/2026', 'Supprimer'],
    ]);
    expect(deleteButton('Kardigué')).toBeNull();
  });

  it('searches by name or e-mail through the address', async () => {
    await open();

    const field = view().querySelector('input[type="search"]') as HTMLInputElement;
    field.value = '  awa ';
    field.dispatchEvent(new Event('input'));
    (view().querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await elapse();

    expect(TestBed.inject(Router).url).toBe('/administration/utilisateurs?q=awa');
    const search = expectList();
    expect(search.request.params.get('q')).toBe('awa');
    search.flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();

    expect(text('.state p')).toBe('Aucun profil ne correspond à « awa ».');
    expect(field.value).toBe('awa');
    (view().querySelector('form button[type="button"]') as HTMLButtonElement).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/administration/utilisateurs');
    expectList().flush({ content: [], totalElements: 0, totalPages: 0 });
    await elapse();
    expect(text('.state p')).toBe("Aucun profil pour l'instant.");
  });

  it('deletes a profile only once confirmed, then reloads', async () => {
    await open();
    confirmations.ask.mockResolvedValueOnce(false);
    deleteButton('Awa')!.click();
    await elapse();
    backend.expectNone('/api/users/u1');

    confirmations.ask.mockResolvedValueOnce(true);
    deleteButton('Awa')!.click();
    await elapse();
    expect(confirmations.ask).toHaveBeenLastCalledWith(
      expect.objectContaining({ title: 'Supprimer le profil de « Awa Diallo » ?', danger: true }),
    );
    const deleting = backend.expectOne('/api/users/u1');
    expect(deleting.request.method).toBe('DELETE');
    deleting.flush('', { status: 204, statusText: '' });
    await elapse();
    expectList().flush({ content: [ME], totalElements: 1, totalPages: 1 });
    await elapse();

    expect(text('[role="status"]')).toBe('Le profil de « Awa Diallo » est supprimé.');
    expect(text('.count')).toBe('1 profil');
  });

  it('explains a refused deletion and a failed load', async () => {
    await open();
    confirmations.ask.mockResolvedValueOnce(true);
    deleteButton('Awa')!.click();
    await elapse();
    backend
      .expectOne('/api/users/u1')
      .flush('{"code":"USER_NOT_FOUND"}', { status: 404, statusText: '' });
    await elapse();
    expectList().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: '' });
    await elapse();

    expect(text('.feedback [role="alert"]')).toBe('Profil introuvable.');
    expect(text('.state[role="alert"] p')).toBe(
      'Le service est momentanément indisponible. Réessaie dans un instant.',
    );
    (view().querySelector('.state[role="alert"] button') as HTMLButtonElement).click();
    await elapse();
    expectList().flush({ content: [AWA], totalElements: 1, totalPages: 1 });
    await elapse();
    expect(text('.count')).toBe('1 profil');
  });

  it('pages through many profiles', async () => {
    await harness.navigateByUrl('/administration/utilisateurs?q=a&page=1');
    const request = expectList();
    expect(request.request.params.get('page')).toBe('1');
    request.flush({ content: [AWA], page: 1, size: 20, totalElements: 21, totalPages: 2 });
    await elapse();

    expect(text('.mat-mdc-paginator-range-label')).toBe('21 – 21 sur 21');
    (
      view().querySelector('button.mat-mdc-paginator-navigation-previous') as HTMLButtonElement
    ).click();
    await elapse();
    expect(TestBed.inject(Router).url).toBe('/administration/utilisateurs?q=a');
    expectList().flush({ content: [AWA], totalElements: 21, totalPages: 2 });
  });
});
