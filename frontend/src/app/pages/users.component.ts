import { Component, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { UserView } from '../core/models';

@Component({
  selector: 'app-users', standalone: true, imports: [FormsModule, DatePipe],
  template: `
  <h1>Manage Users</h1>
  @if (error) { <div class="err">{{ error }}</div> }
  <div class="panel"><h2>{{ f.id ? 'Edit user ' + f.username : 'Create user' }}</h2>
    <div class="row">
      <div><label>Username</label><input [(ngModel)]="f.username"></div>
      <div><label>Real name</label><input [(ngModel)]="f.realname"></div>
      <div><label>E-mail</label><input [(ngModel)]="f.email"></div>
      <div><label>Password {{ f.id ? '(blank = keep)' : '' }}</label><input type="password" [(ngModel)]="f.password"></div>
      <div><label>Access level</label><select [(ngModel)]="f.accessLevel">@for (o of levels(); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
      <div><label>&nbsp;</label><label style="display:flex;gap:5px;align-items:center;font-weight:400"><input type="checkbox" [(ngModel)]="f.enabled"> enabled</label></div>
      <div><label>&nbsp;</label><button class="primary" (click)="save()">{{ f.id ? 'Update' : 'Create' }}</button> @if (f.id) { <button (click)="reset()">Cancel</button> }</div>
    </div>
  </div>
  <div class="panel" style="padding:0;overflow:auto"><table>
    <thead><tr><th>Username</th><th>Real name</th><th>E-mail</th><th>Access</th><th>Enabled</th><th>Last visit</th><th></th></tr></thead>
    <tbody>@for (u of users; track u.id) {
      <tr><td>{{ u.username }}</td><td>{{ u.realname }}</td><td>{{ u.email }}</td><td>{{ auth.label('access', u.accessLevel) }}</td>
        <td>{{ u.enabled ? 'yes' : 'no' }}</td><td>{{ u.lastVisit ? (u.lastVisit | date:'yyyy-MM-dd HH:mm') : '–' }}</td>
        <td class="right nowrap"><button class="small" (click)="edit(u)">edit</button> <button class="small danger" (click)="remove(u)" [disabled]="u.id === auth.user()?.id">delete</button></td></tr>
    }</tbody></table></div>`
})
export class UsersComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  users: UserView[] = []; error = '';
  f: any = this.blank();

  constructor() { this.load(); }
  blank() { return { id: null, username: '', realname: '', email: '', password: '', accessLevel: 25, enabled: true }; }
  levels() { return this.auth.options('access').filter(o => o.value >= 10); }
  load() { this.api.users().subscribe(u => this.users = u); }
  reset() { this.f = this.blank(); }
  edit(u: UserView) { this.f = { ...u, password: '' }; }
  save() {
    const call = this.f.id ? this.api.updateUser(this.f.id, this.f) : this.api.createUser(this.f);
    call.subscribe({ next: () => { this.error = ''; this.reset(); this.load(); }, error: e => this.error = errMsg(e) });
  }
  remove(u: UserView) {
    if (!confirm('Delete user ' + u.username + '? (Users that reported/handled issues cannot be deleted – disable them instead.)')) return;
    this.api.deleteUser(u.id).subscribe({ next: () => this.load(), error: e => this.error = errMsg(e) });
  }
}
