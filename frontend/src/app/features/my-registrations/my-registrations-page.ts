import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { map, Observable, of, switchMap } from 'rxjs';
import { EventsApi } from '../../api/events/events-api';
import { list } from '../../api/events/fn/events/list';
import { mine } from '../../api/registrations/fn/registrations/mine';
import { RegistrationsApi } from '../../api/registrations/registrations-api';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { valueOf } from '../../shared/resources';
import { splitByDate, SplitRegistrations } from './my-registrations';

/** Largest page the API serves, and largest batch read of events: one call each. */
const MAX_PAGE = 100;

export type Period = 'a-venir' | 'passees';

/**
 * The caller's confirmed registrations, upcoming or past (?periode= in the address). Two calls whatever the number
 * of registrations: the registrations, then their events in one batch read (?ids=). Up to 100 registrations, the
 * most recent ones: beyond, older ones are not listed (a known limit, see the README).
 */
@Component({
  selector: 'app-my-registrations-page',
  imports: [DatePipe, RouterLink, MatButtonModule, MatButtonToggleModule, MatProgressBarModule],
  templateUrl: './my-registrations-page.html',
  styleUrl: './my-registrations-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyRegistrationsPage {
  private readonly registrationsApi = inject(RegistrationsApi);
  private readonly eventsApi = inject(EventsApi);
  private readonly router = inject(Router);

  /** Query parameter, bound by the router; anything else than "passees" shows the upcoming ones. */
  readonly periode = input<string | undefined>();
  protected readonly period = computed<Period>(() =>
    this.periode() === 'passees' ? 'passees' : 'a-venir',
  );

  protected readonly registrations = rxResource({
    stream: () => this.load(),
  });

  /** Undefined while loading or failed. */
  protected readonly split = computed(() => valueOf(this.registrations));

  protected readonly shown = computed(() => {
    const split = this.split();
    if (!split) {
      return [];
    }
    return this.period() === 'passees' ? split.past : split.upcoming;
  });

  protected readonly errorMessage = computed(() => {
    const error = this.registrations.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  protected choosePeriod(change: MatButtonToggleChange): void {
    const periode = change.value === 'passees' ? 'passees' : null;
    this.router.navigate([], { queryParams: { periode }, replaceUrl: true });
  }

  private load(): Observable<SplitRegistrations> {
    return this.registrationsApi
      .invoke(mine, { status: 'CONFIRMED', size: MAX_PAGE, sort: ['createdAt,desc'] })
      .pipe(
        switchMap((page) => {
          const registrations = page.content ?? [];
          const ids = [...new Set(registrations.flatMap((r) => (r.eventId ? [r.eventId] : [])))];
          if (ids.length === 0) {
            return of(splitByDate([], [], new Date()));
          }
          return this.eventsApi
            .invoke(list, { ids, size: MAX_PAGE })
            .pipe(map((events) => splitByDate(registrations, events.content ?? [], new Date())));
        }),
      );
  }
}
