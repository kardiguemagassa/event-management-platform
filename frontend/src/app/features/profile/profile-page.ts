import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { deleteMe } from '../../api/users/fn/users/delete-me';
import { me } from '../../api/users/fn/users/me';
import { saveMe } from '../../api/users/fn/users/save-me';
import { UsersApi } from '../../api/users/users-api';
import { ProfileCheck } from '../../core/auth/profile-check';
import { Session } from '../../core/auth/session';
import { ApiProblem, messageOf } from '../../core/http/api-problem';
import { FULL_NAME_MAX, fullNameControl, fullNameError } from '../../shared/full-name';
import { valueOf } from '../../shared/resources';

/**
 * The caller's account: display name (editable), e-mail (managed by Keycloak), sign-out, and the right to erasure
 * of the application profile.
 */
@Component({
  selector: 'app-profile-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './profile-page.html',
  styleUrl: './profile-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfilePage {
  private readonly api = inject(UsersApi);
  private readonly router = inject(Router);
  private readonly profiles = inject(ProfileCheck);
  protected readonly session = inject(Session);

  protected readonly profile = rxResource({ stream: () => this.api.invoke(me) });
  protected readonly user = computed(() => valueOf(this.profile));

  protected readonly maxLength = FULL_NAME_MAX;
  protected readonly fullName = fullNameControl('');
  protected readonly fullNameError = fullNameError;

  protected readonly busy = signal(false);
  protected readonly confirmingDelete = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  protected readonly loadError = computed(() => {
    const error = this.profile.error();
    if (!error) {
      return null;
    }
    return error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
  });

  constructor() {
    effect(() => {
      const user = this.user();
      if (user) {
        this.fullName.reset(user.fullName ?? '');
      }
    });
    // No profile yet (or deleted from another device): the onboarding page creates it, then comes back here.
    effect(() => {
      const error = this.profile.error();
      if (error instanceof ApiProblem && error.code === 'USER_NOT_FOUND') {
        this.router.navigateByUrl(this.profiles.onboardingUrl('/profil'), { replaceUrl: true });
      }
    });
  }

  protected async save(): Promise<void> {
    if (this.fullName.invalid) {
      this.fullName.markAsTouched();
      return;
    }
    await this.act(async () => {
      const saved = await firstValueFrom(
        this.api.invoke(saveMe, { body: { fullName: this.fullName.value.trim() } }),
      );
      this.profile.set(saved);
      this.notice.set('Profil enregistré.');
    });
  }

  protected async deleteProfile(): Promise<void> {
    await this.act(async () => {
      await firstValueFrom(this.api.invoke(deleteMe));
      // Nothing left to show: sign out (gateway, then Keycloak).
      await this.session.logout();
    });
  }

  protected async logout(): Promise<void> {
    await this.act(() => this.session.logout());
  }

  private async act(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    try {
      await action();
    } catch (error) {
      this.failure.set(error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR'));
    } finally {
      this.busy.set(false);
      this.confirmingDelete.set(false);
    }
  }
}
