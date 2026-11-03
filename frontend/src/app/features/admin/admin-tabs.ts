import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** The sections of the administration, as tabs that are links (each section has its own address). */
const SECTIONS = [{ label: 'Utilisateurs', link: '/administration/utilisateurs' }];

@Component({
  selector: 'app-admin-tabs',
  imports: [MatTabsModule, RouterLink, RouterLinkActive],
  template: `
    <header class="page-header">
      <h1>Administration</h1>
      <p class="subtitle">Modération de la plateforme. Chaque action est journalisée.</p>
    </header>
    <nav
      mat-tab-nav-bar
      mat-stretch-tabs="false"
      mat-align-tabs="start"
      [tabPanel]="panel"
      aria-label="Sections de l'administration"
    >
      @for (section of sections; track section.link) {
        <a
          mat-tab-link
          [routerLink]="section.link"
          routerLinkActive
          #active="routerLinkActive"
          [active]="active.isActive"
          >{{ section.label }}</a
        >
      }
    </nav>
    <mat-tab-nav-panel #panel><ng-content /></mat-tab-nav-panel>
  `,
  styles: `
    .page-header {
      margin-bottom: 12px;
      h1 {
        margin: 0;
        font-size: 30px;
      }
      .subtitle {
        margin: 6px 0 0;
        color: #475569;
      }
    }
    nav {
      margin-bottom: 20px;
    }
    @media (max-width: 820px) {
      .page-header h1 {
        font-size: 26px;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminTabs {
  protected readonly sections = SECTIONS;
}
