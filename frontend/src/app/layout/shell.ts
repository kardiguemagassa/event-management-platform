import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Session } from '../core/auth/session';
import { ApiProblem, messageOf } from '../core/http/api-problem';

interface NavItem {
  label: string;
  icon: string;
  link: string;
}

/**
 * Application frame of the mockups: a top bar on large screens, a bottom navigation bar on phones (same items,
 * CSS breakpoint, no JavaScript). Pages of the next steps add their items here.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly session = inject(Session);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly navItems: NavItem[] = [{ label: 'Catalogue', icon: 'calendar', link: '/' }];
  protected readonly signingOut = signal(false);

  protected login(): void {
    this.session.login(this.router.url);
  }

  protected async logout(): Promise<void> {
    this.signingOut.set(true);
    try {
      await this.session.logout();
    } catch (error) {
      this.signingOut.set(false);
      const message = error instanceof ApiProblem ? error.message : messageOf('INTERNAL_ERROR');
      this.snackBar.open(message, 'Fermer', { duration: 6000 });
    }
  }
}
