import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { me } from '../../api/users/fn/users/me';
import { UsersApi } from '../../api/users/users-api';
import { ApiProblem } from '../http/api-problem';

/** Path of the onboarding page; it brings the user back to {@code ?retour=} once the profile is saved. */
export const ONBOARDING_PATH = '/bienvenue';

/**
 * A signed-in user without an application profile (first sign-in, or profile deleted) is welcomed first: the
 * profile holds the display name that organizers see.
 */
@Injectable({ providedIn: 'root' })
export class ProfileCheck {
  private readonly api = inject(UsersApi);

  /** True only when user-service says the profile does not exist; any other failure lets the user in. */
  async missing(): Promise<boolean> {
    try {
      await firstValueFrom(this.api.invoke(me));
      return false;
    } catch (error) {
      return error instanceof ApiProblem && error.code === 'USER_NOT_FOUND';
    }
  }

  /** The onboarding address for a user who was going to {@code url}. */
  onboardingUrl(url: string): string {
    return url === '/' || url.startsWith(ONBOARDING_PATH)
      ? ONBOARDING_PATH
      : `${ONBOARDING_PATH}?retour=${encodeURIComponent(url)}`;
  }
}

/** An in-app path to come back to; anything else (absent, another site) is the home page. */
export function safeReturnPath(value: string | null | undefined): string {
  return value?.startsWith('/') && !value.startsWith('//') ? value : '/';
}
