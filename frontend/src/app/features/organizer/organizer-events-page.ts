import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  numberAttribute,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { EventsApi } from '../../api/events/events-api';
import { cancel } from '../../api/events/fn/events/cancel';
import { delete$ } from '../../api/events/fn/events/delete';
import { list } from '../../api/events/fn/events/list';
import { publish } from '../../api/events/fn/events/publish';
import { EventResponse } from '../../api/events/models/event-response';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { Confirmations } from '../../shared/confirm-dialog';
import { valueOf } from '../../shared/resources';
import { availability } from '../../shared/seats';

const PAGE_SIZE = 20;

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
 * The organizer's own events, drafts and cancelled ones included (?organizer=me): publish a draft, cancel an event,
 * delete one nobody registered to. Every change asks for confirmation when it cannot be undone.
 */
@Component({
  selector: 'app-organizer-events-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatPaginatorModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './organizer-events-page.html',
  styleUrl: './organizer-events-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrganizerEventsPage {
  private readonly api = inject(EventsApi);
  private readonly router = inject(Router);
  private readonly confirmations = inject(Confirmations);

  /** Query parameters, bound by the router. */
  readonly statut = input<string | undefined>();
  readonly page = input(0, {
    transform: (value: unknown) => Math.max(numberAttribute(value, 0), 0),
  });

  protected readonly pageSize = PAGE_SIZE;
  protected readonly filter = computed<Filter>(() => {
    const statut = this.statut();
    return statut && statut in FILTERS ? (statut as Filter) : 'tous';
  });

  protected readonly events = rxResource({
    params: () => ({ status: FILTERS[this.filter()], page: this.page() }),
    stream: ({ params }) =>
      this.api.invoke(list, {
        organizer: 'me',
        status: params.status,
        page: params.page,
        size: PAGE_SIZE,
        sort: ['startAt,asc'],
      }),
  });
  protected readonly result = computed(() => valueOf(this.events));

  protected readonly busy = signal<string | null>(null);
  /** A message left by the page that sent here (the event form, after saving). */
  protected readonly notice = signal<string | null>(
    noticeIn(this.router.currentNavigation()?.extras.state),
  );
  protected readonly failure = signal<string | null>(null);

  protected readonly loadError = computed(() => {
    const error = this.events.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  protected statusLabel(event: EventResponse): string {
    return event.status ? STATUS_LABELS[event.status] : '';
  }

  protected booked(event: EventResponse): number {
    return availability(event.capacity ?? 0, event.reservedSeats ?? 0).booked;
  }

  protected chooseFilter(change: MatButtonToggleChange): void {
    const statut = change.value === 'tous' ? null : change.value;
    this.router.navigate([], { queryParams: { statut, page: null }, replaceUrl: true });
  }

  protected changePage(event: PageEvent): void {
    this.router.navigate([], {
      queryParams: { page: event.pageIndex || null },
      queryParamsHandling: 'merge',
    });
  }

  protected async publish(event: EventResponse): Promise<void> {
    await this.act(event, `« ${event.title} » est publié : il apparaît dans le catalogue.`, () =>
      firstValueFrom(this.api.invoke(publish, { id: event.id! })),
    );
  }

  protected async cancel(event: EventResponse): Promise<void> {
    const confirmed = await this.confirmations.ask({
      title: `Annuler « ${event.title} » ?`,
      message:
        "Les inscriptions ne seront plus possibles et l'événement apparaîtra comme annulé à ses inscrits. " +
        'Cette action est définitive.',
      confirm: "Annuler l'événement",
      danger: true,
    });
    if (confirmed) {
      await this.act(event, `« ${event.title} » est annulé.`, () =>
        firstValueFrom(this.api.invoke(cancel, { id: event.id! })),
      );
    }
  }

  protected async remove(event: EventResponse): Promise<void> {
    const confirmed = await this.confirmations.ask({
      title: `Supprimer « ${event.title} » ?`,
      message: "L'événement est effacé définitivement. Personne n'y est inscrit.",
      confirm: 'Supprimer',
      danger: true,
    });
    if (confirmed) {
      await this.act(event, `« ${event.title} » est supprimé.`, () =>
        firstValueFrom(this.api.invoke(delete$, { id: event.id! })),
      );
    }
  }

  /** One change at a time; the list is reloaded whatever happens (it may have changed meanwhile). */
  private async act(
    event: EventResponse,
    success: string,
    action: () => Promise<unknown>,
  ): Promise<void> {
    this.busy.set(event.id ?? null);
    this.notice.set(null);
    this.failure.set(null);
    try {
      await action();
      this.notice.set(success);
    } catch (error) {
      this.failure.set(error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR'));
    } finally {
      this.busy.set(null);
      this.events.reload();
    }
  }
}

function noticeIn(state: unknown): string | null {
  const notice = (state as { notice?: unknown } | null)?.notice;
  return typeof notice === 'string' ? notice : null;
}
