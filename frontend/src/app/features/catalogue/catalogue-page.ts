import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  numberAttribute,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map } from 'rxjs';
import { EventsApi } from '../../api/events/events-api';
import { list } from '../../api/events/fn/events/list';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { EventCard } from './event-card';
import { fromQueryDate, startOfDay, toQueryDate } from './query-dates';

const PAGE_SIZE = 12;
const MAX_SEARCH_LENGTH = 100;

/**
 * Upcoming published events, searchable by title or place and from a given day. The filters live in the address
 * (?q=&from=&page=): a search can be shared, bookmarked, and the back button restores it.
 */
@Component({
  selector: 'app-catalogue-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    RouterLink,
    EventCard,
  ],
  templateUrl: './catalogue-page.html',
  styleUrl: './catalogue-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CataloguePage {
  private readonly api = inject(EventsApi);
  private readonly router = inject(Router);

  /** Query parameters, bound by the router. */
  readonly q = input<string | undefined>();
  readonly from = input<string | undefined>();
  readonly page = input(0, {
    transform: (value: unknown) => Math.max(numberAttribute(value, 0), 0),
  });

  protected readonly pageSize = PAGE_SIZE;
  protected readonly maxSearchLength = MAX_SEARCH_LENGTH;
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly day = new FormControl<Date | null>(null);
  protected readonly today = startOfDay(new Date());

  /** From the chosen day, or from today: the catalogue lists upcoming events only. */
  private readonly fromDay = computed(() => fromQueryDate(this.from()) ?? this.today);

  protected readonly events = rxResource({
    params: () => ({ q: this.q()?.trim() || undefined, from: this.fromDay(), page: this.page() }),
    stream: ({ params }) =>
      this.api.invoke(list, {
        status: 'PUBLISHED',
        q: params.q,
        from: params.from.toISOString(),
        page: params.page,
        size: PAGE_SIZE,
        sort: ['startAt,asc'],
      }),
  });

  protected readonly errorMessage = computed(() => {
    const error = this.events.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  protected readonly filtered = computed(() => !!this.q()?.trim() || !!this.from());

  constructor() {
    // Address -> form (first load, back button), without echoing the change back.
    effect(() => {
      this.search.setValue(this.q() ?? '', { emitEvent: false });
      this.day.setValue(fromQueryDate(this.from()), { emitEvent: false });
    });

    // Form -> address: typing is debounced, a new search starts again from the first page. Refining a search
    // replaces the history entry; turning a page adds one (the back button returns to the previous page).
    const destroyRef = inject(DestroyRef);
    this.search.valueChanges
      .pipe(
        map((value) => value.trim()),
        debounceTime(300),
        distinctUntilChanged(),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe((q) => this.navigate({ q: q || null, page: null }, true));
    this.day.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((day) => this.navigate({ from: day ? toQueryDate(day) : null, page: null }, true));
  }

  protected changePage(event: PageEvent): void {
    this.navigate({ page: event.pageIndex || null }, false);
  }

  protected clearFilters(): void {
    this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }

  private navigate(queryParams: Record<string, string | number | null>, replaceUrl: boolean): void {
    this.router.navigate([], { queryParams, queryParamsHandling: 'merge', replaceUrl });
  }
}
