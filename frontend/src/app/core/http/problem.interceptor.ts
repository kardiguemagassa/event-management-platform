import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Session } from '../auth/session';
import { ApiProblem } from './api-problem';

/**
 * Every API failure reaches the pages as an {@link ApiProblem} (code and French message), whatever its shape.
 * A 401 on the API means the gateway session is over: the user becomes anonymous (the session probe itself,
 * {@code /bff/me}, is left to {@link Session}).
 */
export const problemInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(Session);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || request.url.startsWith('/bff/')) {
        return throwError(() => error);
      }
      if (error.status === 401) {
        session.expire();
      }
      return throwError(() => ApiProblem.from(error));
    }),
  );
};
