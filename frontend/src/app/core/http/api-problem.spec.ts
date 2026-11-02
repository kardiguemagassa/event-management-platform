import { HttpErrorResponse } from '@angular/common/http';
import { ApiProblem, messageOf } from './api-problem';

describe('ApiProblem', () => {
  it('keeps the code and field errors of a problem response, with a French message', () => {
    const problem = ApiProblem.from(
      new HttpErrorResponse({
        status: 400,
        error: { code: 'VALIDATION_FAILED', errors: { q: 'At most 100 characters' } },
      }),
    );

    expect(problem).toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      message: 'Certains champs sont invalides.',
      errors: { q: 'At most 100 characters' },
    });
  });

  it.each([
    [0, 'NETWORK_ERROR'],
    [401, 'UNAUTHENTICATED'],
    [403, 'ACCESS_DENIED'],
    [502, 'SERVICE_UNAVAILABLE'],
    [503, 'SERVICE_UNAVAILABLE'],
    [504, 'UPSTREAM_TIMEOUT'],
    [500, 'INTERNAL_ERROR'],
  ])('derives a code from status %i when there is no problem body', (status, code) => {
    expect(ApiProblem.from(new HttpErrorResponse({ status, error: '<html>' })).code).toBe(code);
  });

  it('ignores malformed field errors', () => {
    const problem = ApiProblem.from(
      new HttpErrorResponse({
        status: 400,
        error: { code: 'VALIDATION_FAILED', errors: { q: 1 } },
      }),
    );
    expect(problem.errors).toEqual({});
  });

  it('has a generic message for an unknown code', () => {
    expect(messageOf('SOMETHING_NEW')).toBe(
      "Une erreur inattendue s'est produite. Réessaie plus tard.",
    );
  });
});
