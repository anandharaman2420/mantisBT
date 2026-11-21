import { Component, effect, inject, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { CategoryView, IssueSummary, UserRef } from '../core/models';

@Component({
  selector: 'app-issue-list', standalone: true, imports: [FormsModule, RouterLink, DatePipe],
  template: `
  <h1>View Issues <span class="muted">({{ total }})</span></h1>
  <div class="panel">
    <div class="row">
      <div class="grow" style="min-width:220px"><label>Search (summary, description, id)</label><input [(ngModel)]="q" (keyup.enter)="reload()" placeholder="Search…"></div>
      <div><label>Status</label><select [(ngModel)]="status"><option [ngValue]="null">any</option>
        @for (o of auth.options('status'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      <div><label>Priority</label><select [(ngModel)]="priority"><option [ngValue]="null">any</option>
        @for (o of auth.options('priority'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      <div><label>Severity</label><select [(ngModel)]="severity"><option [ngValue]="null">any</option>
        @for (o of auth.options('severity'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      @if (auth.projectId()) {
        <div><label>Category</label><select [(ngModel)]="categoryId"><option [ngValue]="null">any</option>
          @for (c of categories; track c.id) { <option [ngValue]="c.id">{{ c.name }}</option> }</select></div>
        <div><label>Assigned to</label><select [(ngModel)]="handlerId"><option [ngValue]="null">anyone</option><option [ngValue]="0">[none]</option>
          @for (u of handlers; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div>
      }
      <div><label>&nbsp;</label><label style="display:flex;gap:5px;align-items:center;font-weight:400"><input type="checkbox" [(ngModel)]="hideClosed"> hide closed</label></div>
      <div><label>&nbsp;</label><button class="primary" (click)="reload()">Apply</button> <button (click)="reset()">Reset</button></div>
    </div>
  </div>
  @if (error) { <div class="err">{{ error }}</div> }
  <div class="panel" style="padding:0;overflow:auto">
    <table>
      <thead><tr>
        <th class="sortable" (click)="sort('id')">ID{{ arrow('id') }}</th>
        @if (!auth.projectId()) { <th>Project</th> }
        <th>Category</th>
        <th class="sortable" (click)="sort('severity')">Severity{{ arrow('severity') }}</th>
        <th class="sortable" (click)="sort('priority')">Priority{{ arrow('priority') }}</th>
        <th class="sortable" (click)="sort('status')">Status{{ arrow('status') }}</th>
        <th class="sortable" (click)="sort('lastUpdated')">Updated{{ arrow('lastUpdated') }}</th>
        <th class="sortable" (click)="sort('summary')">Summary{{ arrow('summary') }}</th>
        <th>Assigned</th>
      </tr></thead>
      <tbody>
        @for (i of items; track i.id) {
          <tr class="clickable" [routerLink]="['/issues', i.id]">
            <td class="nowrap"><a [routerLink]="['/issues', i.id]">{{ i.id.toString().padStart(7, '0') }}</a> @if (i.viewState === 50) { 🔒 }</td>
            @if (!auth.projectId()) { <td>{{ i.projectName }}</td> }
            <td>{{ i.category }}</td>
            <td>{{ auth.label('severity', i.severity) }}</td>
            <td>{{ auth.label('priority', i.priority) }}</td>
            <td><span class="chip s{{ i.status }}">{{ auth.label('status', i.status) }}</span>@if (i.status >= 80) { <span class="muted"> ({{ auth.label('resolution', i.resolution) }})</span> }</td>
            <td class="nowrap">{{ i.lastUpdated | date:'yyyy-MM-dd' }}</td>
            <td>{{ i.summary }}</td>
            <td>{{ i.handler?.username }}</td>
          </tr>
        } @empty { <tr><td colspan="9" class="muted">No issues match the filter.</td></tr> }
      </tbody>
    </table>
  </div>
  <div class="row" style="justify-content:space-between;align-items:center">
    <span class="muted">Page {{ page + 1 }} of {{ pages }}</span>
    <span><button (click)="go(page - 1)" [disabled]="page === 0">‹ Prev</button> <button (click)="go(page + 1)" [disabled]="page + 1 >= pages">Next ›</button></span>
  </div>`
})
export class IssueListComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  items: IssueSummary[] = []; total = 0; page = 0; size = 25; error = '';
  q = ''; status: number | null = null; priority: number | null = null; severity: number | null = null;
  categoryId: number | null = null; handlerId: number | null = null; hideClosed = true;
  sortBy = 'lastUpdated'; dir = 'desc';
  categories: CategoryView[] = []; handlers: UserRef[] = [];

  get pages() { return Math.max(1, Math.ceil(this.total / this.size)); }

  constructor() {
    const qp = this.route.snapshot.queryParamMap;
    if (qp.get('status')) { this.status = Number(qp.get('status')); this.hideClosed = false; }
    // reload whenever the project selector changes
    effect(() => {
      const pid = this.auth.projectId();
      untracked(() => {
        this.categoryId = null; this.handlerId = null; this.categories = []; this.handlers = [];
        if (pid) {
          this.api.categories(pid).subscribe(c => this.categories = c);
          this.api.assignable(pid).subscribe(u => this.handlers = u);
        }
        this.page = 0; this.load();
      });
    });
  }

  load() {
    this.api.issues({
      projectId: this.auth.projectId(), status: this.status !== null ? [this.status] : [], handlerId: this.handlerId,
      priority: this.priority, severity: this.severity, categoryId: this.categoryId, q: this.q,
      hideClosed: this.hideClosed, sortBy: this.sortBy, dir: this.dir, page: this.page, size: this.size
    }).subscribe({
      next: r => { this.items = r.items; this.total = r.total; this.error = ''; }, error: e => this.error = errMsg(e)
    });
  }
  reload() { this.page = 0; this.load(); }
  reset() { this.q = ''; this.status = this.priority = this.severity = this.categoryId = this.handlerId = null; this.hideClosed = true; this.reload(); }
  go(p: number) { this.page = Math.max(0, p); this.load(); }
  sort(f: string) { if (this.sortBy === f) this.dir = this.dir === 'asc' ? 'desc' : 'asc'; else { this.sortBy = f; this.dir = 'desc'; } this.reload(); }
  arrow(f: string) { return this.sortBy === f ? (this.dir === 'asc' ? ' ▲' : ' ▼') : ''; }
}
