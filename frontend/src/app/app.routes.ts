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
  { path: '**', redirectTo: '' },
];
