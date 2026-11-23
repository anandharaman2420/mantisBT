import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { parseRows, readFile, Sep, SEPARATORS } from '../core/bulk-parse';
import { ImportResult, MemberView, UserRef } from '../core/models';
import { ImportResultComponent } from './import-result.component';

/** Onboard many users to a project at once with a role (viewer … manager): paste a list, pick users, or manage the current members. */
@Component({
  selector: 'app-bulk-members', standalone: true, imports: [FormsModule, ImportResultComponent],
  template: `
  <details class="panel" style="background:#f7fafc;margin-top:14px" (toggle)="onToggle($event)">
    <summary style="cursor:pointer;font-weight:600">⚡ Bulk onboard members</summary>
    <div class="tabs" style="margin-top:10px">
      <button [class.active]="mode === 'list'" (click)="mode = 'list'">Paste / upload list</button>
      <button [class.active]="mode === 'pick'" (click)="mode = 'pick'; loadUsers()">Pick users</button>
      <button [class.active]="mode === 'manage'" (click)="mode = 'manage'; loadMembers()">Current members</button>
    </div>
    @if (error) { <div class="err">{{ error }}</div> }

    @if (mode === 'list') {
      <p class="muted" style="margin:0 0 8px">One user per line: <code>username</code> or e-mail, then optionally the <b>role</b> (viewer, reporter, updater, developer, manager).
        Lines without a role get the default role below. Existing members get their role changed.</p>
      <textarea rows="8" [(ngModel)]="text" (ngModelChange)="parse()" placeholder="jsmith&#9;developer&#10;mrossi&#9;manager&#10;anna@example.com"></textarea>
      <div class="row" style="margin-top:8px;align-items:flex-end">
        <div><label>…or upload (.csv .tsv .txt)</label><input type="file" accept=".csv,.tsv,.txt" (change)="load($event)" style="width:auto"></div>
        <div><label>Column separator</label><select [(ngModel)]="sep" (ngModelChange)="parse()">@for (s of seps; track s.value) { <option [value]="s.value">{{ s.label }}</option> }</select></div>
        <div><label>Default role</label><select [(ngModel)]="defaultRole">@for (o of roles(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        <label style="display:flex;gap:5px;align-items:center;font-weight:400;font-size:14px;color:inherit"><input type="checkbox" [(ngModel)]="header" (ngModelChange)="parse()"> first line is a header</label>
      </div>
      @if (auth.isAdmin()) {
        <div class="row" style="margin-top:10px;align-items:flex-end">
          <label style="display:flex;gap:5px;align-items:center;font-weight:400;font-size:14px;color:inherit"><input type="checkbox" [(ngModel)]="createMissing"> create users that do not exist yet</label>
          @if (createMissing) {
            <div><label>Initial password (min 6)</label><input [(ngModel)]="initialPassword" style="width:200px"></div>
            <span class="muted">Columns then: username, role, <b>e-mail</b>, real name. New users have to change the password themselves under My Account.</span>
          }
        </div>
      }
      @if (rows.length) { <div class="muted" style="margin-top:8px"><b>{{ rows.length }}</b> users found.</div> }
      <div style="margin-top:10px"><button class="primary" (click)="runList()" [disabled]="!rows.length || busy">{{ busy ? 'Working…' : 'Add / update ' + rows.length + ' users' }}</button></div>
    }

    @if (mode === 'pick') {
      <div class="row" style="align-items:flex-end">
        <div class="grow"><label>Filter users</label><input [(ngModel)]="filter" placeholder="type to filter by name…"></div>
        <div><label>Role</label><select [(ngModel)]="defaultRole">@for (o of roles(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        <div><label>&nbsp;</label><button class="primary" (click)="runPick()" [disabled]="!picked.size || busy">Add {{ picked.size }} selected</button></div>
      </div>
      @if (!users.length) { <p class="muted">No user list available for your account – use "Paste / upload list".</p> }
      <div style="max-height:260px;overflow:auto;margin-top:8px;border:1px solid var(--border);border-radius:4px;padding:6px">
        <label style="display:flex;gap:6px;align-items:center;font-weight:600;color:inherit;font-size:14px"><input type="checkbox" [checked]="allFilteredPicked()" (change)="toggleAllFiltered()"> select all {{ filtered().length }} shown</label>
        @for (u of filtered(); track u.id) {
          <label style="display:flex;gap:6px;align-items:center;font-weight:400;color:inherit;font-size:14px"><input type="checkbox" [checked]="picked.has(u.id)" (change)="togglePick(u.id)"> {{ u.username }} <span class="muted">{{ u.realname }}</span></label>
        }
      </div>
    }

    @if (mode === 'manage') {
      <div class="row" style="align-items:flex-end;margin-bottom:8px">
        <div><label>Role for selected</label><select [(ngModel)]="newRole">@for (o of roles(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
        <div><label>&nbsp;</label><button (click)="setRoleSelected()" [disabled]="!sel.size || busy">Set role</button></div>
        <div><label>&nbsp;</label><button class="danger" (click)="removeSelected()" [disabled]="!sel.size || busy">Remove {{ sel.size }} selected</button></div>
      </div>
      <div style="max-height:300px;overflow:auto"><table><thead><tr>
        <th style="width:28px"><input type="checkbox" [checked]="mem.length > 0 && sel.size === mem.length" (change)="toggleAllMembers()"></th><th>User</th><th>Role</th></tr></thead><tbody>
        @for (m of mem; track m.userId) { <tr><td><input type="checkbox" [checked]="sel.has(m.userId)" (change)="toggleMember(m.userId)"></td>
          <td>{{ m.username }} <span class="muted">{{ m.realname }}</span></td><td>{{ auth.label('access', m.accessLevel) }}</td></tr> }
        @empty { <tr><td colspan="3" class="muted">No members with a project-specific role yet.</td></tr> }
      </tbody></table></div>
    }
    <app-import-result [r]="result" />
  </details>`
})
export class BulkMembersComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  @Input({ required: true }) projectId!: number;
  @Input() myAccess = 70;
  @Output() done = new EventEmitter<void>();

  mode: 'list' | 'pick' | 'manage' = 'list';
  seps = SEPARATORS; sep: Sep = 'auto'; header = false; text = '';
  rows: string[][] = []; defaultRole = 55; newRole = 55;
  createMissing = false; initialPassword = '';
  users: UserRef[] = []; filter = ''; picked = new Set<number>();
  mem: MemberView[] = []; sel = new Set<number>();
  busy = false; error = ''; result: ImportResult | null = null;

  ngOnInit() { }
  onToggle(_: Event) { }

  roles() { return this.auth.options('access').filter(o => o.value >= 10 && o.value < 90 && o.value <= this.myAccess); }

  parse() { this.rows = parseRows(this.text, this.sep, this.header); }
  async load(ev: Event) { const f = (ev.target as HTMLInputElement).files?.[0]; if (f) { this.text = await readFile(f); this.parse(); } }

  /** "developer" | "55" -> 55;  "" -> null (use default);  unknown -> undefined */
  private role(txt: string | undefined): number | null | undefined {
    const t = (txt ?? '').trim().toLowerCase();
    if (!t) return null;
    if (/^\d+$/.test(t)) return Number(t);
    return this.auth.options('access').find(o => o.label.toLowerCase() === t)?.value;
  }

  private finish(r: ImportResult) { this.busy = false; this.result = r; this.done.emit(); }
  private fail = (e: any) => { this.busy = false; this.error = errMsg(e); };

  runList() {
    this.error = ''; this.result = null;
    const body: any[] = [];
    for (let i = 0; i < this.rows.length; i++) {
      const r = this.rows[i]; const lvl = this.role(r[1]);
      if (lvl === undefined) { this.error = `Row ${i + 1}: unknown role "${r[1]}" (use viewer, reporter, updater, developer or manager)`; return; }
      body.push({ user: r[0], accessLevel: lvl, email: r[2] ?? '', realname: r[3] ?? '' });
    }
    this.busy = true;
    this.api.bulkMembers(this.projectId, { rows: body, defaultLevel: this.defaultRole, createMissing: this.createMissing, initialPassword: this.initialPassword })
      .subscribe({ next: r => this.finish(r), error: this.fail });
  }

  loadUsers() {
    if (this.users.length) return;
    this.api.userLookup().subscribe({ next: u => this.users = u, error: () => this.users = [] });
  }
  filtered() { const f = this.filter.trim().toLowerCase(); return this.users.filter(u => !f || (u.username + ' ' + u.realname).toLowerCase().includes(f)); }
  togglePick(id: number) { this.picked.has(id) ? this.picked.delete(id) : this.picked.add(id); }
  allFilteredPicked() { const f = this.filtered(); return f.length > 0 && f.every(u => this.picked.has(u.id)); }
  toggleAllFiltered() { const all = this.allFilteredPicked(); this.filtered().forEach(u => all ? this.picked.delete(u.id) : this.picked.add(u.id)); }
  runPick() {
    this.error = ''; this.result = null; this.busy = true;
    const rows = this.users.filter(u => this.picked.has(u.id)).map(u => ({ user: u.username, accessLevel: this.defaultRole }));
    this.api.bulkMembers(this.projectId, { rows, defaultLevel: this.defaultRole }).subscribe({
      next: r => { this.picked.clear(); this.finish(r); }, error: this.fail });
  }

  loadMembers() { this.sel.clear(); this.api.members(this.projectId).subscribe({ next: m => this.mem = m, error: this.fail }); }
  toggleMember(id: number) { this.sel.has(id) ? this.sel.delete(id) : this.sel.add(id); }
  toggleAllMembers() { if (this.sel.size === this.mem.length) this.sel.clear(); else this.mem.forEach(m => this.sel.add(m.userId)); }
  setRoleSelected() {
    this.error = ''; this.busy = true;
    const rows = this.mem.filter(m => this.sel.has(m.userId)).map(m => ({ user: m.username, accessLevel: this.newRole }));
    this.api.bulkMembers(this.projectId, { rows, defaultLevel: this.newRole }).subscribe({ next: r => { this.finish(r); this.loadMembers(); }, error: this.fail });
  }
  removeSelected() {
    if (!confirm(`Remove ${this.sel.size} member(s) from this project?`)) return;
    this.error = ''; this.busy = true;
    this.api.removeMembersBulk(this.projectId, [...this.sel]).subscribe({ next: r => { this.finish(r); this.loadMembers(); }, error: this.fail });
  }
}
