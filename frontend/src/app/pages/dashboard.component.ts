import { Component, effect, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { CountEntry, NewsView, SummaryView } from '../core/models';

@Component({
  selector: 'app-dashboard', standalone: true, imports: [RouterLink, DatePipe],
  template: `
  <h1>Dashboard @if (auth.currentProject()) { <span class="muted">– {{ auth.currentProject()!.name }}</span> }</h1>
  @if (s) {
    <div class="grid3">
      <div class="panel"><div class="muted">Open issues</div><div class="card-num">{{ s.open }}</div></div>
      <div class="panel"><div class="muted">Unassigned (open)</div><div class="card-num">{{ s.unassigned }}</div></div>
      <div class="panel"><div class="muted">Total issues</div><div class="card-num">{{ s.total }}</div></div>
    </div>
    <div class="grid3">
      <div class="panel"><h2>By status</h2>
        @for (c of s.byStatus; track c.key) { <div class="r"><a [routerLink]="['/issues']" [queryParams]="{status: c.key}"><span class="chip s{{c.key}}">{{ c.label }}</span></a>
          <div class="bar" [style.width.%]="pct(c, s.byStatus)"></div><b>{{ c.count }}</b></div> }
      </div>
      <div class="panel"><h2>Open by priority</h2>
        @for (c of s.byPriority; track c.key) { <div class="r"><span>{{ c.label }}</span><div class="bar" [style.width.%]="pct(c, s.byPriority)"></div><b>{{ c.count }}</b></div> }
        @if (!s.byPriority.length) { <span class="muted">Nothing open</span> }
      </div>
      <div class="panel"><h2>Open by severity</h2>
        @for (c of s.bySeverity; track c.key) { <div class="r"><span>{{ c.label }}</span><div class="bar" [style.width.%]="pct(c, s.bySeverity)"></div><b>{{ c.count }}</b></div> }
        @if (!s.bySeverity.length) { <span class="muted">Nothing open</span> }
      </div>
    </div>
  }
  <div class="panel"><h2>News</h2>
    @for (n of news; track n.id) {
      <div class="note"><b>{{ n.headline }}</b> <span class="muted">– {{ n.poster.username }}, {{ n.datePosted | date:'yyyy-MM-dd' }}</span>
        <div class="pre">{{ n.body }}</div></div>
    } @empty { <span class="muted">No news.</span> }
  </div>`,
  styles: [`.r { display: grid; grid-template-columns: 110px 1fr 40px; gap: 8px; align-items: center; margin-bottom: 6px; } b { text-align: right; }`]
})
export class DashboardComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  s: SummaryView | null = null;
  news: NewsView[] = [];

  constructor() {
    effect(() => {
      const pid = this.auth.projectId();
      this.api.summary(pid).subscribe(s => this.s = s);
      this.api.news(pid).subscribe(n => this.news = n);
    });
  }
  pct(c: CountEntry, all: CountEntry[]) { const m = Math.max(...all.map(x => x.count), 1); return (c.count / m) * 100; }
}
