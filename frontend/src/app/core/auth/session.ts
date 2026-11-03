import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The signed-in user, as the gateway (BFF) describes it: never a token, only who and which roles. */
export interface SessionUser {
  subject: string;
  name: string | null;
  email: string | null;
  roles: string[];
}

interface LogoutResponse {
  logoutUrl: string;
}

/** Where to come back after the Keycloak round trip (the login redirect leaves the application). */
const RETURN_URL_KEY = 'event-platform.return-url';

/**
 * Session held by the gateway (Backend for Frontend): the browser only has an HttpOnly cookie, so the user is
 * known by asking {@code /bff/me}, signing in is a full-page redirect and signing out is a POST protected by the
 * XSRF-TOKEN cookie that HttpClient sends back as the X-XSRF-TOKEN header.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly location = inject(BrowserLocation);

  private readonly current = signal<SessionUser | null>(null);

  readonly user = this.current.asReadonly();
  readonly authenticated = computed(() => this.current() !== null);
  readonly organizer = computed(() => this.hasRole('ORGANIZER'));

  /** Run once at startup: 401 means anonymous, any other failure too (the catalogue stays usable). */
  async load(): Promise<void> {
    try {
      this.current.set(await firstValueFrom(this.http.get<SessionUser>('/bff/me')));
    } catch (error) {
      if (!(error instanceof HttpErrorResponse)) {
        throw error;
      }
      this.current.set(null);
    }
  }

  hasRole(role: string): boolean {
    return this.current()?.roles.includes(role) ?? false;
  }

  /** Signs in through Keycloak, then comes back to {@code returnUrl} (see {@link takeReturnUrl}). */
  login(returnUrl = '/'): void {
    this.location.store(RETURN_URL_KEY, returnUrl);
    this.location.assign('/oauth2/authorization/keycloak');
  }

  /** Ends the gateway session, then the Keycloak one (RP-initiated logout). */
  async logout(): Promise<void> {
    const response = await firstValueFrom(this.http.post<LogoutResponse>('/logout', null));
    this.current.set(null);
    this.location.assign(response.logoutUrl);
  }

  /** The session ended on the server side (expired): the user is anonymous again. */
  expire(): void {
    this.current.set(null);
  }

  /** The page asked before signing in, once: only an in-app path is accepted (no open redirect). */
  takeReturnUrl(): string | null {
    const url = this.location.take(RETURN_URL_KEY);
    return url?.startsWith('/') && !url.startsWith('//') ? url : null;
  }
}

/** Full-page navigation and per-tab storage, behind a seam so that the session logic is testable. */
@Injectable({ providedIn: 'root' })
export class BrowserLocation {
  assign(url: string): void {
    window.location.assign(url);
  }

  /** Path and query of the current address. */
  current(): string {
    return window.location.pathname + window.location.search;
  }

  /** Changes the address without loading it (before the router reads it). */
  replace(url: string): void {
    history.replaceState(history.state, '', url);
  }

  store(key: string, value: string): void {
    try {
      sessionStorage.setItem(key, value);
    } catch {
      // Storage unavailable (private mode, blocked): the user simply lands on the home page.
    }
  }

  take(key: string): string | null {
    try {
      const value = sessionStorage.getItem(key);
      sessionStorage.removeItem(key);
      return value;
    } catch {
      return null;
    }
  }
}
