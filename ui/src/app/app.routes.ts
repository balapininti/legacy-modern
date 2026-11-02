import { Routes } from '@angular/router';
import { requireRole, signedIn } from './core/http';
import { Shell } from './shell';

const program = () => import('./pages/programs.page').then((m) => m.ProgramsPage);

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage) },
  { path: 'auth/callback', loadComponent: () => import('./pages/login.page').then((m) => m.OidcCallbackPage) },
  {
    path: '',
    component: Shell,
    canActivate: [signedIn],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'inventory' },
      { path: 'inventory', title: 'Inventory', loadComponent: () => import('./pages/inventory.page').then((m) => m.InventoryPage) },
      { path: 'programs', title: 'Program explorer', loadComponent: program },
      { path: 'programs/:name', title: 'Program explorer', loadComponent: program },
      { path: 'graph', title: 'Graph view', loadComponent: () => import('./pages/graph.page').then((m) => m.GraphPage) },
      { path: 'search', title: 'Search', loadComponent: () => import('./pages/search.page').then((m) => m.SearchPage) },
      { path: 'review', title: 'Review queue', canActivate: [requireRole('sme-reviewer', 'admin')], loadComponent: () => import('./pages/review.page').then((m) => m.ReviewPage) },
      { path: 'links', title: 'Link confirmation', loadComponent: () => import('./pages/links.page').then((m) => m.LinksPage) },
      { path: 'gaps', title: 'Gap report', loadComponent: () => import('./pages/gaps.page').then((m) => m.GapsPage) },
      { path: 'deviations', title: 'Deviations', loadComponent: () => import('./pages/deviations.page').then((m) => m.DeviationsPage) },
      { path: 'tracker', title: 'Migration tracker', loadComponent: () => import('./pages/tracker.page').then((m) => m.TrackerPage) },
      { path: 'captures', title: 'Captures', canActivate: [requireRole('developer', 'admin')], loadComponent: () => import('./pages/captures.page').then((m) => m.CapturesPage) },
      { path: 'setup', title: 'Setup wizard', canActivate: [requireRole('admin')], loadComponent: () => import('./pages/setup.page').then((m) => m.SetupPage) },
      { path: 'admin', title: 'Admin', canActivate: [requireRole('admin')], loadComponent: () => import('./pages/admin.page').then((m) => m.AdminPage) },
      { path: 'not-allowed', title: 'Not allowed', loadComponent: () => import('./pages/not-allowed.page').then((m) => m.NotAllowedPage) },
    ],
  },
  { path: '**', redirectTo: '' },
];
