import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import localeFr from '@angular/common/locales/fr';
import {
  ApplicationConfig,
  LOCALE_ID,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideEventsApiConfiguration } from './api/events/events-api-configuration';
import { provideRegistrationsApiConfiguration } from './api/registrations/registrations-api-configuration';
import { provideUsersApiConfiguration } from './api/users/users-api-configuration';
import { routes } from './app.routes';
import { startSession } from './core/auth/startup';
import { problemInterceptor } from './core/http/problem.interceptor';
import { FrenchPaginatorIntl } from './core/ui/french-paginator-intl';
import { provideIcons } from './core/ui/icons';

registerLocaleData(localeFr);

/** The API is on the same origin as the application (nginx, or the dev-server proxy): relative URLs. */
const SAME_ORIGIN = '';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    // XSRF protection is on by default: the XSRF-TOKEN cookie goes back as the X-XSRF-TOKEN header on writes.
    provideHttpClient(withFetch(), withInterceptors([problemInterceptor])),
    provideUsersApiConfiguration(SAME_ORIGIN),
    provideEventsApiConfiguration(SAME_ORIGIN),
    provideRegistrationsApiConfiguration(SAME_ORIGIN),
    { provide: LOCALE_ID, useValue: 'fr-FR' },
    { provide: MAT_DATE_LOCALE, useValue: 'fr-FR' },
    provideNativeDateAdapter(),
    { provide: MatPaginatorIntl, useClass: FrenchPaginatorIntl },
    provideIcons(),
    // Who is signed in, where to go back to, and onboarding: before the first page.
    provideAppInitializer(startSession),
  ],
};
