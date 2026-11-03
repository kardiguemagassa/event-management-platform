import { Routes } from '@angular/router';
import { authenticated, hasRole } from './core/auth/guards';
import { confirmLeave } from './shared/leave-guard';

export const routes: Routes = [
  {
    path: '',
    title: 'Événements à venir · Event Platform',
    loadComponent: () => import('./features/catalogue/catalogue-page').then((m) => m.CataloguePage),
  },
  {
    path: 'evenements/:id',
    title: "Détail de l'événement · Event Platform",
    loadComponent: () =>
      import('./features/event-detail/event-detail-page').then((m) => m.EventDetailPage),
  },
  {
    path: 'mes-inscriptions',
    title: 'Mes inscriptions · Event Platform',
    canActivate: [authenticated],
    loadComponent: () =>
      import('./features/my-registrations/my-registrations-page').then(
        (m) => m.MyRegistrationsPage,
      ),
  },
  {
    path: 'organisation',
    title: 'Mes événements · Event Platform',
    canActivate: [hasRole('ORGANIZER')],
    loadComponent: () =>
      import('./features/organizer/organizer-events-page').then((m) => m.OrganizerEventsPage),
  },
  {
    path: 'administration',
    canActivate: [hasRole('ADMIN')],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'utilisateurs' },
      {
        path: 'utilisateurs',
        title: 'Utilisateurs · Administration · Event Platform',
        loadComponent: () =>
          import('./features/admin/admin-users-page').then((m) => m.AdminUsersPage),
      },
    ],
  },
  {
    path: 'organisation/nouveau',
    title: 'Nouvel événement · Event Platform',
    canActivate: [hasRole('ORGANIZER')],
    canDeactivate: [confirmLeave],
    loadComponent: () =>
      import('./features/organizer/event-form-page').then((m) => m.EventFormPage),
  },
  {
    path: 'organisation/:id/inscrits',
    title: 'Inscrits · Event Platform',
    canActivate: [hasRole('ORGANIZER')],
    loadComponent: () =>
      import('./features/organizer/event-attendees-page').then((m) => m.EventAttendeesPage),
  },
  {
    path: 'organisation/:id/modifier',
    title: "Modifier l'événement · Event Platform",
    canActivate: [hasRole('ORGANIZER')],
    canDeactivate: [confirmLeave],
    loadComponent: () =>
      import('./features/organizer/event-form-page').then((m) => m.EventFormPage),
  },
  {
    path: 'profil',
    title: 'Mon compte · Event Platform',
    canActivate: [authenticated],
    loadComponent: () => import('./features/profile/profile-page').then((m) => m.ProfilePage),
  },
  {
    path: 'bienvenue',
    title: 'Bienvenue · Event Platform',
    canActivate: [authenticated],
    loadComponent: () => import('./features/profile/onboarding-page').then((m) => m.OnboardingPage),
  },
  { path: '**', redirectTo: '' },
];
