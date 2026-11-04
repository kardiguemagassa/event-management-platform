import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom, map } from 'rxjs';
import { EventsApi } from '../../api/events/events-api';
import { get } from '../../api/events/fn/events/get';
import { cancel } from '../../api/registrations/fn/registrations/cancel';
import { get as getRegistration } from '../../api/registrations/fn/registrations/get';
import { mine } from '../../api/registrations/fn/registrations/mine';
import { register } from '../../api/registrations/fn/registrations/register';
import { RegistrationResponse } from '../../api/registrations/models/registration-response';
import { RegistrationsApi } from '../../api/registrations/registrations-api';
import { Session } from '../../core/auth/session';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { isActive } from '../../shared/registrations';
import { valueOf } from '../../shared/resources';
import { availability, seatsLeftLabel } from '../../shared/seats';

/** What the visitor can do with this event, in order of precedence. */
type Stage = 'cancelled' | 'draft' | 'past' | 'pending' | 'registered' | 'full' | 'open';

/** How often, and how long, a new registration is followed until event-service answers (usually in under a second). */
export const FOLLOW_INTERVAL_MS = 500;
export const FOLLOW_ATTEMPTS = 20;

/**
 * One event and the visitor's place in it: register, or cancel one's registration. Anonymous visitors see the
 * event too; registering takes them through the login and back here.
 */
@Component({
  selector: 'app-event-detail-page',
  imports: [DatePipe, RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './event-detail-page.html',
  styleUrl: './event-detail-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventDetailPage {
  private readonly eventsApi = inject(EventsApi);
  private readonly registrationsApi = inject(RegistrationsApi);
  private readonly router = inject(Router);
  protected readonly session = inject(Session);

  /** Route parameter. */
  readonly id = input.required<string>();

  protected readonly event = rxResource({
    params: () => ({ id: this.id() }),
    stream: ({ params }) => this.eventsApi.invoke(get, params),
  });

  /**
   * The visitor's active registration to this event (PENDING or CONFIRMED): their latest one, if still active; not
   * asked for anonymous visitors. If this check fails, the page still offers to register: the API refuses a
   * duplicate (ALREADY_REGISTERED), which reloads the check.
   */
  protected readonly registration = rxResource({
    params: () => (this.session.authenticated() ? { eventId: this.id() } : undefined),
    stream: ({ params }) =>
      this.registrationsApi.invoke(mine, { eventId: params.eventId, size: 1 }).pipe(
        map((page): RegistrationResponse | null => {
          const latest = page.content?.[0];
          return latest && isActive(latest) ? latest : null;
        }),
      ),
  });

  protected readonly busy = signal(false);
  protected readonly confirmingCancel = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  protected readonly seats = computed(() => {
    const event = valueOf(this.event);
    return event ? availability(event.capacity ?? 0, event.reservedSeats ?? 0) : null;
  });
  protected readonly seatsLabel = computed(() => seatsLeftLabel(this.seats()?.left ?? 0));

  protected readonly stage = computed<Stage | null>(() => {
    const event = valueOf(this.event);
    if (!event) {
      return null;
    }
    if (event.status === 'CANCELLED') {
      return 'cancelled';
    }
    if (event.status === 'DRAFT') {
      return 'draft';
    }
    if (event.startAt && new Date(event.startAt) < new Date()) {
      return 'past';
    }
    const registration = valueOf(this.registration);
    if (registration) {
      return registration.status === 'PENDING' ? 'pending' : 'registered';
    }
    return this.seats()?.tone === 'full' ? 'full' : 'open';
  });

  /** 404 has its own page; any other failure can be retried. */
  protected readonly loadError = computed(() => {
    const error = this.event.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem
      ? error
      : new ApiProblem(0, 'INTERNAL_ERROR', messageOf('INTERNAL_ERROR'));
  });

  protected login(): void {
    this.session.login(this.router.url);
  }

  /**
   * The seat is reserved asynchronously: the registration is accepted as PENDING, then followed until event-service
   * confirms or refuses it.
   */
  protected async register(): Promise<void> {
    await this.act(async () => {
      const requested = await firstValueFrom(
        this.registrationsApi.invoke(register, { body: { eventId: this.id() } }),
      );
      this.registration.set(requested);
      const answered = requested.id ? await this.follow(requested.id) : requested;
      switch (answered.status) {
        case 'CONFIRMED':
          this.registration.set(answered);
          this.notice.set('Inscription confirmée. À bientôt !');
          break;
        case 'REJECTED':
          this.registration.set(null);
          this.failure.set(messageOf(answered.rejectionReason ?? 'INTERNAL_ERROR'));
          break;
        default:
          this.notice.set('Demande reçue : ton inscription sera confirmée dans quelques instants.');
      }
    });
  }

  /** The registration once answered, or as it still is after the last attempt. */
  private async follow(id: string): Promise<RegistrationResponse> {
    let registration: RegistrationResponse = { id, status: 'PENDING' };
    for (let attempt = 0; attempt < FOLLOW_ATTEMPTS; attempt++) {
      if (attempt > 0) {
        await new Promise((resolve) => setTimeout(resolve, FOLLOW_INTERVAL_MS));
      }
      registration = await firstValueFrom(this.registrationsApi.invoke(getRegistration, { id }));
      if (registration.status !== 'PENDING') {
        return registration;
      }
    }
    return registration;
  }

  /** A registration left PENDING (page reloaded meanwhile): checks again. */
  protected refresh(): void {
    this.notice.set(null);
    this.registration.reload();
    this.event.reload();
  }

  protected async cancelRegistration(): Promise<void> {
    const registration = valueOf(this.registration);
    if (!registration?.id) {
      return;
    }
    const id = registration.id;
    await this.act(async () => {
      await firstValueFrom(this.registrationsApi.invoke(cancel, { id }));
      this.registration.set(null);
      this.notice.set('Inscription annulée. Ta place est libérée.');
    });
  }

  /** Runs an action once at a time; the seat count is refreshed whatever happens (it may have changed). */
  private async act(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    try {
      await action();
    } catch (error) {
      this.failure.set(error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR'));
      if (error instanceof ApiProblem && error.code === 'ALREADY_REGISTERED') {
        this.registration.reload();
      }
    } finally {
      this.busy.set(false);
      this.confirmingCancel.set(false);
      this.event.reload();
    }
  }
}
