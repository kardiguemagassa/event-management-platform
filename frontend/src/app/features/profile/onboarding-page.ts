import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { saveMe } from '../../api/users/fn/users/save-me';
import { UsersApi } from '../../api/users/users-api';
import { safeReturnPath } from '../../core/auth/profile-check';
import { Session } from '../../core/auth/session';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { FULL_NAME_MAX, fullNameControl, fullNameError } from '../../shared/full-name';

/**
 * First sign-in: the display name organizers will see, pre-filled from the sign-in account. Then back to the page
 * the user was going to (?retour=).
 */
@Component({
  selector: 'app-onboarding-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './onboarding-page.html',
  styleUrl: './onboarding-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OnboardingPage {
  private readonly api = inject(UsersApi);
  private readonly router = inject(Router);

  /** Query parameter, bound by the router. */
  readonly retour = input<string | undefined>();

  protected readonly maxLength = FULL_NAME_MAX;
  protected readonly fullName = fullNameControl(inject(Session).user()?.name ?? '');
  protected readonly fullNameError = fullNameError;
  protected readonly saving = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected async save(): Promise<void> {
    if (this.fullName.invalid) {
      this.fullName.markAsTouched();
      return;
    }
    this.saving.set(true);
    this.failure.set(null);
    try {
      await firstValueFrom(
        this.api.invoke(saveMe, { body: { fullName: this.fullName.value.trim() } }),
      );
      await this.router.navigateByUrl(safeReturnPath(this.retour()), { replaceUrl: true });
    } catch (error) {
      this.failure.set(error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR'));
    } finally {
      this.saving.set(false);
    }
  }
}
