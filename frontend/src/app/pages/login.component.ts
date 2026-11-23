import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';

@Component({
  selector: 'app-login', standalone: true, imports: [FormsModule],
  template: `
  <div class="wrap"><div class="panel box">
    <h1>🐞 MantisBT</h1>
    <p class="muted">Sign in to continue</p>
    @if (error) { <div class="err">{{ error }}</div> }
    <div style="margin-bottom:10px"><label>Username</label><input [(ngModel)]="username" (keyup.enter)="go()" autofocus></div>
    <div style="margin-bottom:14px"><label>Password</label><input type="password" [(ngModel)]="password" (keyup.enter)="go()"></div>
    <button class="primary" style="width:100%" (click)="go()" [disabled]="busy">{{ busy ? 'Signing in…' : 'Login' }}</button>
  </div></div>`,
  styles: [`.wrap { min-height: 100vh; display: grid; place-items: center; } .box { width: 340px; }`]
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  username = ''; password = ''; error = ''; busy = false;

  go() {
    if (!this.username || !this.password) return;
    this.busy = true; this.error = '';
    this.auth.login(this.username, this.password).subscribe({
      next: async () => { await this.auth.bootstrap(); this.router.navigate(['/']); },
      error: e => { this.error = errMsg(e); this.busy = false; }
    });
  }
}
