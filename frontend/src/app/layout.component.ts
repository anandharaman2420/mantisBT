import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'app-layout', standalone: true, imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  template: `
  <header class="nav">
    <div class="inner">
      <a routerLink="/" class="brand">🐞 MantisBT</a>
      <nav>
        <a routerLink="/" routerLinkActive="on" [routerLinkActiveOptions]="{exact:true}">Dashboard</a>
        <a routerLink="/issues" routerLinkActive="on">View Issues</a>
        <a routerLink="/report" routerLinkActive="on">Report Issue</a>
        <a routerLink="/projects" routerLinkActive="on">Manage Projects</a>
        @if (auth.config().timeTracking && auth.isManager()) { <a routerLink="/time-report" routerLinkActive="on">Time Report</a> }
        @if (auth.isAdmin()) { <a routerLink="/users" routerLinkActive="on">Manage Users</a> }
        @if (auth.isAdmin() || (auth.user()?.accessLevel ?? 0) >= 55) { <a routerLink="/custom-fields" routerLinkActive="on">Custom Fields</a> }
      </nav>
      <span class="sp"></span>
      <select class="proj" [ngModel]="auth.projectId()" (ngModelChange)="auth.selectProject($event)">
        <option [ngValue]="null">All Projects</option>
        @for (o of auth.projectOptions(); track o.project.id) { <option [ngValue]="o.project.id">{{ o.label }}</option> }
      </select>
      <a routerLink="/account" class="user">{{ auth.user()?.username }}</a>
      <button class="small" (click)="auth.logout()">Logout</button>
    </div>
  </header>
  <main class="container"><router-outlet /></main>`,
  styles: [`
    .nav { background: #1a365d; color: #fff; } .inner { max-width: 1280px; margin: 0 auto; padding: 8px 18px; display: flex; gap: 14px; align-items: center; flex-wrap: wrap; }
    .brand { color: #fff; font-weight: 700; font-size: 17px; } nav { display: flex; gap: 4px; }
    nav a { color: #cbd5e0; padding: 5px 10px; border-radius: 4px; } nav a:hover { background: rgba(255,255,255,.1); text-decoration: none; } nav a.on { background: rgba(255,255,255,.18); color: #fff; }
    .sp { flex: 1; } .proj { width: auto; min-width: 150px; } .user { color: #fff; }
  `]
})
export class LayoutComponent {
  auth = inject(AuthService);
  constructor(private router: Router) {}
}
