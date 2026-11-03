import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom, map, of } from 'rxjs';
import { EventsApi } from '../../api/events/events-api';
import { cancel } from '../../api/events/fn/events/cancel';
import { list } from '../../api/events/fn/events/list';
import { EventResponse } from '../../api/events/models/event-response';
import { list as listUsers } from '../../api/users/fn/users/list';
import { UsersApi } from '../../api/users/users-api';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { Confirmations } from '../../shared/confirm-dialog';
import { valueOf } from '../../shared/resources';
import { availability } from '../../shared/seats';
import { AdminTabs } from './admin-tabs';

const PAGE_SIZE = 20;
/** Same limit as event-service. */
const SEARCH_MAX = 100;

/** Status filters, as they appear in the address (?statut=). */
const FILTERS = {
  tous: undefined,
  brouillons: 'DRAFT',
  publies: 'PUBLISHED',
  annules: 'CANCELLED',
} as const;
type Filter = keyof typeof FILTERS;

const STATUS_LABELS: Record<NonNullable<EventResponse['status']>, string> = {
  DRAFT: 'Brouillon',
  PUBLISHED: 'Publié',
  CANCELLED: 'Annulé',
};

/**
 * Moderation of the events of every organizer, drafts included (?scope=all), newest first: search, status filter,
 * the name of the organizer, who registered, and cancellation. An administrator cancels but never edits nor deletes
 * the event of another (the API enforces it).
 */
@Component({
  selector: 'app-admin-events-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    AdminTabs,
  ],
  templateUrl: './admin-events-page.html',
  styleUrl: './admin-events-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminEventsPage {
  private readonly api = inject(EventsApi);
  private readonly users = inject(UsersApi);
  private readonly router = inject(Router);
  private readonly confirmations = inject(Confirmations);

  /** Query parameters, bound by the router. */
  readonly statut = input<string | undefined>();
  readonly q = input<string | undefined>();
  readonly page = input(0, {
    transform: (value: unknown) => Math.max(numberAttribute(value, 0), 0),
  });

  protected readonly pageSize = PAGE_SIZE;
  protected readonly searchMax = SEARCH_MAX;
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly searched = computed(() => this.q()?.trim() || null);
  protected readonly filter = computed<Filter>(() => {
    const statut = this.statut();
    return statut && statut in FILTERS ? (statut as Filter) : 'tous';
  });

  protected readonly events = rxResource({
    params: () => ({
      status: FILTERS[this.filter()],
      q: this.searched() ?? undefined,
      page: this.page(),
    }),
    stream: ({ params }) =>
      this.api.invoke(list, {
        scope: 'all',
        ...params,
        size: PAGE_SIZE,
        sort: ['createdAt,desc'],
      }),
  });
  protected readonly result = computed(() => valueOf(this.events));

  /** Names of the organizers of the page, in one call; unknown names never fail the list. */
  private readonly organizers = rxResource({
    params: () => {
      const ids = [
        ...new Set(
          (this.result()?.content ?? []).map((event) => event.organizerId!).filter(Boolean),
        ),
      ];
      return ids.length ? ids : undefined;
    },
    stream: ({ params: ids }) =>
      ids.length
        ? this.users
            .invoke(listUsers, { ids, size: ids.length })
            .pipe(
              map(
                (page) =>
                  new Map((page.content ?? []).map((user) => [user.id!, user.fullName ?? ''])),
              ),
            )
        : of(new Map<string, string>()),
  });

  protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  protected readonly loadError = computed(() => {
    const error = this.events.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  constructor() {
    effect(() => this.search.setValue(this.q() ?? ''));
  }

  protected organizerName(event: EventResponse): string | null {
    return valueOf(this.organizers)?.get(event.organizerId ?? '') || null;
  }

  protected statusLabel(event: EventResponse): string {
    return event.status ? STATUS_LABELS[event.status] : '';
  }

  protected booked(event: EventResponse): number {
    return availability(event.capacity ?? 0, event.reservedSeats ?? 0).booked;
  }

  protected find(): void {
    const q = this.search.value.trim().slice(0, SEARCH_MAX);
    this.router.navigate([], {
      queryParams: { q: q || null, page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected clear(): void {
    this.router.navigate([], {
      queryParams: { q: null, page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected chooseFilter(change: MatButtonToggleChange): void {
    const statut = change.value === 'tous' ? null : change.value;
    this.router.navigate([], {
      queryParams: { statut, page: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected changePage(event: PageEvent): void {
    this.router.navigate([], {
      queryParams: { page: event.pageIndex || null },
      queryParamsHandling: 'merge',
    });
  }

  protected async cancel(event: EventResponse): Promise<void> {
    const organizer = this.organizerName(event);
    const confirmed = await this.confirmations.ask({
      title: `Annuler « ${event.title} » ?`,
      message:
        `Modération : l'événement${organizer ? ` de ${organizer}` : ''} est annulé pour tous ses inscrits, ` +
        "qui ne peuvent plus s'y inscrire. Cette action est définitive et journalisée.",
      confirm: "Annuler l'événement",
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    this.busy.set(event.id!);
    this.notice.set(null);
    this.failure.set(null);
    try {
      await firstValueFrom(this.api.invoke(cancel, { id: event.id! }));
      this.notice.set(`« ${event.title} » est annulé.`);
    } catch (error) {
      this.failure.set(error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR'));
    } finally {
      this.busy.set(null);
      this.events.reload();
    }
  }
}
