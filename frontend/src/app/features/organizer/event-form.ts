import {
  AbstractControl,
  FormControl,
  FormGroup,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { EventRequest } from '../../api/events/models/event-request';
import { EventResponse } from '../../api/events/models/event-response';
import { notBlank } from '../../shared/validators';

/** Same limits as event-service (EventRequest): checked here first, for an immediate message. */
export const LIMITS = { title: 200, location: 200, description: 2000, capacity: 100_000 } as const;

export type EventForm = FormGroup<{
  title: FormControl<string>;
  location: FormControl<string>;
  startAt: FormControl<string>;
  capacity: FormControl<number | null>;
  description: FormControl<string>;
}>;

/** @Future: the start, read in the browser's time zone, is after now. */
function future(control: AbstractControl<string>): ValidationErrors | null {
  if (!control.value) {
    return null;
  }
  const start = fromLocalInput(control.value);
  return start && Date.parse(start) > Date.now() ? null : { past: true };
}

export function eventForm(): EventForm {
  return new FormGroup({
    title: new FormControl('', {
      nonNullable: true,
      validators: [notBlank, Validators.maxLength(LIMITS.title)],
    }),
    location: new FormControl('', {
      nonNullable: true,
      validators: [notBlank, Validators.maxLength(LIMITS.location)],
    }),
    startAt: new FormControl('', { nonNullable: true, validators: [Validators.required, future] }),
    capacity: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(1), Validators.max(LIMITS.capacity)],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(LIMITS.description)],
    }),
  });
}

/** Editing: the event's values, and no capacity below the seats already reserved. */
export function fill(form: EventForm, event: EventResponse): void {
  const reserved = event.reservedSeats ?? 0;
  form.controls.capacity.setValidators([
    Validators.required,
    Validators.min(Math.max(1, reserved)),
    Validators.max(LIMITS.capacity),
  ]);
  form.reset({
    title: event.title ?? '',
    location: event.location ?? '',
    startAt: event.startAt ? toLocalInput(event.startAt) : '',
    capacity: event.capacity ?? null,
    description: event.description ?? '',
  });
}

export function toRequest(form: EventForm): EventRequest {
  const value = form.getRawValue();
  return {
    title: value.title.trim(),
    location: value.location.trim(),
    startAt: fromLocalInput(value.startAt) ?? '',
    capacity: value.capacity ?? 0,
    description: value.description.trim() || undefined,
  };
}

/** The French message of the first error of a field, or null. */
export function fieldError(control: AbstractControl, reserved = 0): string | null {
  const errors = control.errors;
  if (!errors) {
    return null;
  }
  if (errors['blank'] || errors['required']) {
    return 'Ce champ est obligatoire.';
  }
  if (errors['maxlength']) {
    return `${errors['maxlength'].requiredLength} caractères au maximum.`;
  }
  if (errors['past']) {
    return 'La date de début doit être dans le futur.';
  }
  if (errors['min']) {
    return reserved > 1
      ? `Au moins ${reserved} places : ${reserved} sont déjà réservées.`
      : 'Au moins 1 place.';
  }
  if (errors['max']) {
    return `${LIMITS.capacity.toLocaleString('fr-FR')} places au maximum.`;
  }
  return errors['server'] ?? 'Valeur invalide.';
}

/** An instant as the value of an <input type="datetime-local">, in the browser's time zone. */
export function toLocalInput(instant: string): string {
  const date = new Date(instant);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** The value of an <input type="datetime-local"> as an instant (ISO 8601, UTC), or null if unreadable. */
export function fromLocalInput(value: string): string | null {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
