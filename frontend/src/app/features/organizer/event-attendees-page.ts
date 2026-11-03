import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  numberAttribute,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { EventsApi } from '../../api/events/events-api';
import { get } from '../../api/events/fn/events/get';
import { findByEvent } from '../../api/registrations/fn/registrations/find-by-event';
import { RegistrationsApi } from '../../api/registrations/registrations-api';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { valueOf } from '../../shared/resources';

const PAGE_SIZE = 20;

/** Status filters, as they appear in the address (?statut=); confirmed registrations by default. */
const FILTERS = {
  confirmees: 'CONFIRMED',
  annulees: 'CANCELLED',
  toutes: undefined,
} as const;
type Filter = keyof typeof FILTERS;

const EMPTY: Record<Filter, string> = {
  confirmees: "Personne n'est inscrit pour l'instant.",
  annulees: 'Aucune inscription annulée.',
  toutes: "Personne ne s'est encore inscrit.",
};

/**
 * The registrations of one of the organizer's events, newest first: the attendee's name (as given in their
 * profile), when they registered, and when they cancelled. Readable by the event's organizer only (the API checks).
 */
@Component({
  selector: 'app-event-attendees-page',
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatPaginatorModule,
    MatProgressBarModule,
  ],
  templateUrl: './event-attendees-page.html',
  styleUrl: './event-attendees-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventAttendeesPage {
  private readonly events = inject(EventsApi);
  private readonly registrations = inject(RegistrationsApi);
  private readonly router = inject(Router);

  /** Route and query parameters, bound by the router. */
  readonly id = input.required<string>();
  readonly statut = input<string | undefined>();
  readonly page = input(0, {
    transform: (value: unknown) => Math.max(numberAttribute(value, 0), 0),
  });

  protected readonly pageSize = PAGE_SIZE;
  protected readonly filter = computed<Filter>(() => {
    const statut = this.statut();
    return statut && statut in FILTERS ? (statut as Filter) : 'confirmees';
  });
  protected readonly emptyMessage = computed(() => EMPTY[this.filter()]);

  protected readonly loadedEvent = rxResource({
    params: () => this.id(),
    stream: ({ params: id }) => this.events.invoke(get, { id }),
  });
  protected readonly event = computed(() => valueOf(this.loadedEvent));

  protected readonly attendees = rxResource({
    params: () => ({ eventId: this.id(), status: FILTERS[this.filter()], page: this.page() }),
    stream: ({ params }) => this.registrations.invoke(findByEvent, { ...params, size: PAGE_SIZE }),
  });
  protected readonly result = computed(() => valueOf(this.attendees));

  protected readonly loadError = computed(() => {
    const error = this.attendees.error() ?? this.loadedEvent.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  protected retry(): void {
    if (this.loadedEvent.error()) {
      this.loadedEvent.reload();
    }
    this.attendees.reload();
  }

  protected chooseFilter(change: MatButtonToggleChange): void {
    const statut = change.value === 'confirmees' ? null : change.value;
    this.router.navigate([], { queryParams: { statut, page: null }, replaceUrl: true });
  }

  protected changePage(event: PageEvent): void {
    this.router.navigate([], {
      queryParams: { page: event.pageIndex || null },
      queryParamsHandling: 'merge',
    });
  }
}
