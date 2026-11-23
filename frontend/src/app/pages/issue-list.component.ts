import { Component, effect, inject, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { BulkBarComponent } from '../shared/bulk-bar.component';
import { CategoryView, FilterCriteria, IssueQuery, IssueSummary, SavedFilterView, UserRef } from '../core/models';

@Component({
  selector: 'app-issue-list', standalone: true, imports: [FormsModule, RouterLink, DatePipe, BulkBarComponent],
  template: `
  <h1>View Issues <span class="muted">({{ total }})</span></h1>
  <div class="panel">
    <div class="row" style="align-items:center;margin-bottom:12px;justify-content:space-between;flex-wrap:wrap;gap:8px">
      <div class="row" style="align-items:center;gap:8px;flex-wrap:wrap">
        <label style="margin:0;font-weight:600">Saved filters:</label>
        <select [ngModel]="selectedFilterId" (ngModelChange)="onSelectSavedFilter($event)" style="min-width:180px">
          <option [ngValue]="null">– Custom filter –</option>
          @for (f of savedFilters; track f.id) {
            <option [ngValue]="f.id">{{ f.name }}{{ f.isPublic ? ' (shared)' : '' }}{{ f.projectName ? ' [' + f.projectName + ']' : '' }}</option>
          }
        </select>
        <button class="small" (click)="toggleSaveDialog()">{{ showSaveDialog ? 'Cancel' : 'Save filter…' }}</button>
        @if (selectedFilter && canDeleteSelectedFilter()) {
          <button class="small danger" (click)="deleteCurrentFilter()">Delete filter</button>
        }
      </div>
      <div class="row" style="gap:8px">
        <button class="small" (click)="export('csv')" [disabled]="exporting">📥 Export CSV</button>
        <button class="small" (click)="export('excel')" [disabled]="exporting">📊 Export Excel</button>
      </div>
    </div>

    @if (showSaveDialog) {
      <div class="row" style="align-items:center;gap:10px;margin-bottom:12px;padding:8px 12px;background:#ebf8ff;border:1px solid #bee3f8;border-radius:4px;flex-wrap:wrap">
        <div class="grow" style="min-width:180px"><label style="margin:0 0 2px 0;font-size:12px">Filter name</label><input [(ngModel)]="newFilterName" placeholder="e.g. High Priority Open"></div>
        <label style="display:flex;gap:5px;align-items:center;font-weight:400;margin-top:16px"><input type="checkbox" [(ngModel)]="newFilterPublic"> Public (shared)</label>
        <div style="margin-top:16px">
          <button class="primary small" (click)="saveFilter()" [disabled]="!newFilterName.trim()">Save</button>
          <button class="small" (click)="showSaveDialog = false">Cancel</button>
        </div>
      </div>
    }

    <div class="row">
      <div class="grow" style="min-width:220px"><label>Search (summary, description, id)</label><input [(ngModel)]="q" (keyup.enter)="reload()" placeholder="Search…"></div>
      <div class="msel"><label>Status</label>
        <details #statusMenu><summary>{{ statusSummary() }}</summary>
          <div class="menu">
            @for (o of auth.options('status'); track o.value) {
              <label class="opt"><input type="checkbox" [checked]="statuses.includes(o.value)" (change)="toggleStatus(o.value)"> <span class="chip s{{ o.value }}">{{ o.label }}</span></label>
            }
            <a href="#" (click)="statuses = []; $event.preventDefault()">clear (any status)</a>
          </div>
        </details></div>
      <div><label>Priority</label><select [(ngModel)]="priority"><option [ngValue]="null">any</option>
        @for (o of auth.options('priority'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      <div><label>Severity</label><select [(ngModel)]="severity"><option [ngValue]="null">any</option>
        @for (o of auth.options('severity'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      <div><label>Tag</label><input [(ngModel)]="tag" (keyup.enter)="reload()" style="width:120px" placeholder="any"></div>
      @if (reporters.length) {
        <div><label>Reporter</label><select [(ngModel)]="reporterId"><option [ngValue]="null">anyone</option>
          @for (u of reporters; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div>
      } @else if (reporterId) {
        <div><label>Reporter</label><span class="tag">#{{ reporterId }} <a href="#" (click)="reporterId = null; $event.preventDefault()">×</a></span></div>
      }
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
  @if (selectedIds.length) {
    <app-bulk-bar [ids]="selectedIds" [projectId]="auth.projectId()" [categories]="categories" [handlers]="handlers"
                  (done)="load()" (cleared)="selectedIds = []" />
  }
  <div class="panel" style="padding:0;overflow:auto">
    <table>
      <thead><tr>
        <th style="width:28px"><input type="checkbox" [checked]="allSelected()" (change)="toggleAll()" title="select all on this page"></th>
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
            <td (click)="$event.stopPropagation()"><input type="checkbox" [checked]="isSel(i.id)" (change)="toggle(i.id)"></td>
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
        } @empty { <tr><td colspan="10" class="muted">No issues match the filter.</td></tr> }
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
  q = ''; statuses: number[] = []; tag = ''; reporterId: number | null = null; reporters: UserRef[] = [];
  priority: number | null = null; severity: number | null = null;
  categoryId: number | null = null; handlerId: number | null = null; hideClosed = true;
  sortBy = 'lastUpdated'; dir = 'desc';
  categories: CategoryView[] = []; handlers: UserRef[] = [];

  savedFilters: SavedFilterView[] = [];
  selectedFilterId: number | null = null;
  showSaveDialog = false;
  newFilterName = '';
  newFilterPublic = false;
  exporting = false;
  /** ids ticked in the first column (bulk actions) */
  selectedIds: number[] = [];
  isSel(id: number) { return this.selectedIds.includes(id); }
  toggle(id: number) { this.selectedIds = this.isSel(id) ? this.selectedIds.filter(x => x !== id) : [...this.selectedIds, id]; }
  allSelected() { return this.items.length > 0 && this.items.every(i => this.isSel(i.id)); }
  toggleAll() { this.selectedIds = this.allSelected() ? [] : this.items.map(i => i.id); }

  get pages() { return Math.max(1, Math.ceil(this.total / this.size)); }

  get selectedFilter(): SavedFilterView | undefined {
    return this.savedFilters.find(f => f.id === this.selectedFilterId);
  }

  canDeleteSelectedFilter(): boolean {
    const f = this.selectedFilter;
    if (!f) return false;
    return this.auth.isAdmin() || f.user.id === this.auth.user()?.id;
  }

  constructor() {
    const qp = this.route.snapshot.queryParamMap;
    const st = qp.getAll('status').map(Number).filter(n => !isNaN(n));
    if (st.length) { this.statuses = st; this.hideClosed = false; }
    if (qp.get('tag')) this.tag = qp.get('tag')!;                 // links from the issue page: /issues?tag=x
    if (qp.get('reporterId')) this.reporterId = Number(qp.get('reporterId'));
    this.api.userLookup().subscribe({ next: u => this.reporters = u, error: () => {} });   // developers only; others keep the id chip
    // reload whenever the project selector changes
    effect(() => {
      const pid = this.auth.projectId();
      untracked(() => {
        this.categoryId = null; this.handlerId = null; this.categories = []; this.handlers = [];
        if (pid) {
          this.api.categories(pid).subscribe(c => this.categories = c);
          this.api.assignable(pid).subscribe(u => this.handlers = u);
        }
        this.loadSavedFilters();
        this.page = 0; this.load();
      });
    });
  }

  loadSavedFilters() {
    this.api.savedFilters(this.auth.projectId()).subscribe({
      next: fs => this.savedFilters = fs,
      error: () => {}
    });
  }

  onSelectSavedFilter(id: number | null) {
    this.selectedFilterId = id;
    if (!id) return;
    const f = this.savedFilters.find(x => x.id === id);
    if (!f || !f.criteria) return;
    const c = f.criteria;
    this.q = c.q ?? '';
    this.statuses = c.status ? [...c.status] : [];
    this.reporterId = c.reporterId ?? null;
    this.tag = c.tag ?? '';
    this.priority = c.priority ?? null;
    this.severity = c.severity ?? null;
    this.categoryId = c.categoryId ?? null;
    this.handlerId = c.handlerId ?? null;
    this.hideClosed = c.hideClosed ?? true;
    if (c.sortBy) this.sortBy = c.sortBy;
    if (c.dir) this.dir = c.dir;
    this.page = 0;
    this.load();
  }

  toggleSaveDialog() {
    this.showSaveDialog = !this.showSaveDialog;
    if (this.showSaveDialog && !this.newFilterName) {
      this.newFilterName = 'Filter ' + new Date().toISOString().slice(0, 10);
    }
  }

  saveFilter() {
    if (!this.newFilterName.trim()) return;
    const criteria: FilterCriteria = this.criteria();
    this.api.createFilter({
      name: this.newFilterName.trim(),
      projectId: this.auth.projectId(),
      isPublic: this.newFilterPublic,
      criteria
    }).subscribe({
      next: saved => {
        this.showSaveDialog = false;
        this.newFilterName = '';
        this.newFilterPublic = false;
        this.selectedFilterId = saved.id;
        this.loadSavedFilters();
      },
      error: e => this.error = errMsg(e)
    });
  }

  deleteCurrentFilter() {
    if (!this.selectedFilterId) return;
    if (!confirm('Delete saved filter "' + (this.selectedFilter?.name || '') + '"?')) return;
    this.api.deleteFilter(this.selectedFilterId).subscribe({
      next: () => {
        this.selectedFilterId = null;
        this.loadSavedFilters();
      },
      error: e => this.error = errMsg(e)
    });
  }

  export(type: 'csv' | 'excel') {
    this.exporting = true;
    const q: IssueQuery = this.criteria();
    const call = type === 'csv' ? this.api.exportCsv(q) : this.api.exportExcel(q);
    call.subscribe({
      next: blob => {
        this.exporting = false;
        const ext = type === 'csv' ? 'csv' : 'xls';
        const filename = `issues_${new Date().toISOString().slice(0, 10)}.${ext}`;
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        window.URL.revokeObjectURL(url);
      },
      error: e => {
        this.exporting = false;
        this.error = errMsg(e);
      }
    });
  }

  load() {
    this.api.issues({ ...this.criteria(), page: this.page, size: this.size }).subscribe({
      next: r => { this.items = r.items; this.total = r.total; this.error = ''; this.selectedIds = []; }, error: e => this.error = errMsg(e)
    });
  }
  /** the single source for list, export and saved filters (all criteria, incl. several statuses, tag, reporter) */
  private criteria(): FilterCriteria {
    return {
      projectId: this.auth.projectId(), status: [...this.statuses], handlerId: this.handlerId, reporterId: this.reporterId,
      priority: this.priority, severity: this.severity, categoryId: this.categoryId, q: this.q,
      tag: this.tag.trim() || undefined, hideClosed: this.hideClosed, sortBy: this.sortBy, dir: this.dir
    };
  }
  toggleStatus(v: number) { this.statuses = this.statuses.includes(v) ? this.statuses.filter(x => x !== v) : [...this.statuses, v]; }
  statusSummary() {
    if (!this.statuses.length) return 'any';
    return this.statuses.length <= 2 ? this.statuses.map(s => this.auth.label('status', s)).join(', ') : `${this.statuses.length} selected`;
  }
  reload() { this.page = 0; this.load(); }
  reset() {
    this.selectedFilterId = null;
    this.q = ''; this.statuses = []; this.tag = ''; this.priority = this.severity = this.categoryId = this.handlerId = this.reporterId = null;
    this.hideClosed = true;
    this.reload();
  }
  go(p: number) { this.page = Math.max(0, p); this.load(); }
  sort(f: string) { if (this.sortBy === f) this.dir = this.dir === 'asc' ? 'desc' : 'asc'; else { this.sortBy = f; this.dir = 'desc'; } this.reload(); }
  arrow(f: string) { return this.sortBy === f ? (this.dir === 'asc' ? ' ▲' : ' ▼') : ''; }
}
