import { Component, Input, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { ACCESS, CategoryView, IssueForm, IssueView, TagView, UserRef, VersionView } from '../core/models';

/** Used for both "Report Issue" (/report) and "Edit Issue" (/issues/:id/edit). */
@Component({
  selector: 'app-issue-form', standalone: true, imports: [FormsModule, RouterLink],
  template: `
  <h1>{{ editing ? 'Edit Issue ' + id : 'Report Issue' }}</h1>
  @if (error) { <div class="err">{{ error }}</div> }
  <div class="panel">
    <div class="grid2">
      <div>
        <div class="m"><label>Project *</label>
          <select [(ngModel)]="m.projectId" (ngModelChange)="projectChanged()" [disabled]="editing">
            <option [ngValue]="null">– select –</option>
            @for (p of auth.projects(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }
          </select></div>
        <div class="m"><label>Category</label>
          <select [(ngModel)]="m.categoryId"><option [ngValue]="null">– none –</option>
            @for (c of categories; track c.id) { <option [ngValue]="c.id">{{ c.name }}</option> }</select></div>
        <div class="row m">
          <div class="grow"><label>Reproducibility</label><select [(ngModel)]="m.reproducibility">@for (o of auth.options('reproducibility'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          <div class="grow"><label>Severity</label><select [(ngModel)]="m.severity">@for (o of auth.options('severity'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          <div class="grow"><label>Priority</label><select [(ngModel)]="m.priority">@for (o of auth.options('priority'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        </div>
        @if (canAssign()) {
          <div class="m"><label>Assign to</label>
            <select [(ngModel)]="m.handlerId"><option [ngValue]="null">[nobody]</option>
              @for (u of handlers; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div>
        }
      </div>
      <div>
        <div class="row m">
          <div class="grow"><label>Platform</label><input [(ngModel)]="m.platform"></div>
          <div class="grow"><label>OS</label><input [(ngModel)]="m.os"></div>
          <div class="grow"><label>OS version</label><input [(ngModel)]="m.osBuild"></div>
        </div>
        <div class="row m">
          <div class="grow"><label>Product version</label><input [(ngModel)]="m.version" list="vers"></div>
          <div class="grow"><label>Product build</label><input [(ngModel)]="m.build"></div>
        </div>
        <datalist id="vers">@for (v of versions; track v.id) { <option [value]="v.version"></option> }</datalist>
        <div class="m"><label>Tags (comma separated)</label><input [(ngModel)]="tagsText" list="taglist">
          <datalist id="taglist">@for (t of allTags; track t.id) { <option [value]="t.name"></option> }</datalist></div>
        <div class="m"><label>Visibility</label>
          <select [(ngModel)]="m.viewState">@for (o of auth.options('viewState'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      </div>
    </div>
    <div class="m"><label>Summary * (max 128)</label><input [(ngModel)]="m.summary" maxlength="128"></div>
    <div class="m"><label>Description *</label><textarea [(ngModel)]="m.description" rows="6"></textarea></div>
    <div class="m"><label>Steps to reproduce</label><textarea [(ngModel)]="m.stepsToReproduce"></textarea></div>
    <div class="m"><label>Additional information</label><textarea [(ngModel)]="m.additionalInformation"></textarea></div>
    @if (editing && issue?.canChangeStatus) {
      <h2>Workflow</h2>
      <div class="row m">
        <div class="grow"><label>Status</label><select [(ngModel)]="m.status">@for (o of auth.options('status'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        <div class="grow"><label>Resolution</label><select [(ngModel)]="m.resolution">@for (o of auth.options('resolution'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        <div class="grow"><label>Fixed in version</label><input [(ngModel)]="m.fixedInVersion" list="vers"></div>
        <div class="grow"><label>Target version</label><input [(ngModel)]="m.targetVersion" list="vers"></div>
      </div>
    }
    <div class="row"><button class="primary" (click)="save()" [disabled]="busy">{{ editing ? 'Update Issue' : 'Submit Issue' }}</button>
      <a [routerLink]="editing ? ['/issues', id] : ['/issues']"><button>Cancel</button></a></div>
  </div>`,
  styles: [`.m { margin-bottom: 12px; }`]
})
export class IssueFormComponent implements OnInit {
  /** bound from the route parameter (withComponentInputBinding) */
  @Input() id?: string;
  auth = inject(AuthService);
  private api = inject(ApiService);
  private router = inject(Router);

  editing = false; busy = false; error = '';
  issue: IssueView | null = null;
  original: any = {};
  m: any = { projectId: null, categoryId: null, reproducibility: 10, severity: 50, priority: 30, handlerId: null, viewState: 10,
    summary: '', description: '', stepsToReproduce: '', additionalInformation: '', platform: '', os: '', osBuild: '',
    version: '', build: '', status: 10, resolution: 10, fixedInVersion: '', targetVersion: '' };
  tagsText = '';
  categories: CategoryView[] = []; versions: VersionView[] = []; handlers: UserRef[] = []; allTags: TagView[] = [];

  ngOnInit() {
    this.api.tags().subscribe(t => this.allTags = t);
    if (this.id) {
      this.editing = true;
      this.api.issue(Number(this.id)).subscribe({
        next: i => {
          this.issue = i;
          this.m = { projectId: i.projectId, categoryId: i.categoryId, reproducibility: i.reproducibility, severity: i.severity,
            priority: i.priority, handlerId: i.handler?.id ?? null, viewState: i.viewState, summary: i.summary,
            description: i.description, stepsToReproduce: i.stepsToReproduce, additionalInformation: i.additionalInformation,
            platform: i.platform, os: i.os, osBuild: i.osBuild, version: i.version, build: i.build, status: i.status,
            resolution: i.resolution, fixedInVersion: i.fixedInVersion, targetVersion: i.targetVersion };
          this.tagsText = i.tags.join(', ');
          this.original = { ...this.m, tags: [...i.tags] };
          this.loadProject();
        },
        error: e => this.error = errMsg(e)
      });
    } else {
      this.m.projectId = this.auth.projectId();
      this.loadProject();
    }
  }

  projectChanged() { this.m.categoryId = null; this.m.handlerId = null; this.loadProject(); }

  private loadProject() {
    const pid = this.m.projectId;
    this.categories = []; this.versions = []; this.handlers = [];
    if (!pid) return;
    this.api.categories(pid).subscribe(c => {
      this.categories = c;
      if (!this.editing && this.m.categoryId === null && c.length) this.m.categoryId = c[0].id;
    });
    this.api.versions(pid).subscribe(v => this.versions = v);
    if (this.canAssign()) this.api.assignable(pid).subscribe(u => this.handlers = u);
  }

  canAssign(): boolean {
    const p = this.auth.projects().find(x => x.id === this.m.projectId);
    return !!p && p.myAccess >= ACCESS.DEVELOPER;
  }

  save() {
    this.error = '';
    if (!this.m.projectId) { this.error = 'Select a project'; return; }
    if (!this.m.summary?.trim() || !this.m.description?.trim()) { this.error = 'Summary and description are required'; return; }
    const tags = this.tagsText.split(',').map(s => s.trim()).filter(Boolean);
    this.busy = true;
    if (!this.editing) {
      const body: IssueForm = { ...this.m, tags };
      if (!this.canAssign() || body.handlerId === null) delete body.handlerId;
      this.api.createIssue(body).subscribe({
        next: i => this.router.navigate(['/issues', i.id]), error: e => { this.error = errMsg(e); this.busy = false; }
      });
      return;
    }
    // edit: send only what changed, so lower access levels are not rejected for untouched developer fields
    const diff: any = {};
    for (const k of Object.keys(this.m)) if (this.m[k] !== this.original[k] && k !== 'projectId') diff[k] = this.m[k];
    if ('handlerId' in diff && diff.handlerId === null) { diff.clearHandler = true; delete diff.handlerId; }
    if (JSON.stringify(tags) !== JSON.stringify(this.original.tags)) diff.tags = tags;
    this.api.updateIssue(Number(this.id), diff).subscribe({
      next: i => this.router.navigate(['/issues', i.id]), error: e => { this.error = errMsg(e); this.busy = false; }
    });
  }
}
