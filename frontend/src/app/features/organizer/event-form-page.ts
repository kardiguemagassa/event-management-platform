import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { EventsApi } from '../../api/events/events-api';
import { create } from '../../api/events/fn/events/create';
import { get } from '../../api/events/fn/events/get';
import { update } from '../../api/events/fn/events/update';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { Confirmations } from '../../shared/confirm-dialog';
import { LeaveCheck } from '../../shared/leave-guard';
import { valueOf } from '../../shared/resources';
import { eventForm, fieldError, fill, LIMITS, toLocalInput, toRequest } from './event-form';

/** Codes of the API that concern one field: the message is shown under it. */
const FIELD_OF_CODE: Record<string, keyof ReturnType<typeof eventForm>['controls']> = {
  EVENT_START_NOT_IN_FUTURE: 'startAt',
  CAPACITY_BELOW_RESERVED: 'capacity',
};

/**
 * Creates an event (as a draft, published later from the list) or edits one (/organisation/:id/modifier).
 * Leaving with changes not saved asks first.
 */
@Component({
  selector: 'app-event-form-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './event-form-page.html',
  styleUrl: './event-form-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(window:beforeunload)': 'warnBeforeUnload($event)' },
})
export class EventFormPage implements LeaveCheck {
  private readonly api = inject(EventsApi);
  private readonly router = inject(Router);
  private readonly confirmations = inject(Confirmations);

  /** Route parameter: absent when creating. */
  readonly id = input<string>();

  protected readonly form = eventForm();
  protected readonly limits = LIMITS;
  protected readonly fieldError = fieldError;
  /** No start in the past offered by the date picker. */
  protected readonly earliest = toLocalInput(new Date().toISOString());

  protected readonly loaded = rxResource({
    params: () => this.id(),
    stream: ({ params: id }) => this.api.invoke(get, { id }),
  });
  protected readonly event = computed(() => valueOf(this.loaded));
  protected readonly editing = computed(() => this.id() !== undefined);
  protected readonly reserved = computed(() => this.event()?.reservedSeats ?? 0);

  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  private saved = false;

  protected readonly loadError = computed(() => {
    const error = this.loaded.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  constructor() {
    effect(() => {
      const event = this.event();
      if (event) {
        fill(this.form, event);
      }
    });
  }

  protected async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.failure.set(null);
    try {
      const body = toRequest(this.form);
      const id = this.id();
      const event = await firstValueFrom(
        id ? this.api.invoke(update, { id, body }) : this.api.invoke(create, { body }),
      );
      this.saved = true;
      const notice = id
        ? `« ${event.title} » est enregistré.`
        : `Brouillon « ${event.title} » créé : publie-le quand il est prêt.`;
      await this.router.navigate(['/organisation'], {
        queryParams: id ? {} : { statut: 'brouillons' },
        state: { notice },
      });
    } catch (error) {
      this.showProblem(error);
    } finally {
      this.busy.set(false);
    }
  }

  canLeave(): boolean | Promise<boolean> {
    if (this.saved || !this.form.dirty) {
      return true;
    }
    return this.confirmations.ask({
      title: 'Quitter sans enregistrer ?',
      message: 'Tes modifications seront perdues.',
      confirm: 'Quitter',
      danger: true,
    });
  }

  /** Closing the tab or reloading with changes not saved: the browser asks. */
  protected warnBeforeUnload(event: BeforeUnloadEvent): void {
    if (!this.saved && this.form.dirty) {
      event.preventDefault();
    }
  }

  private showProblem(error: unknown): void {
    if (!(error instanceof ApiProblem)) {
      this.failure.set(messageOf('INTERNAL_ERROR'));
      return;
    }
    this.failure.set(error.message);
    const field = FIELD_OF_CODE[error.code];
    if (field) {
      this.markInvalid(field, error.message);
    }
    // Bean Validation's messages are in English: a French one under each field refused.
    for (const name of Object.keys(error.errors)) {
      if (name in this.form.controls) {
        this.markInvalid(name as keyof typeof this.form.controls, 'Valeur refusée.');
      }
    }
  }

  private markInvalid(field: keyof typeof this.form.controls, message: string): void {
    const control = this.form.controls[field];
    control.setErrors({ ...control.errors, server: message });
    control.markAsTouched();
  }
}
