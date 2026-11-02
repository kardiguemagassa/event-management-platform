import { Routes } from '@angular/router';

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
  { path: '**', redirectTo: '' },
];
