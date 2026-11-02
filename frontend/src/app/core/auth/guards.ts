import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Session } from './session';

/** Pages of a signed-in user: an anonymous visitor goes through the Keycloak login, then comes back here. */
export const authenticated: CanActivateFn = (_route, state) => {
  const session = inject(Session);
  if (session.authenticated()) {
    return true;
  }
  session.login(state.url);
  return false;
};

/** Pages reserved to a realm role: a signed-in user without it is sent back to the catalogue. */
export function hasRole(role: string): CanActivateFn {
  return (_route, state) => {
    const session = inject(Session);
    if (!session.authenticated()) {
      session.login(state.url);
      return false;
    }
    return session.hasRole(role) || inject(Router).parseUrl('/');
  };
}
