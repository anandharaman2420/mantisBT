import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { tap } from 'rxjs';
import { ApiService } from './api.service';
import { Meta, ProjectView, UserView } from './models';

const TOKEN_KEY = 'mantis.token';
const PROJECT_KEY = 'mantis.project';

/** Session state: JWT, current user, enum labels and the "current project" selector (like Mantis' project switcher). */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private api = inject(ApiService);
  private router = inject(Router);

  user = signal<UserView | null>(null);
  meta = signal<Meta>({});
  projects = signal<ProjectView[]>([]);
  /** null = "All Projects" */
  projectId = signal<number | null>(this.readProject());
  isAdmin = computed(() => (this.user()?.accessLevel ?? 0) >= 90);
  currentProject = computed(() => this.projects().find(p => p.id === this.projectId()) ?? null);

  get token(): string | null { return localStorage.getItem(TOKEN_KEY); }
  get loggedIn(): boolean { return !!this.token; }

  login(username: string, password: string) {
    return this.api.login(username, password).pipe(tap(r => {
      localStorage.setItem(TOKEN_KEY, r.token);
      this.user.set(r.user);
    }));
  }

  /** loads user, enum labels and project list; called by the guard on every page refresh */
  bootstrap() {
    return Promise.all([
      this.api.me().toPromise(), this.api.meta().toPromise(), this.api.projects().toPromise()
    ]).then(([u, m, p]) => {
      this.user.set(u!); this.meta.set(m!); this.projects.set(p!);
      if (this.projectId() !== null && !p!.some(x => x.id === this.projectId())) this.selectProject(null);
    });
  }

  reloadProjects() { return this.api.projects().toPromise().then(p => this.projects.set(p!)); }

  selectProject(id: number | null) {
    this.projectId.set(id);
    if (id === null) localStorage.removeItem(PROJECT_KEY); else localStorage.setItem(PROJECT_KEY, String(id));
  }

  logout() {
    localStorage.removeItem(TOKEN_KEY);
    this.user.set(null);
    this.router.navigate(['/login']);
  }

  /** label for an enum value, e.g. label('status', 50) -> "assigned" */
  label(kind: string, value: number | null | undefined): string {
    if (value === null || value === undefined) return '';
    return this.meta()[kind]?.[String(value)] ?? String(value);
  }
  options(kind: string): { value: number; label: string }[] {
    return Object.entries(this.meta()[kind] ?? {}).map(([k, v]) => ({ value: Number(k), label: v }));
  }

  private readProject(): number | null {
    const v = localStorage.getItem(PROJECT_KEY);
    return v ? Number(v) : null;
  }
}
