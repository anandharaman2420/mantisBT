import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { CF_TYPES, CustomFieldView, ProjectView } from '../core/models';

@Component({
  selector: 'app-custom-fields', standalone: true, imports: [FormsModule],
  template: `
  <h1>Custom Fields</h1>
  @if (error) { <div class="err">{{ error }}</div> }
  @if (auth.isAdmin()) {
    <div class="panel"><h2>{{ f.id ? 'Edit "' + f.name + '"' : 'Create custom field' }}</h2>
      <div class="row" style="margin-bottom:10px">
        <div class="grow"><label>Name</label><input [(ngModel)]="f.name" maxlength="64"></div>
        <div><label>Type</label><select [(ngModel)]="f.type">@for (t of types; track t.value) { <option [ngValue]="t.value">{{ t.label }}</option> }</select></div>
        <div><label>Visible to (min. level)</label><select [(ngModel)]="f.accessLevelR">@for (o of levels(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        <div><label>Editable by (min. level)</label><select [(ngModel)]="f.accessLevelRw">@for (o of levels(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      </div>
      @if (needsOptions()) { <div style="margin-bottom:10px"><label>Possible values (separated by |)</label><input [(ngModel)]="f.possibleValues" placeholder="red|green|blue"></div> }
      <div class="row" style="margin-bottom:10px">
        <div class="grow"><label>Default value</label><input [(ngModel)]="f.defaultValue"></div>
        @if (f.type === 0 || f.type === 10) { <div class="grow"><label>Validation (regular expression)</label><input [(ngModel)]="f.validRegexp" placeholder="e.g. [A-Z]{3}-\\d+"></div> }
        <div><label>{{ f.type === 1 || f.type === 2 ? 'Min value' : 'Min length' }}</label><input type="number" [(ngModel)]="f.lengthMin" style="width:100px"></div>
        <div><label>{{ f.type === 1 || f.type === 2 ? 'Max value' : 'Max length' }}</label><input type="number" [(ngModel)]="f.lengthMax" style="width:100px"></div>
      </div>
      <div class="row" style="margin-bottom:12px">
        @for (o of flags; track o.key) { <label style="display:flex;gap:5px;align-items:center;font-weight:400;font-size:14px;color:inherit"><input type="checkbox" [(ngModel)]="f[o.key]"> {{ o.label }}</label> }
      </div>
      <div style="margin-bottom:14px;padding:10px;background:#f7fafc;border:1px solid #e2e8f0;border-radius:4px">
        <div class="row" style="align-items:center;margin-bottom:6px">
          <label style="margin:0;font-weight:600">Link to projects:</label>
          <button class="small" type="button" (click)="selectAllProjects()">Select all</button>
          <button class="small" type="button" (click)="clearProjects()">Clear</button>
        </div>
        <div class="row" style="flex-wrap:wrap;gap:12px">
          @for (p of allProjects; track p.id) {
            <label style="display:flex;gap:5px;align-items:center;font-weight:400;font-size:14px;color:inherit">
              <input type="checkbox" [checked]="isProjectSelected(p.id)" (change)="toggleProject(p.id)"> {{ p.name }}
            </label>
          } @empty { <span class="muted">No projects found.</span> }
        </div>
      </div>
      <button class="primary" (click)="save()">{{ f.id ? 'Update' : 'Create' }}</button> @if (f.id) { <button (click)="reset()">Cancel</button> }
    </div>
  }
  <div class="panel" style="padding:0;overflow:auto"><table>
    <thead><tr><th>Name</th><th>Type</th><th>Visible ≥</th><th>Editable ≥</th><th>Required (report / update)</th><th>Linked Projects</th>@if (auth.isAdmin()) { <th></th> }</tr></thead>
    <tbody>@for (c of list; track c.id) {
      <tr><td>{{ c.name }}</td><td>{{ typeLabel(c.type) }}</td><td>{{ auth.label('access', c.accessLevelR) }}</td><td>{{ auth.label('access', c.accessLevelRw) }}</td>
        <td>{{ c.requireReport ? 'yes' : 'no' }} / {{ c.requireUpdate ? 'yes' : 'no' }}</td>
        <td>{{ linkedProjectsText(c) }}</td>
        @if (auth.isAdmin()) {
          <td class="right nowrap"><button class="small" (click)="edit(c)">edit</button> <button class="small danger" (click)="remove(c)">delete</button></td>
        }
      </tr>
    } @empty { <tr><td [attr.colspan]="auth.isAdmin() ? 7 : 6" class="muted">No custom fields yet.</td></tr> }</tbody>
  </table></div>`
})
export class CustomFieldsComponent {
  private api = inject(ApiService);
  auth = inject(AuthService);
  types = CF_TYPES;
  list: CustomFieldView[] = []; error = '';
  allProjects: ProjectView[] = [];
  f: any = this.blank();
  flags = [
    { key: 'requireReport', label: 'required when reporting' }, { key: 'requireUpdate', label: 'required when updating' },
    { key: 'displayReport', label: 'show on report form' }, { key: 'displayUpdate', label: 'show on update form' },
    { key: 'displayResolved', label: 'show when resolving' }, { key: 'displayClosed', label: 'show when closing' }
  ];

