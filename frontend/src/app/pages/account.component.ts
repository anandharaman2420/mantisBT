import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';

@Component({
  selector: 'app-account', standalone: true, imports: [FormsModule],
  template: `
  <h1>My Account</h1>
  <div class="panel" style="max-width:520px">
    <dl class="kv"><dt>Username</dt><dd>{{ auth.user()?.username }}</dd><dt>Real name</dt><dd>{{ auth.user()?.realname }}</dd>
      <dt>E-mail</dt><dd>{{ auth.user()?.email }}</dd><dt>Access level</dt><dd>{{ auth.label('access', auth.user()?.accessLevel) }}</dd></dl>
  </div>
  <div class="panel" style="max-width:520px"><h2>E-mail notifications</h2>
    <label style="display:flex;gap:8px;align-items:center;font-weight:400;font-size:14px;color:inherit">
      <input type="checkbox" [ngModel]="auth.user()?.emailEnabled" (ngModelChange)="setEmail($event)">
      Send me an e-mail when issues I reported, handle or monitor change</label>
  </div>
  <div class="panel" style="max-width:520px"><h2>Change password</h2>
    @if (msg) { <div class="ok">{{ msg }}</div> } @if (err) { <div class="err">{{ err }}</div> }
    <div style="margin-bottom:10px"><label>Current password</label><input type="password" [(ngModel)]="cur"></div>
    <div style="margin-bottom:10px"><label>New password (min 6)</label><input type="password" [(ngModel)]="next"></div>
    <button class="primary" (click)="save()">Update password</button>
  </div>`
})
export class AccountComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  cur = ''; next = ''; msg = ''; err = '';
  setEmail(v: boolean) { this.api.setPreferences(v).subscribe({ next: u => this.auth.user.set(u), error: e => this.err = errMsg(e) }); }
  save() {
    this.msg = ''; this.err = '';
    this.api.changePassword(this.cur, this.next).subscribe({
      next: r => { this.msg = r.message; this.cur = this.next = ''; }, error: e => this.err = errMsg(e)
    });
  }
}
