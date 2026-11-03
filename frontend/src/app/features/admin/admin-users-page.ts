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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { delete$ } from '../../api/users/fn/users/delete';
import { list } from '../../api/users/fn/users/list';
import { UserResponse } from '../../api/users/models/user-response';
import { UsersApi } from '../../api/users/users-api';
import { Session } from '../../core/auth/session';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { Confirmations } from '../../shared/confirm-dialog';
import { valueOf } from '../../shared/resources';
import { AdminTabs } from './admin-tabs';

const PAGE_SIZE = 20;
/** Same limit as user-service. */
const SEARCH_MAX = 100;

/**
 * The application profiles, newest first: search by name or e-mail (?q=), and erasure of a profile after a
 * confirmation that says what it does and does not delete. One's own profile is erased from "Mon compte".
 */
@Component({
  selector: 'app-admin-users-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    AdminTabs,
  ],
  templateUrl: './admin-users-page.html',
  styleUrl: './admin-users-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminUsersPage {
  private readonly api = inject(UsersApi);
  private readonly router = inject(Router);
  private readonly confirmations = inject(Confirmations);
  private readonly session = inject(Session);

  /** Query parameters, bound by the router. */
  readonly q = input<string | undefined>();
  readonly page = input(0, {
    transform: (value: unknown) => Math.max(numberAttribute(value, 0), 0),
  });

  protected readonly pageSize = PAGE_SIZE;
  protected readonly searchMax = SEARCH_MAX;
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly searched = computed(() => this.q()?.trim() || null);

  protected readonly users = rxResource({
    params: () => ({ q: this.searched() ?? undefined, page: this.page() }),
    stream: ({ params }) =>
      this.api.invoke(list, { ...params, size: PAGE_SIZE, sort: ['createdAt,desc'] }),
  });
  protected readonly result = computed(() => valueOf(this.users));

  protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  protected readonly loadError = computed(() => {
    const error = this.users.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  constructor() {
    // The field shows the search of the address (back and forward buttons included).
    effect(() => this.search.setValue(this.q() ?? ''));
  }

  protected isMe(user: UserResponse): boolean {
    return user.id === this.session.user()?.subject;
  }

  protected find(): void {
    const q = this.search.value.trim().slice(0, SEARCH_MAX);
    this.router.navigate([], { queryParams: { q: q || null, page: null } });
  }

  protected clear(): void {
    this.router.navigate([], { queryParams: { q: null, page: null } });
  }

  protected changePage(event: PageEvent): void {
    this.router.navigate([], {
      queryParams: { page: event.pageIndex || null },
      queryParamsHandling: 'merge',
    });
  }

  protected async remove(user: UserResponse): Promise<void> {
    const confirmed = await this.confirmations.ask({
      title: `Supprimer le profil de « ${user.fullName} » ?`,
      message:
        'Son nom et son e-mail sont effacés de la plateforme. Ses inscriptions restent, sans son nom. ' +
        "Son compte de connexion n'est pas supprimé : à sa prochaine connexion, un nouveau profil lui sera demandé.",
      confirm: 'Supprimer le profil',
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    this.busy.set(user.id!);
    this.notice.set(null);
    this.failure.set(null);
    try {
      await firstValueFrom(this.api.invoke(delete$, { id: user.id! }));
      this.notice.set(`Le profil de « ${user.fullName} » est supprimé.`);
    } catch (error) {
      this.failure.set(error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR'));
    } finally {
      this.busy.set(null);
      this.users.reload();
    }
  }
}
