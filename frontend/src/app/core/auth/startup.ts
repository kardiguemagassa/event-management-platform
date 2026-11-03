import { inject } from '@angular/core';
import { ProfileCheck } from './profile-check';
import { BrowserLocation, Session } from './session';

/**
 * Runs before the router's initial navigation, which then reads the address bar: who is signed in; back from the
 * Keycloak login, the page asked first; and a user without a profile is welcomed first.
 */
export async function startSession(): Promise<void> {
  // Injected before any await: the injection context ends at the first one.
  const session = inject(Session);
  const profiles = inject(ProfileCheck);
  const location = inject(BrowserLocation);

  await session.load();
  if (!session.authenticated()) {
    return;
  }
  let url = session.takeReturnUrl() ?? location.current();
  if (await profiles.missing()) {
    url = profiles.onboardingUrl(url);
  }
  if (url !== location.current()) {
    location.replace(url);
  }
}
