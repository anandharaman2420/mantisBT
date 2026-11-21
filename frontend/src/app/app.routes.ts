import { Routes } from '@angular/router';
import { adminGuard, authGuard, developerOrAdminGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login.component').then(m => m.LoginComponent) },
  {
    path: '', canActivate: [authGuard],
    loadComponent: () => import('./layout.component').then(m => m.LayoutComponent),
    children: [
      { path: '', pathMatch: 'full', loadComponent: () => import('./pages/dashboard.component').then(m => m.DashboardComponent) },
      { path: 'issues', loadComponent: () => import('./pages/issue-list.component').then(m => m.IssueListComponent) },
      { path: 'report', loadComponent: () => import('./pages/issue-form.component').then(m => m.IssueFormComponent) },
      { path: 'issues/:id', loadComponent: () => import('./pages/issue-view.component').then(m => m.IssueViewComponent) },
      { path: 'issues/:id/edit', loadComponent: () => import('./pages/issue-form.component').then(m => m.IssueFormComponent) },
      { path: 'projects', loadComponent: () => import('./pages/projects.component').then(m => m.ProjectsComponent) },
      { path: 'users', canActivate: [adminGuard], loadComponent: () => import('./pages/users.component').then(m => m.UsersComponent) },
      { path: 'custom-fields', canActivate: [developerOrAdminGuard], loadComponent: () => import('./pages/custom-fields.component').then(m => m.CustomFieldsComponent) },
      { path: 'account', loadComponent: () => import('./pages/account.component').then(m => m.AccountComponent) }
    ]
  },
  { path: '**', redirectTo: '' }
];
