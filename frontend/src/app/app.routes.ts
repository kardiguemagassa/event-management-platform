import { Routes } from '@angular/router';
import { authenticated } from './core/auth/guards';

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
