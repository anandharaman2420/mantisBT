import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { BulkFailure, CategoryView, UserRef } from '../core/models';

/** "With selected issues: [action] [value] [Apply]" – port of Mantis bug_actiongroup_page. */
@Component({
  selector: 'app-bulk-bar', standalone: true, imports: [FormsModule],
  template: `
  <div class="panel" style="background:#ebf8ff;border-color:#bee3f8">
    <div class="row" style="align-items:flex-end">
      <div><b>{{ ids.length }} selected</b> <a href="#" (click)="cleared.emit(); $event.preventDefault()" class="muted">clear</a></div>
      <div><label>Action</label>
        <select [(ngModel)]="action" (ngModelChange)="reset()" style="min-width:190px">
          <option value="">– choose –</option>
          @for (a of actions(); track a.key) { <option [value]="a.key">{{ a.label }}</option> }
        </select></div>

      @switch (action) {
        @case ('assign') { <div><label>Assign to</label><select [(ngModel)]="longValue"><option [ngValue]="null">[nobody] (unassign)</option>
          @for (u of handlers; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div> }
        @case ('status') {
          <div><label>New status</label><select [(ngModel)]="intValue">@for (o of auth.options('status'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          @if (intValue !== null && intValue >= 80) { <div><label>Resolution</label><select [(ngModel)]="resolution"><option [ngValue]="null">(auto)</option>
            @for (o of auth.options('resolution'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div> }
          <div class="grow"><label>Note (optional)</label><input [(ngModel)]="text"></div> }
        @case ('priority') { <div><label>Priority</label><select [(ngModel)]="intValue">@for (o of auth.options('priority'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div> }
        @case ('severity') { <div><label>Severity</label><select [(ngModel)]="intValue">@for (o of auth.options('severity'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div> }
        @case ('view_state') { <div><label>Visibility</label><select [(ngModel)]="intValue">@for (o of auth.options('viewState'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div> }
        @case ('sticky') { <div><label>Sticky</label><select [(ngModel)]="intValue"><option [ngValue]="1">yes</option><option [ngValue]="0">no</option></select></div> }
        @case ('category') { <div><label>Category</label><select [(ngModel)]="longValue"><option [ngValue]="null">– select –</option>
          @for (c of categories; track c.id) { <option [ngValue]="c.id">{{ c.name }}</option> }</select></div> }
        @case ('fixed_in_version') { <div><label>Fixed in version (empty = clear)</label><input [(ngModel)]="text"></div> }
        @case ('target_version') { <div><label>Target version (empty = clear)</label><input [(ngModel)]="text"></div> }
        @case ('add_tags') { <div class="grow"><label>Tags to add (comma separated)</label><input [(ngModel)]="text"></div> }
        @case ('remove_tags') { <div class="grow"><label>Tags to remove (comma separated)</label><input [(ngModel)]="text"></div> }
        @case ('add_note') { <div class="grow" style="min-width:260px"><label>Note</label><input [(ngModel)]="text"></div>
          <div><label>&nbsp;</label><label style="display:flex;gap:5px;align-items:center;font-weight:400"><input type="checkbox" [(ngModel)]="privateNote"> private</label></div> }
        @case ('move') { <div><label>Move to project</label><select [(ngModel)]="longValue"><option [ngValue]="null">– select –</option>
          @for (p of targetProjects(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }</select></div> }
        @case ('copy') { <div><label>Copy to project</label><select [(ngModel)]="longValue"><option [ngValue]="null">– select –</option>
          @for (p of copyProjects(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }</select></div> }
      }
      @if (action) { <div><label>&nbsp;</label><button class="primary" (click)="apply()" [disabled]="busy || !valid()">Apply to {{ ids.length }}</button></div> }
    </div>
    @if (action === 'copy') { <div class="muted" style="margin-top:6px">Copies get status "new", you as reporter, and a "related to" link to the original.</div> }
    @if (message) { <div class="ok" style="margin:10px 0 0">{{ message }}</div> }
    @if (error) { <div class="err" style="margin:10px 0 0">{{ error }}</div> }
    @if (failures.length) {
      <div class="err" style="margin:10px 0 0"><b>{{ failures.length }} issue(s) could not be changed:</b>
        @for (f of failures; track f.issueId) { <div>#{{ f.issueId }}: {{ f.message }}</div> }</div>
    }
  </div>`
})
export class BulkBarComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);

  @Input({ required: true }) ids: number[] = [];
  @Input() projectId: number | null = null;
  @Input() categories: CategoryView[] = [];
  @Input() handlers: UserRef[] = [];
  /** fired after the server answered, so the list can reload */
  @Output() done = new EventEmitter<void>();
  @Output() cleared = new EventEmitter<void>();

  action = ''; intValue: number | null = null; longValue: number | null = null; text = '';
  resolution: number | null = null; privateNote = false;
  busy = false; message = ''; error = ''; failures: BulkFailure[] = [];

  actions() {
    const a = [
      { key: 'status', label: 'Change status / resolve / close' }, { key: 'priority', label: 'Update priority' },
      { key: 'severity', label: 'Update severity' }, { key: 'view_state', label: 'Update visibility (public / private)' },
      { key: 'fixed_in_version', label: 'Set "fixed in" version' }, { key: 'target_version', label: 'Set target version' },
      { key: 'sticky', label: 'Set sticky' }, { key: 'add_tags', label: 'Add tags' }, { key: 'remove_tags', label: 'Remove tags' },
      { key: 'add_note', label: 'Add note' }, { key: 'move', label: 'Move to project' }, { key: 'copy', label: 'Copy to project' },
      { key: 'delete', label: 'Delete' }
    ];
    // assignee list / categories are only known when one project is selected
    if (this.projectId) a.unshift({ key: 'assign', label: 'Assign to' }, { key: 'category', label: 'Update category' });
    return a;
  }
  targetProjects() { return this.auth.projects().filter(p => p.myAccess >= 25 && p.id !== this.projectId); }
  copyProjects() { return this.auth.projects().filter(p => p.myAccess >= 25); }

  reset() {
    this.text = ''; this.longValue = null; this.resolution = null; this.privateNote = false;
    this.intValue = this.action === 'status' ? 50 : this.action === 'priority' ? 30 : this.action === 'severity' ? 50
      : this.action === 'view_state' ? 10 : this.action === 'sticky' ? 1 : null;
    this.message = this.error = ''; this.failures = [];
  }

  valid(): boolean {
    switch (this.action) {
      case 'assign': case 'delete': return true;
      case 'category': case 'move': case 'copy': return this.longValue !== null;
      case 'fixed_in_version': case 'target_version': return true;
      case 'add_tags': case 'remove_tags': case 'add_note': return this.text.trim().length > 0;
      case '': return false;
      default: return this.intValue !== null;
    }
  }

  apply() {
    const n = this.ids.length;
    if (this.action === 'delete' && !confirm(`Delete ${n} issue(s) permanently?`)) return;
    if (this.action === 'move' && !confirm(`Move ${n} issue(s) to another project?`)) return;
    const body: any = { ids: this.ids, action: this.action };
    switch (this.action) {
      case 'assign': case 'category': case 'move': case 'copy': body.longValue = this.longValue; break;
      case 'status': body.intValue = this.intValue; body.resolution = this.intValue !== null && this.intValue >= 80 ? this.resolution : null; body.text = this.text; break;
      case 'add_note': body.text = this.text; body.intValue = this.privateNote ? 50 : 10; break;
      case 'fixed_in_version': case 'target_version': case 'add_tags': case 'remove_tags': body.text = this.text; break;
      case 'delete': break;
      default: body.intValue = this.intValue;
    }
    this.busy = true; this.message = this.error = ''; this.failures = [];
    this.api.bulk(body).subscribe({
      next: r => {
        this.busy = false; this.failures = r.failures;
        this.message = `${r.succeeded} of ${n} issue(s) updated.`;
        this.done.emit();
      },
      error: e => { this.busy = false; this.error = errMsg(e); }
    });
  }
}
