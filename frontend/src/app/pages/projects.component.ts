import { Component, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { ACCESS, CategoryView, CustomFieldView, MemberView, NewsView, ProjectView, UserRef, VersionView } from '../core/models';

@Component({
  selector: 'app-projects', standalone: true, imports: [FormsModule, DatePipe],
  template: `
  <h1>Manage Projects</h1>
  @if (error) { <div class="err">{{ error }}</div> }
  <div class="grid2" style="grid-template-columns: 1fr 1.6fr">
    <div class="panel"><h2>Projects</h2>
      <table><thead><tr><th>Name</th><th>Status</th><th>Visibility</th><th></th></tr></thead><tbody>
        @for (o of auth.projectOptions(); track o.project.id) {
          <tr [style.background]="sel?.id === o.project.id ? '#ebf4ff' : ''"><td>{{ o.label }}</td><td>{{ auth.label('projectStatus', o.project.status) }}</td>
            <td>{{ auth.label('viewState', o.project.viewState) }}</td>
            <td class="right">@if (o.project.myAccess >= MANAGER) { <button class="small" (click)="select(o.project)">manage</button> }</td></tr>
        }
      </tbody></table>
      @if (auth.isAdmin()) {
        <h3 style="margin-top:16px">Create project</h3>
        <div class="row"><div class="grow"><label>Name</label><input [(ngModel)]="np.name"></div>
          <div><label>Status</label><select [(ngModel)]="np.status">@for (o of auth.options('projectStatus'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          <div><label>Visibility</label><select [(ngModel)]="np.viewState">@for (o of auth.options('viewState'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          <div><label>Parent project</label><select [(ngModel)]="np.parentId"><option [ngValue]="null">– none (top level) –</option>
            @for (o of auth.projectOptions(); track o.project.id) { <option [ngValue]="o.project.id">{{ o.label }}</option> }</select></div>
          <div><label>&nbsp;</label><button class="primary" (click)="create()">Create</button></div></div>
      }
    </div>

    @if (sel) {
      <div class="panel"><h2>{{ sel.name }}</h2>
        <div class="tabs">@for (t of tabs; track t) { <button [class.active]="tab === t" (click)="setTab(t)">{{ t }}</button> }</div>

        @if (tab === 'Details') {
          <div class="row" style="margin-bottom:10px"><div class="grow"><label>Name</label><input [(ngModel)]="edit.name"></div>
            <div><label>Status</label><select [(ngModel)]="edit.status">@for (o of auth.options('projectStatus'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
            <div><label>Visibility</label><select [(ngModel)]="edit.viewState">@for (o of auth.options('viewState'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div></div>
          <div style="margin-bottom:10px"><label>Description</label><textarea [(ngModel)]="edit.description" rows="3"></textarea></div>
          <div class="row" style="margin-bottom:10px"><div><label>Parent project {{ auth.isAdmin() ? '' : '(administrator only)' }}</label>
            <select [(ngModel)]="edit.parentId" [disabled]="!auth.isAdmin()"><option [ngValue]="null">– none (top level) –</option>
              @for (o of parentChoices(); track o.project.id) { <option [ngValue]="o.project.id">{{ o.label }}</option> }</select></div>
            @if (edit.parentId) { <label style="display:flex;gap:6px;align-items:center;font-weight:400;margin-top:18px"><input type="checkbox" [(ngModel)]="edit.inheritParent" [disabled]="!auth.isAdmin()">
              inherit categories, versions, custom fields and access from parent</label> }</div>
          <label style="display:flex;gap:6px;align-items:center;font-weight:400"><input type="checkbox" [(ngModel)]="edit.enabled"> enabled</label>
          <div class="row" style="margin-top:12px"><button class="primary" (click)="saveProject()">Save</button>
            @if (auth.isAdmin()) { <button class="danger" (click)="deleteProject()">Delete project</button> }</div>
        }

        @if (tab === 'Categories') {
          <table><thead><tr><th>Name</th><th>Default assignee</th><th></th></tr></thead><tbody>
            @for (c of cats; track c.id) { <tr><td>{{ c.name }} @if (c.inherited) { <span class="muted">(inherited)</span> }</td><td>{{ c.defaultHandlerName }}</td><td class="right">@if (!c.inherited) { <button class="small danger" (click)="delCat(c)">delete</button> }</td></tr> }
          </tbody></table>
          <div class="row" style="margin-top:10px"><div class="grow"><label>New category</label><input [(ngModel)]="newCat"></div>
            <div><label>Default assignee</label><select [(ngModel)]="newCatHandler"><option [ngValue]="null">–</option>@for (u of devs; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div>
            <div><label>&nbsp;</label><button class="primary" (click)="addCat()">Add</button></div></div>
        }

        @if (tab === 'Versions') {
          <table><thead><tr><th>Version</th><th>Released</th><th>Obsolete</th><th></th></tr></thead><tbody>
            @for (v of vers; track v.id) { <tr><td>{{ v.version }} @if (v.inherited) { <span class="muted">(inherited)</span> }</td><td><input type="checkbox" [disabled]="v.inherited" [ngModel]="v.released" (ngModelChange)="toggleVer(v, 'released', $event)"></td>
              <td><input type="checkbox" [disabled]="v.inherited" [ngModel]="v.obsolete" (ngModelChange)="toggleVer(v, 'obsolete', $event)"></td>
              <td class="right">@if (!v.inherited) { <button class="small danger" (click)="delVer(v)">delete</button> }</td></tr> }
          </tbody></table>
          <div class="row" style="margin-top:10px"><div class="grow"><label>New version</label><input [(ngModel)]="newVer"></div>
            <div><label>&nbsp;</label><button class="primary" (click)="addVer()">Add</button></div></div>
        }

        @if (tab === 'Members') {
          <p class="muted">Per-project access levels override the user's global level.</p>
          <table><thead><tr><th>User</th><th>Access level</th><th></th></tr></thead><tbody>
            @for (m of mems; track m.userId) { <tr><td>{{ m.username }}</td><td>{{ auth.label('access', m.accessLevel) }}</td>
              <td class="right"><button class="small danger" (click)="delMember(m)">remove</button></td></tr> }
          </tbody></table>
          <div class="row" style="margin-top:10px">
            <div class="grow"><label>User</label><select [(ngModel)]="memUser"><option [ngValue]="null">– select –</option>@for (u of allUsers; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div>
            <div><label>Level</label><select [(ngModel)]="memLevel">@for (o of levels(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
            <div><label>&nbsp;</label><button class="primary" (click)="setMember()" [disabled]="!memUser">Set</button></div></div>
        }

        @if (tab === 'Custom fields') {
          <p class="muted">Fields that appear on issues of this project. Define new fields under "Custom Fields" (administrator).</p>
          <table><thead><tr><th>Field</th><th></th></tr></thead><tbody>
            @for (f of linked; track f.id) { <tr><td>{{ f.name }}</td><td class="right"><button class="small danger" (click)="unlink(f)">remove</button></td></tr> }
          </tbody></table>
          <div class="row" style="margin-top:10px"><div class="grow"><label>Add existing field</label>
            <select [(ngModel)]="linkId"><option [ngValue]="null">– select –</option>@for (f of available; track f.id) { <option [ngValue]="f.id">{{ f.name }}</option> }</select></div>
            <div><label>&nbsp;</label><button class="primary" (click)="link()" [disabled]="!linkId">Add</button></div></div>
          <p class="muted" style="margin-top:8px">Removing a field also deletes its values on this project's issues.</p>
        }

        @if (tab === 'News') {
          @for (n of news; track n.id) {
            <div class="note"><b>{{ n.headline }}</b> <span class="muted">– {{ n.poster.username }}, {{ n.datePosted | date:'yyyy-MM-dd' }}</span>
              <button class="small danger" style="float:right" (click)="delNews(n)">delete</button><div class="pre">{{ n.body }}</div></div>
          } @empty { <p class="muted">No news for this project.</p> }
          <div style="margin-top:10px"><label>Headline</label><input [(ngModel)]="nn.headline" maxlength="64">
            <label style="margin-top:8px">Body</label><textarea [(ngModel)]="nn.body" rows="3"></textarea>
            <button class="primary" style="margin-top:8px" (click)="addNews()" [disabled]="!nn.headline || !nn.body">Post news</button></div>
        }
      </div>
    }
  </div>`
})
export class ProjectsComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  MANAGER = ACCESS.MANAGER;
  error = '';
  np: any = { name: '', status: 10, viewState: 10, parentId: null };
  sel: ProjectView | null = null; edit: any = {};
  tabs = ['Details', 'Categories', 'Versions', 'Members', 'Custom fields', 'News']; tab = 'Details';
  cats: CategoryView[] = []; vers: VersionView[] = []; mems: MemberView[] = []; news: NewsView[] = [];
  newCat = ''; newCatHandler: number | null = null; newVer = '';
  devs: UserRef[] = []; allUsers: UserRef[] = []; memUser: number | null = null; memLevel = 25;
  nn = { headline: '', body: '' };
  linked: CustomFieldView[] = []; available: CustomFieldView[] = []; linkId: number | null = null;

  private fail = (e: any) => this.error = errMsg(e);

  /** possible parents: every visible project except this one and its own sub-projects */
  parentChoices() {
    const banned = new Set<number>([this.sel!.id]);
    let grew = true;
    while (grew) {
      grew = false;
      for (const p of this.auth.projects())
        if (p.parentId !== null && banned.has(p.parentId) && !banned.has(p.id)) { banned.add(p.id); grew = true; }
    }
    return this.auth.projectOptions().filter(o => !banned.has(o.project.id));
  }

  levels() { return this.auth.options('access').filter(o => o.value >= 10 && o.value < 90 && o.value <= (this.sel?.myAccess ?? 0)); }

  create() {
    this.api.createProject(this.np).subscribe({
      next: async () => { this.np = { name: '', status: 10, viewState: 10, parentId: null }; this.error = ''; await this.auth.reloadProjects(); }, error: this.fail });
  }
  select(p: ProjectView) {
    this.sel = p; this.edit = { ...p }; this.tab = 'Details'; this.error = '';
    this.api.assignable(p.id).subscribe(u => this.devs = u);
    this.api.userLookup().subscribe(u => this.allUsers = u);
    this.setTab('Details');
  }
  setTab(t: string) {
    this.tab = t; const id = this.sel!.id;
    if (t === 'Categories') this.api.categories(id).subscribe(c => this.cats = c);
    if (t === 'Versions') this.api.versions(id).subscribe(v => this.vers = v);
    if (t === 'Members') this.api.members(id).subscribe(m => this.mems = m);
    if (t === 'Custom fields') { this.api.linkedFields(id).subscribe(l => this.linked = l); this.api.availableFields(id).subscribe(a => this.available = a); }
    if (t === 'News') this.api.news(id).subscribe(n => this.news = n.filter(x => x.projectId === id));
  }
  saveProject() {
    // only administrators may change the tree; for managers the fields are omitted (unchanged)
    const body = this.auth.isAdmin()
      ? { ...this.edit, parentId: this.edit.parentId ?? 0, inheritParent: this.edit.parentId ? this.edit.inheritParent : true }
      : { ...this.edit, parentId: undefined, inheritParent: undefined };
    this.api.updateProject(this.sel!.id, body).subscribe({
      next: async p => { await this.auth.reloadProjects(); this.sel = this.auth.projects().find(x => x.id === p.id) ?? null; this.error = ''; }, error: this.fail });
  }
  deleteProject() {
    if (!confirm('Delete project "' + this.sel!.name + '"?')) return;
    this.api.deleteProject(this.sel!.id).subscribe({ next: async () => { this.sel = null; await this.auth.reloadProjects(); this.auth.selectProject(null); }, error: this.fail });
  }
  addCat() { this.api.addCategory(this.sel!.id, { name: this.newCat, defaultHandlerId: this.newCatHandler }).subscribe({ next: () => { this.newCat = ''; this.newCatHandler = null; this.error = ''; this.setTab('Categories'); }, error: this.fail }); }
  delCat(c: CategoryView) { this.api.deleteCategory(c.id).subscribe({ next: () => this.setTab('Categories'), error: this.fail }); }
  addVer() { this.api.addVersion(this.sel!.id, { version: this.newVer }).subscribe({ next: () => { this.newVer = ''; this.error = ''; this.setTab('Versions'); }, error: this.fail }); }
  toggleVer(v: VersionView, field: 'released' | 'obsolete', val: boolean) {
    this.api.updateVersion(v.id, { version: v.version, description: v.description, released: field === 'released' ? val : v.released, obsolete: field === 'obsolete' ? val : v.obsolete })
      .subscribe({ next: () => this.setTab('Versions'), error: this.fail });
  }
  delVer(v: VersionView) { this.api.deleteVersion(v.id).subscribe({ next: () => this.setTab('Versions'), error: this.fail }); }
  setMember() { this.api.setMember(this.sel!.id, this.memUser!, this.memLevel).subscribe({ next: () => { this.error = ''; this.setTab('Members'); }, error: this.fail }); }
  delMember(m: MemberView) { this.api.removeMember(this.sel!.id, m.userId).subscribe({ next: () => this.setTab('Members'), error: this.fail }); }
  link() { this.api.linkField(this.sel!.id, this.linkId!).subscribe({ next: () => { this.linkId = null; this.error = ''; this.setTab('Custom fields'); }, error: this.fail }); }
  unlink(f: CustomFieldView) { if (confirm('Remove "' + f.name + '" from this project (its values on the project\'s issues are deleted)?')) this.api.unlinkField(this.sel!.id, f.id).subscribe({ next: () => this.setTab('Custom fields'), error: this.fail }); }
  addNews() { this.api.addNews({ projectId: this.sel!.id, headline: this.nn.headline, body: this.nn.body }).subscribe({ next: () => { this.nn = { headline: '', body: '' }; this.setTab('News'); }, error: this.fail }); }
  delNews(n: NewsView) { this.api.deleteNews(n.id).subscribe({ next: () => this.setTab('News'), error: this.fail }); }
}
