import { FormControl, Validators } from '@angular/forms';
import { notBlank } from './validators';

/** Same rules as user-service (@NotBlank @Size(max = 100)): checked here first, for an immediate message. */
export const FULL_NAME_MAX = 100;

export function fullNameControl(initial: string): FormControl<string> {
  return new FormControl(initial, {
    nonNullable: true,
    validators: [notBlank, Validators.maxLength(FULL_NAME_MAX)],
  });
}

export function fullNameError(control: FormControl<string>): string | null {
  if (control.hasError('blank')) {
    return 'Indique ton nom.';
  }
  if (control.hasError('maxlength')) {
    return `${FULL_NAME_MAX} caractères au maximum.`;
  }
  return null;
}
