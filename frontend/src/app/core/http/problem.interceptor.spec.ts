import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Session } from '../auth/session';
import { ApiProblem } from './api-problem';
import { problemInterceptor } from './problem.interceptor';

describe('problemInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  const session = { expire: vi.fn() };

  beforeEach(() => {
    session.expire.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemInterceptor])),
        provideHttpClientTesting(),
        { provide: Session, useValue: session },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('turns an API error into an ApiProblem', async () => {
    const call = firstValueFrom(http.get('/api/events/1'));
    backend
      .expectOne('/api/events/1')
      .flush({ code: 'EVENT_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });

    await expect(call).rejects.toMatchObject({
      code: 'EVENT_NOT_FOUND',
      message: 'Cet événement est introuvable.',
    });
    expect(session.expire).not.toHaveBeenCalled();
  });

  it('ends the session on a 401', async () => {
    const call = firstValueFrom(http.get('/api/registrations/me'));
    backend
      .expectOne('/api/registrations/me')
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: '' });

    await expect(call).rejects.toBeInstanceOf(ApiProblem);
    expect(session.expire).toHaveBeenCalledOnce();
  });

  it('leaves the session probe to the session', async () => {
    const call = firstValueFrom(http.get('/bff/me'));
    backend.expectOne('/bff/me').flush(null, { status: 401, statusText: '' });

    await expect(call).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(session.expire).not.toHaveBeenCalled();
  });
});
