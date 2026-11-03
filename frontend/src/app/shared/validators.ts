import { AbstractControl, ValidationErrors } from '@angular/forms';

/** Bean Validation's @NotBlank: whitespace alone is no value. */
export function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() ? null : { blank: true };
}
