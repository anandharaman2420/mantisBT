import { Component, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { TimeReport } from '../core/models';
import { fmtMinutes } from '../core/time';

/** Time tracking report (Mantis billing page): who logged how much time, per project and period. Managers only. */
@Component({
  selector: 'app-time-report', standalone: true, imports: [FormsModule, RouterLink, DatePipe],
  template: `
  <h1>Time Report</h1>
  <div class="panel"><div class="row">
    <div><label>Project</label><select [(ngModel)]="projectId"><option [ngValue]="null">All my projects</option>
      @for (p of managed(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }</select></div>
    <div><label>From</label><input type="date" [(ngModel)]="from"></div>
    <div><label>To</label><input type="date" [(ngModel)]="to"></div>
    <div><label>&nbsp;</label><button class="primary" (click)="load()">Show</button></div>
    @if (r && r.entries.length) { <div><label>&nbsp;</label><button (click)="csv()">📥 Export CSV</button></div> }
  </div></div>
  @if (error) { <div class="err">{{ error }}</div> }
  @if (r) {
    <div class="grid2">
      <div class="panel"><h2>Total: {{ fmt(r.totalMinutes) }} h</h2>
        <table><thead><tr><th>User</th><th class="right">Time</th></tr></thead><tbody>
          @for (u of r.byUser; track u.user.id) { <tr><td>{{ u.user.username }}</td><td class="right">{{ fmt(u.minutes) }}</td></tr> }
          @empty { <tr><td colspan="2" class="muted">No time logged in this period.</td></tr> }
        </tbody></table></div>
    </div>
    <div class="panel" style="padding:0;overflow:auto"><table>
      <thead><tr><th>Date</th><th>User</th><th>Project</th><th>Issue</th><th>Note</th><th class="right">Time</th></tr></thead>
      <tbody>@for (e of r.entries; track $index) {
        <tr><td class="nowrap">{{ e.date | date:'yyyy-MM-dd HH:mm' }}</td><td>{{ e.user.username }}</td><td>{{ e.projectName }}</td>
          <td><a [routerLink]="['/issues', e.issueId]">{{ e.issueId.toString().padStart(7,'0') }}</a> {{ e.summary }}</td>
          <td class="pre">{{ e.note }}</td><td class="right nowrap">{{ fmt(e.minutes) }}</td></tr> }</tbody>
    </table></div>
  }`
})
export class TimeReportComponent {
  private api = inject(ApiService);
  auth = inject(AuthService);
  fmt = fmtMinutes;
  projectId: number | null = null;
  from = ''; to = '';
  r: TimeReport | null = null; error = '';

  constructor() {
    const now = new Date();
    const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    this.from = iso(new Date(now.getFullYear(), now.getMonth(), 1));
    this.to = iso(now);
    this.load();
  }
  managed() { return this.auth.projects().filter(p => p.myAccess >= 70); }
  load() {
    this.api.timeReport(this.projectId, this.from, this.to).subscribe({
      next: r => { this.r = r; this.error = ''; }, error: e => { this.r = null; this.error = errMsg(e); }
    });
  }
  csv() {
    const q = (s: string) => '"' + (s ?? '').replace(/"/g, '""') + '"';
    const lines = ['Date,User,Project,Issue,Summary,Note,Minutes,Time'];
    for (const e of this.r!.entries)
      lines.push([q(e.date), q(e.user.username), q(e.projectName), e.issueId, q(e.summary), q(e.note), e.minutes, fmtMinutes(e.minutes)].join(','));
    const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob);
    a.download = `time-report_${this.from}_${this.to}.csv`; a.click(); URL.revokeObjectURL(a.href);
  }
}