  constructor() {
    this.load();
    this.api.projects().subscribe(p => {
      this.allProjects = p;
      if (!this.f.id && (!this.f.projectIds || !this.f.projectIds.length)) {
        this.f.projectIds = p.map(x => x.id);
      }
    });
  }
  blank() {
    return { id: null, name: '', type: 0, possibleValues: '', defaultValue: '', validRegexp: '', accessLevelR: 10, accessLevelRw: 25,
      lengthMin: 0, lengthMax: 0, requireReport: false, requireUpdate: false, displayReport: true, displayUpdate: true,
      displayResolved: false, displayClosed: false, filterBy: true, projectIds: this.allProjects.map(p => p.id) };
  }
  levels() { return this.auth.options('access').filter(o => o.value >= 10); }
  needsOptions() { return [3, 5, 6, 7, 9].includes(this.f.type); }
  typeLabel(t: number) { return this.types.find(x => x.value === t)?.label ?? String(t); }
  load() { this.api.customFields().subscribe(l => this.list = l); }
  reset() { this.f = this.blank(); }
  edit(c: CustomFieldView) {
    this.f = { ...c, projectIds: c.projectIds ? [...c.projectIds] : [] };
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  isProjectSelected(id: number): boolean { return (this.f.projectIds || []).includes(id); }
  toggleProject(id: number) {
    const pids: number[] = this.f.projectIds || [];
    this.f.projectIds = pids.includes(id) ? pids.filter(x => x !== id) : [...pids, id];
  }
  selectAllProjects() { this.f.projectIds = this.allProjects.map(p => p.id); }
  clearProjects() { this.f.projectIds = []; }
  linkedProjectsText(c: CustomFieldView): string {
    if (!c.projectIds || c.projectIds.length === 0) return 'None';
    if (this.allProjects.length && c.projectIds.length === this.allProjects.length) return 'All projects';
    return c.projectIds.map(id => this.allProjects.find(p => p.id === id)?.name ?? ('Project #' + id)).join(', ');
  }
  save() {
    const call = this.f.id ? this.api.updateCustomField(this.f.id, this.f) : this.api.createCustomField(this.f);
    call.subscribe({ next: () => { this.error = ''; this.reset(); this.load(); }, error: e => this.error = errMsg(e) });
  }
  remove(c: CustomFieldView) {
    if (!confirm('Delete custom field "' + c.name + '" and all its values?')) return;
    this.api.deleteCustomField(c.id).subscribe({ next: () => this.load(), error: e => this.error = errMsg(e) });
  }
}
